#!/usr/bin/env python3
"""phonectl: let Claude Code in Android's Linux Terminal see and drive the Android screen.

Two jobs in one file:
  phonectl serve      MCP server (stdio) that Claude Code talks to
  phonectl pair       one-time pairing with Android's Wireless debugging
  phonectl connect    find the phone again (wireless debugging changes ports)
  phonectl pin        keep a fixed port (5555) until the phone reboots
  phonectl status     show what's connected
  phonectl doctor     check every piece and say what to fix
  phonectl do '[{"open_app":"settings"},{"tap":"Battery"}]'   run steps from the terminal
  phonectl shortcuts  list saved voice shortcuts

Fast path: when the Pocket Pilot app's accessibility service ("fast control") is on, every tool goes
through it (reads the screen as text, taps in milliseconds, waits for the screen to settle).
Otherwise the tools fall back to adb over Wireless debugging.

No pip packages needed. Pillow (python3-pil) is used to shrink screenshots if present.
"""

import base64
import concurrent.futures
import io
import json
import os
import re
import shlex
import socket
import subprocess
import sys
import threading
import time
import xml.etree.ElementTree as ET

VERSION = "3.1.0"
CONF_DIR = os.path.expanduser("~/.config/phone-control")
CONF_FILE = os.path.join(CONF_DIR, "config.json")
SHOT_WIDTH = int(os.environ.get("PHONE_SHOT_WIDTH", "540"))  # screenshot width Claude sees
GUARD = os.environ.get("PHONE_GUARD", "on").lower() != "off"
SCAN_FROM, SCAN_TO = 30000, 50000

RISKY_WORDS = [
    "send", "post", "publish", "share", "reply", "tweet", "submit", "pay", "buy", "purchase",
    "checkout", "check out", "order", "place order", "subscribe", "donate", "transfer", "confirm",
    "delete", "remove", "erase", "uninstall", "clear", "reset", "factory", "discard", "trash",
    "archive", "block", "unfriend", "unfollow", "sign out", "log out", "logout", "turn off",
    "disable", "allow", "grant", "accept", "agree", "install", "call", "dial",
]
RISKY_RE = re.compile(r"\b(" + "|".join(re.escape(w) for w in RISKY_WORDS) + r")\b", re.I)

APP_ALIASES = {
    "chrome": "com.android.chrome", "gmail": "com.google.android.gm",
    "maps": "com.google.android.apps.maps", "google maps": "com.google.android.apps.maps",
    "youtube": "com.google.android.youtube", "settings": "com.android.settings",
    "camera": "com.google.android.GoogleCamera", "messages": "com.google.android.apps.messaging",
    "phone": "com.google.android.dialer", "photos": "com.google.android.apps.photos",
    "calendar": "com.google.android.calendar", "clock": "com.google.android.deskclock",
    "contacts": "com.google.android.contacts", "play store": "com.android.vending",
    "files": "com.google.android.apps.nbu.files", "drive": "com.google.android.apps.docs",
    "keep": "com.google.android.keep", "calculator": "com.google.android.calculator",
    "spotify": "com.spotify.music", "whatsapp": "com.whatsapp", "discord": "com.discord",
    "instagram": "com.instagram.android", "tiktok": "com.zhiliaoapp.musically",
    "terminal": "com.android.virtualization.terminal", "claude": "com.anthropic.claude",
}

KEYS = {
    "home": 3, "back": 4, "call": 5, "endcall": 6, "up": 19, "down": 20, "left": 21, "right": 22,
    "center": 23, "volume_up": 24, "volume_down": 25, "power": 26, "camera": 27, "tab": 61,
    "space": 62, "enter": 66, "delete": 67, "backspace": 67, "menu": 82, "search": 84,
    "play_pause": 85, "next": 87, "previous": 88, "escape": 111, "forward_delete": 112,
    "recents": 187, "app_switch": 187, "brightness_down": 220, "brightness_up": 221,
    "sleep": 223, "wakeup": 224, "assist": 219, "notifications": 83, "mute": 164,
}


# --------------------------------------------------------------------------- config

def load_conf():
    try:
        with open(CONF_FILE) as f:
            return json.load(f)
    except Exception:
        return {}


def save_conf(conf):
    os.makedirs(CONF_DIR, exist_ok=True)
    tmp = CONF_FILE + ".tmp"
    with open(tmp, "w") as f:
        json.dump(conf, f, indent=2)
    os.replace(tmp, CONF_FILE)


# --------------------------------------------------------------------------- adb basics

def run(cmd, timeout=20, binary=False, input_bytes=None):
    try:
        p = subprocess.run(cmd, capture_output=True, timeout=timeout, input=input_bytes)
    except FileNotFoundError:
        raise RuntimeError("adb isn't installed. Run the setup script again.")
    except subprocess.TimeoutExpired:
        raise RuntimeError("The phone took too long to answer (%s)." % " ".join(cmd[:4]))
    if binary:
        return p.returncode, p.stdout, p.stderr.decode(errors="replace")
    return p.returncode, p.stdout.decode(errors="replace"), p.stderr.decode(errors="replace")


def adb_devices():
    _, out, _ = run(["adb", "devices"], timeout=10)
    devs = []
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2:
            devs.append((parts[0], parts[1]))
    return devs


def online_serial():
    conf = load_conf()
    want = os.environ.get("PHONE_SERIAL") or conf.get("target")
    devs = [d for d, s in adb_devices() if s == "device"]
    if want and want in devs:
        return want
    return devs[0] if devs else None


def adb(args, timeout=20, binary=False, serial=None):
    serial = serial or online_serial()
    if not serial:
        # try once to reconnect quietly
        connect(quiet=True)
        serial = online_serial()
    if not serial:
        raise RuntimeError(
            "The phone isn't connected. Turn on Settings > Developer options > Wireless debugging, "
            "then run: phonectl connect")
    return run(["adb", "-s", serial] + args, timeout=timeout, binary=binary)


def shell(cmd, timeout=20):
    code, out, err = adb(["shell", cmd], timeout=timeout)
    if code != 0 and not out:
        raise RuntimeError(err.strip() or "command failed: %s" % cmd)
    return out


def adb_version():
    try:
        _, out, _ = run(["adb", "version"], timeout=10)
    except RuntimeError:
        return None, ""
    m = re.search(r"Version (\d+)\.(\d+)", out)
    return ((int(m.group(1)), int(m.group(2))) if m else None), out.strip()


# --------------------------------------------------------------------------- finding the phone

def gateway_ips():
    ips = []
    try:
        _, out, _ = run(["ip", "route"], timeout=5)
        for line in out.splitlines():
            m = re.match(r"default via (\S+)", line)
            if m:
                ips.append(m.group(1))
    except Exception:
        pass
    return ips


def candidate_hosts():
    conf = load_conf()
    hosts = []
    for h in [os.environ.get("PHONE_HOST"), conf.get("host")] + conf.get("extra_hosts", []) + gateway_ips():
        if h and h not in hosts:
            hosts.append(h)
    return hosts


def port_open(host, port, timeout=0.35):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(timeout)
    try:
        return s.connect_ex((host, port)) == 0
    except Exception:
        return False
    finally:
        s.close()


def scan_ports(host, lo=SCAN_FROM, hi=SCAN_TO, workers=400):
    found = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as ex:
        futs = {ex.submit(port_open, host, p): p for p in range(lo, hi)}
        for f in concurrent.futures.as_completed(futs):
            if f.result():
                found.append(futs[f])
    return sorted(found)


def try_connect(target):
    run(["adb", "connect", target], timeout=10)
    time.sleep(0.6)
    for d, s in adb_devices():
        if d == target and s == "device":
            return True
        if d == target and s in ("offline", "unauthorized"):
            run(["adb", "disconnect", target], timeout=5)
    return False


def remember(target):
    conf = load_conf()
    conf["target"] = target
    conf["host"] = target.rsplit(":", 1)[0]
    save_conf(conf)


def connect(quiet=False, scan=True):
    say = (lambda *a: None) if quiet else print
    run(["adb", "start-server"], timeout=15)
    s = online_serial()
    if s:
        say("Already connected: %s" % s)
        return s
    conf = load_conf()
    tries = []
    if conf.get("target"):
        tries.append(conf["target"])
    for h in candidate_hosts():
        tries.append("%s:5555" % h)
    for t in tries:
        host, port = t.rsplit(":", 1)
        if port_open(host, int(port), 0.5) and try_connect(t):
            remember(t)
            say("Connected: %s" % t)
            return t
    if not scan:
        return None
    for h in candidate_hosts():
        say("Looking for the phone's debug port on %s ..." % h)
        for p in scan_ports(h):
            t = "%s:%d" % (h, p)
            if try_connect(t):
                remember(t)
                say("Connected: %s" % t)
                return t
    say("Couldn't find the phone.\n"
        "1. Settings > System > Developer options > Wireless debugging: turn it on (Wi-Fi must be on).\n"
        "2. Run: phonectl connect\n"
        "Never paired on this phone? Run: phonectl pair\n"
        "You can also type the IP:port shown under 'IP address & Port':  phonectl connect 192.168.1.23:41234")
    return None


# --------------------------------------------------------------------------- CLI commands

def cmd_pair(args):
    ver, _ = adb_version()
    if ver and ver < (30, 0):
        print("Your adb is too old to pair (needs 30+). Run the setup script again; it upgrades adb.")
        return 1
    print("On the phone: Settings > System > Developer options > Wireless debugging > ON,\n"
          "then tap 'Pair device with pairing code'. Keep that popup open\n"
          "(put Terminal and Settings in split screen, or use the popup window).\n")
    if len(args) >= 2:
        hostport, code = args[0], args[1]
    elif len(args) == 1 and " " in args[0]:
        hostport, code = args[0].split()[:2]
    else:
        line = input("Type the IP address & Port from the popup, a space, then the 6-digit code:\n> ").strip()
        parts = line.replace(",", " ").split()
        if len(parts) < 2:
            print("I need both, like: 192.168.1.23:37155 482913")
            return 1
        hostport, code = parts[0], parts[1]
    code_, out, err = run(["adb", "pair", hostport, code], timeout=30)
    print((out + err).strip())
    if "Successfully paired" not in out + err:
        print("\nPairing didn't work. The code expires fast; tap 'Pair device' again and retry.")
        return 1
    host = hostport.rsplit(":", 1)[0]
    conf = load_conf()
    conf["host"] = host
    extra = conf.get("extra_hosts", [])
    if host not in extra:
        extra.append(host)
    conf["extra_hosts"] = extra
    save_conf(conf)
    print("\nPaired. Now connecting ...")
    return 0 if connect() else 1


def cmd_connect(args):
    if args:
        t = args[0]
        if ":" not in t:
            t += ":5555"
        if try_connect(t):
            remember(t)
            print("Connected: %s" % t)
            return 0
        print("That address didn't answer. Check Wireless debugging is on, then run: phonectl connect")
        return 1
    return 0 if connect() else 1


def cmd_pin(args):
    s = online_serial() or connect()
    if not s:
        return 1
    host = s.rsplit(":", 1)[0]
    code, out, err = run(["adb", "-s", s, "tcpip", "5555"], timeout=15)
    print((out + err).strip())
    time.sleep(2)
    if try_connect("%s:5555" % host):
        remember("%s:5555" % host)
        print("Pinned to %s:5555 until the phone reboots. Reconnecting is now instant." % host)
        return 0
    print("Pin didn't take. That's fine, phonectl connect still works.")
    return 1


def cmd_status(args):
    fast = APP.available()
    if fast:
        info = APP.call("info")
        print("Fast control: linked (Pocket Pilot app), screen %sx%s, in %s" % (info.get("w"), info.get("h"), info.get("pkg")))
    else:
        print("Fast control: not linked (turn it on in Pocket Pilot; the bridge must be running)")
    s = online_serial()
    if not s:
        print("adb: not connected%s" % (" (optional now)" if fast else ". Run: phonectl connect"))
        return 0 if fast else 1
    model = shell("getprop ro.product.model").strip()
    size = shell("wm size").strip().splitlines()[-1]
    print("adb: connected to %s (%s), %s" % (model, s, size))
    return 0


def cmd_doctor(args):
    ok = True
    ver, raw = adb_version()
    if not ver:
        print("x adb missing -> run the setup script"); ok = False
    elif ver < (30, 0):
        print("! adb %d.%d is too old to pair (needs 30+). Setup script upgrades it." % ver); ok = False
    else:
        print("ok adb %d.%d" % ver)
    print("ok hosts to try: %s" % ", ".join(candidate_hosts() or ["none found"]))
    fast = APP.available()
    print("ok fast control linked (Pocket Pilot app)" if fast else
          "! fast control not linked -> Pocket Pilot > Turn on fast control, and pilot start")
    s = online_serial()
    print(("ok adb phone connected: %s" % s) if s else "! adb not connected -> phonectl connect (optional with fast control)")
    ok = ok and (bool(s) or fast)
    try:
        import PIL  # noqa
        print("ok Pillow present (small, fast screenshots)")
    except Exception:
        print("! Pillow missing -> sudo apt install -y python3-pil (screenshots will be full size)")
    r = subprocess.run(["bash", "-lc", "command -v claude"], capture_output=True, text=True)
    print(("ok claude at %s" % r.stdout.strip()) if r.returncode == 0 else "x claude not on PATH")
    return 0 if ok else 1


# --------------------------------------------------------------------------- screen

class Screen:
    def __init__(self):
        self.scale = None          # device px per screenshot px
        self.screen_w = None
        self.last_nodes = []
        self.last_dump_t = 0.0
        self.lock = threading.Lock()

    def _set_scale(self, w):
        self.screen_w = w
        self.scale = max(1.0, w / float(SHOT_WIDTH))

    def ensure_scale(self):
        if self.scale:
            return
        out = shell("wm size")
        m = re.findall(r"(\d+)x(\d+)", out)
        w = int(m[-1][0]) if m else 1080
        self._set_scale(w)

    def to_dev(self, x, y):
        self.ensure_scale()
        return int(round(float(x) * self.scale)), int(round(float(y) * self.scale))

    def to_shot(self, x, y):
        self.ensure_scale()
        return int(round(x / self.scale)), int(round(y / self.scale))

    def screenshot(self, width=None):
        code, data, err = adb(["exec-out", "screencap", "-p"], timeout=25, binary=True)
        if code != 0 or not data.startswith(b"\x89PNG"):
            raise RuntimeError("Screenshot failed: %s" % (err.strip() or "no image"))
        width = int(width or SHOT_WIDTH)
        try:
            from PIL import Image
            img = Image.open(io.BytesIO(data))
            w, h = img.size
            self.screen_w = w
            self.scale = max(1.0, w / float(SHOT_WIDTH))
            out_w = int(round(w / self.scale)) if width == SHOT_WIDTH else min(width, w)
            out_h = int(round(h * out_w / float(w)))
            img = img.convert("RGB").resize((out_w, out_h))
            buf = io.BytesIO()
            img.save(buf, "JPEG", quality=72)
            return buf.getvalue(), "image/jpeg", out_w, out_h, (w, h)
        except ImportError:
            w = int.from_bytes(data[16:20], "big")
            h = int.from_bytes(data[20:24], "big")
            self.screen_w, self.scale = w, 1.0
            return data, "image/png", w, h, (w, h)

    def dump(self):
        xml = ""
        for attempt in range(2):
            code, out, err = adb(["exec-out", "uiautomator", "dump", "/dev/tty"], timeout=25)
            i = out.find("<?xml")
            j = out.rfind("</hierarchy>")
            if i >= 0 and j > i:
                xml = out[i:j + len("</hierarchy>")]
                break
            shell("uiautomator dump /sdcard/.pc_dump.xml >/dev/null 2>&1", timeout=25)
            out = shell("cat /sdcard/.pc_dump.xml", timeout=15)
            i = out.find("<?xml")
            if i >= 0:
                xml = out[i:]
                break
            time.sleep(0.8)
        if not xml:
            raise RuntimeError("Couldn't read the screen layout (some apps block it). Use screenshot instead.")
        if "<!DOCTYPE" in xml or "<!ENTITY" in xml:
            raise RuntimeError("Unexpected screen layout data; use screenshot instead.")
        root = ET.fromstring(xml)
        nodes = []
        for el in root.iter("node"):
            b = re.findall(r"\d+", el.get("bounds", ""))
            if len(b) != 4:
                continue
            x1, y1, x2, y2 = map(int, b)
            nodes.append({
                "text": el.get("text", ""), "desc": el.get("content-desc", ""),
                "id": el.get("resource-id", ""), "cls": el.get("class", "").split(".")[-1],
                "pkg": el.get("package", ""), "clickable": el.get("clickable") == "true",
                "long": el.get("long-clickable") == "true", "scroll": el.get("scrollable") == "true",
                "edit": "EditText" in el.get("class", ""), "checked": el.get("checked") == "true",
                "checkable": el.get("checkable") == "true", "focused": el.get("focused") == "true",
                "enabled": el.get("enabled") != "false", "bounds": (x1, y1, x2, y2),
            })
        if nodes and not self.scale:
            self._set_scale(max(n["bounds"][2] for n in nodes))
        with self.lock:
            self.last_nodes, self.last_dump_t = nodes, time.time()
        return nodes

    def label_at(self, dx, dy, nodes):
        hits = [n for n in nodes if n["bounds"][0] <= dx <= n["bounds"][2] and n["bounds"][1] <= dy <= n["bounds"][3]]
        if not hits:
            return ""
        def area(n):
            b = n["bounds"]; return (b[2] - b[0]) * (b[3] - b[1])
        clickable = [n for n in hits if n["clickable"]] or hits
        target = min(clickable, key=area)
        tb = target["bounds"]
        inner = [n for n in nodes if n["bounds"][0] >= tb[0] and n["bounds"][1] >= tb[1]
                 and n["bounds"][2] <= tb[2] and n["bounds"][3] <= tb[3]]
        words = [target["text"], target["desc"], target["id"].split("/")[-1].replace("_", " ")]
        words += [n["text"] or n["desc"] for n in inner]
        seen, out = set(), []
        for w in words:
            if w and w.lower() not in seen:
                seen.add(w.lower()); out.append(w)
        return " ".join(out).strip()[:120]


SCREEN = Screen()


def risky(label):
    return bool(label and RISKY_RE.search(label))


def guard_check(dx, dy, confirmed):
    if not GUARD or confirmed:
        return None
    try:
        nodes = SCREEN.last_nodes if time.time() - SCREEN.last_dump_t < 8 else SCREEN.dump()
    except Exception:
        return None
    label = SCREEN.label_at(dx, dy, nodes)
    if risky(label):
        return ("HOLD: this looks like \"%s\". That can send, pay, delete or change something. "
                "Ask the user in plain words and wait for their yes. Only after they say yes, "
                "call again with confirmed=true." % label)
    return None


def text_for_input(s):
    # `input text` turns %s into a space; the whole string is single-quoted for the phone's shell.
    return s.replace(" ", "%s")


# --------------------------------------------------------------------------- fast backend (Pocket Pilot app)

BRIDGE_CONF = os.path.join(CONF_DIR, "bridge.json")
SHORTCUTS_FILE = os.path.join(CONF_DIR, "shortcuts.json")
TIMING_LOG = os.path.expanduser("~/phone-control/timing.log")
BACKEND = os.environ.get("PHONE_BACKEND", "auto").lower()   # auto | app | adb
BRIDGE_URL = os.environ.get("PILOT_URL", "http://127.0.0.1:%s" % os.environ.get("PILOT_PORT", "8765"))

# Java-friendly version of the risky-word pattern, sent to the app so it can hold taps itself.
GUARD_RE = r"\b(" + "|".join(w.replace(" ", r"\s+") for w in RISKY_WORDS) + r")\b"
# Fields where pressing Enter usually sends something.
SUBMIT_GUARD_RE = (r"message|chat|reply|comment|compose|send|post|tweet|caption|"
                   r"messaging|whatsapp|discord|telegram|signal|slack|instagram|facebook|messenger|gm\b|mail")


class AppDown(Exception):
    pass


class AppBackend:
    """Talks to Pocket Pilot's accessibility service through the voice bridge's /agent/call."""

    def __init__(self):
        self.state_t = 0.0
        self.state = False
        self.screen = None   # (w, h)

    def _token(self):
        try:
            with open(BRIDGE_CONF) as f:
                return json.load(f).get("token", "")
        except Exception:
            return ""

    def _req(self, method, path, body=None, timeout=30):
        import urllib.request
        import urllib.error
        data = json.dumps(body).encode() if body is not None else None
        r = urllib.request.Request(BRIDGE_URL + path, data=data, method=method,
                                   headers={"X-Token": self._token(), "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(r, timeout=timeout) as resp:
                return json.loads(resp.read().decode() or "{}")
        except urllib.error.HTTPError as e:
            try:
                return json.loads(e.read().decode() or "{}") | {"_code": e.code}
            except Exception:
                return {"_code": e.code, "error": str(e)}
        except Exception as e:
            raise AppDown(str(e))

    def available(self):
        if BACKEND == "adb":
            return False
        if time.time() - self.state_t < 4:
            return self.state
        try:
            self.state = bool(self._req("GET", "/agent/status", timeout=2).get("connected"))
        except AppDown:
            self.state = False
        self.state_t = time.time()
        return self.state

    def call(self, cmd, args=None, timeout=25):
        r = self._req("POST", "/agent/call", {"cmd": cmd, "args": args or {}, "timeout": timeout},
                      timeout=timeout + 5)
        if r.get("_code") in (503, 504) or r.get("offline"):
            self.state, self.state_t = False, time.time()
            raise AppDown(r.get("error", "app not linked"))
        res = r.get("result")
        if res is None:
            raise AppDown(r.get("error", "no answer from the app"))
        if cmd in ("info", "tree") and res.get("w"):
            self.screen = (res["w"], res["h"])
            SCREEN._set_scale(res["w"])
        return res

    def ensure_scale(self):
        if not SCREEN.scale:
            info = self.call("info")
            SCREEN._set_scale(info.get("w") or 1080)


APP = AppBackend()


def use_app():
    if BACKEND == "app":
        return True
    return APP.available()


def after_text(res):
    """Say what the screen shows now, so Claude doesn't need a separate look."""
    if "summary" not in res:
        return ""
    s = res["summary"]
    if res.get("changed"):
        return " Screen changed (%.1fs): %s" % (res.get("waited_ms", 0) / 1000.0, s)
    return " Screen looks the same: %s" % s


HOLD_TEXT = ("HOLD: this looks like \"%s\". That can send, pay, delete or change something. "
             "Ask the user in plain words and wait for their yes. Only after they say yes, "
             "call again with confirmed=true.")


def log_timing(entry):
    try:
        os.makedirs(os.path.dirname(TIMING_LOG), exist_ok=True)
        with open(TIMING_LOG, "a") as f:
            f.write(json.dumps(entry) + "\n")
    except Exception:
        pass


# --------------------------------------------------------------------------- tools

CONFIRM_NOTE = (" Before any action that sends a message, posts, pays or buys, deletes, or changes a "
                "setting or permission, ask the user and wait for a clear yes.")
COORD_NOTE = " Coordinates are in screenshot pixels (the image from phone_screenshot), not device pixels."
AFTER_NOTE = (" The answer already says what the screen shows once it settles, so you usually don't "
              "need to look again.")

SCREENS = {
    "wifi": "android.settings.WIFI_SETTINGS", "bluetooth": "android.settings.BLUETOOTH_SETTINGS",
    "battery": "android.intent.action.POWER_USAGE_SUMMARY", "display": "android.settings.DISPLAY_SETTINGS",
    "sound": "android.settings.SOUND_SETTINGS", "apps": "android.settings.APPLICATION_SETTINGS",
    "location": "android.settings.LOCATION_SOURCE_SETTINGS",
    "developer": "android.settings.APPLICATION_DEVELOPMENT_SETTINGS",
    "accessibility": "android.settings.ACCESSIBILITY_SETTINGS", "network": "android.settings.WIRELESS_SETTINGS",
    "storage": "android.settings.INTERNAL_STORAGE_SETTINGS", "date": "android.settings.DATE_SETTINGS",
    "security": "android.settings.SECURITY_SETTINGS", "nfc": "android.settings.NFC_SETTINGS",
    "airplane": "android.settings.AIRPLANE_MODE_SETTINGS", "do_not_disturb": "android.settings.ZEN_MODE_SETTINGS",
    "default_apps": "android.settings.MANAGE_DEFAULT_APPS_SETTINGS", "settings": "android.settings.SETTINGS",
    "notifications": "android.settings.ALL_APPS_NOTIFICATION_SETTINGS", "hotspot": "android.settings.TETHER_SETTINGS",
    "data_usage": "android.settings.DATA_USAGE_SETTINGS", "language": "android.settings.LOCALE_SETTINGS",
    "keyboard": "android.settings.INPUT_METHOD_SETTINGS", "privacy": "android.settings.PRIVACY_SETTINGS",
    "about": "android.settings.DEVICE_INFO_SETTINGS", "cast": "android.settings.CAST_SETTINGS",
}
GLOBAL_KEYS = {"back", "home", "recents", "app_switch", "notifications", "quick_settings", "power_dialog",
               "split_screen", "lock"}
MEDIA_KEYS = {"play_pause", "next", "previous", "volume_up", "volume_down", "mute"}

STEP_HELP = ('Steps are objects, run in order, each one waits for the screen to settle: '
             '{"open_app":"spotify"} {"open_url":"https://..."} {"open_screen":"wifi"} '
             '{"tap":"Liked Songs"} (by text) or {"tap":[x,y]} {"long_press":"text"} '
             '{"type":"hello","into":"Search","submit":true} {"key":"back"} {"scroll":"down"} '
             '{"swipe":[x1,y1,x2,y2]} {"wait_for":"text","timeout":8} {"wait_gone":"Loading"} '
             '{"wait_change":true} {"expect":"text"} (stop if missing) {"sleep":0.5}. '
             'Add "confirmed":true to one step only after the user said yes to exactly that step. '
             'It stops at the first step that fails or answers HOLD.')

TOOLS = [
    {"name": "phone_ui_elements",
     "description": "Read what's on screen as text: buttons, labels, fields, with their centre point in "
                    "screenshot pixels. Fast (well under a second with Pocket Pilot's fast control). "
                    "Use this first; use phone_screenshot only when you need to see pictures or layout.",
     "inputSchema": {"type": "object", "properties": {
         "filter": {"type": "string", "description": "only items whose text/description/id contains this"},
         "all": {"type": "boolean", "description": "include layout containers too"}}}},
    {"name": "phone_screenshot",
     "description": "See the phone's screen as an image. Slower than phone_ui_elements; use it for "
                    "pictures, games, maps or when the text list is empty." + COORD_NOTE,
     "inputSchema": {"type": "object", "properties": {
         "width": {"type": "integer", "description": "image width, default 540"}}}},
    {"name": "phone_tap_element",
     "description": "Tap something by its visible text, description or id." + AFTER_NOTE + CONFIRM_NOTE +
                    " If it answers HOLD, ask the user, then call again with confirmed=true only after they say yes.",
     "inputSchema": {"type": "object", "required": ["match"], "properties": {
         "match": {"type": "string"}, "index": {"type": "integer", "description": "which match, 0 = first"},
         "exact": {"type": "boolean"}, "long_press": {"type": "boolean"},
         "confirmed": {"type": "boolean", "description": "true only after the user said yes to this exact action"}}}},
    {"name": "phone_tap",
     "description": "Tap a point." + COORD_NOTE + AFTER_NOTE + CONFIRM_NOTE +
                    " If it answers HOLD, ask the user, then call again with confirmed=true only after they say yes.",
     "inputSchema": {"type": "object", "required": ["x", "y"], "properties": {
         "x": {"type": "number"}, "y": {"type": "number"},
         "long_press": {"type": "boolean"},
         "wait": {"type": "boolean", "description": "false = don't wait for the screen (fast taps, games)"},
         "confirmed": {"type": "boolean", "description": "true only after the user said yes to this exact action"}}}},
    {"name": "phone_do",
     "description": "Run several steps in one go, much faster than one tool call per step. " + STEP_HELP +
                    CONFIRM_NOTE,
     "inputSchema": {"type": "object", "required": ["steps"], "properties": {
         "steps": {"type": "array", "items": {"type": "object"}}}}},
    {"name": "phone_wait_for_change",
     "description": "Wait until the screen changes and settles (or until some text appears or disappears), "
                    "instead of fixed waits. Returns as soon as it happens, with what the screen shows.",
     "inputSchema": {"type": "object", "properties": {
         "timeout": {"type": "number", "description": "seconds, default 5, max 30"},
         "settle_ms": {"type": "integer", "description": "quiet time that counts as settled, default 300"},
         "until_text": {"type": "string"}, "until_gone": {"type": "string"}}}},
    {"name": "phone_type",
     "description": "Type text (any language and emoji with fast control). Types into the focused field, "
                    "or the field matching 'into'. Replaces what's there unless append=true. submit=true "
                    "presses Enter, which in chat apps sends, so ask the user first there." + CONFIRM_NOTE,
     "inputSchema": {"type": "object", "required": ["text"], "properties": {
         "text": {"type": "string"}, "into": {"type": "string", "description": "field hint, label or id"},
         "append": {"type": "boolean"}, "submit": {"type": "boolean"},
         "method": {"type": "string", "enum": ["auto", "set", "paste"],
                    "description": "auto (default) tries the best way for the app and checks the text stuck"},
         "confirmed": {"type": "boolean", "description": "true only after the user said yes to sending"}}}},
    {"name": "phone_open_app",
     "description": "Open an app by its name as shown on the phone (e.g. 'Spotify', 'Settings') or package name.",
     "inputSchema": {"type": "object", "required": ["app"], "properties": {"app": {"type": "string"}}}},
    {"name": "phone_open_screen",
     "description": "Jump straight to a settings screen, skipping the menus: " + ", ".join(sorted(SCREENS)) +
                    ", or app_info (with app). For other apps, phone_open_url with a deep link is fastest.",
     "inputSchema": {"type": "object", "required": ["screen"], "properties": {
         "screen": {"type": "string"}, "app": {"type": "string", "description": "for app_info"}}}},
    {"name": "phone_open_url",
     "description": "Open a web address or deep link straight in its app: https://..., tel:, mailto:, geo:, "
                    "spotify:, youtube links, maps search (geo:0,0?q=coffee), etc.",
     "inputSchema": {"type": "object", "required": ["url"], "properties": {"url": {"type": "string"}}}},
    {"name": "phone_key",
     "description": "Press: back, home, recents, notifications, quick_settings, lock, play_pause, next, "
                    "previous, volume_up, volume_down, mute, enter, delete, tab, space, search, escape, power, "
                    "wakeup, sleep. Enter in a chat app usually sends, so ask the user first there.",
     "inputSchema": {"type": "object", "required": ["key"], "properties": {
         "key": {"type": "string"}, "times": {"type": "integer"}}}},
    {"name": "phone_scroll",
     "description": "Scroll up, down, left or right (direction the content moves into view)." + AFTER_NOTE,
     "inputSchema": {"type": "object", "required": ["direction"], "properties": {
         "direction": {"type": "string", "enum": ["up", "down", "left", "right"]},
         "within": {"type": "string", "description": "scroll the list that matches this text"},
         "amount": {"type": "number", "description": "0.1 to 0.9 of the screen, default 0.5 (adb only)"}}}},
    {"name": "phone_swipe",
     "description": "Swipe from one point to another." + COORD_NOTE,
     "inputSchema": {"type": "object", "required": ["x1", "y1", "x2", "y2"], "properties": {
         "x1": {"type": "number"}, "y1": {"type": "number"}, "x2": {"type": "number"}, "y2": {"type": "number"},
         "ms": {"type": "integer", "description": "duration, default 300"},
         "wait": {"type": "boolean", "description": "false = don't wait for the screen"}}}},
    {"name": "phone_shortcut",
     "description": "The user's saved shortcuts: named lists of phone_do steps they can replay by voice "
                    "(\"run morning\"). action: list, run, save, delete. When the user says \"save that as X\", "
                    "save the steps you just used (never with confirmed). Running a shortcut still holds at "
                    "risky steps; after the user's yes, run again with from_step and confirmed=true.",
     "inputSchema": {"type": "object", "required": ["action"], "properties": {
         "action": {"type": "string", "enum": ["list", "run", "save", "delete"]},
         "name": {"type": "string"}, "steps": {"type": "array", "items": {"type": "object"}},
         "about": {"type": "string", "description": "one line on what it does"},
         "from_step": {"type": "integer", "description": "1-based step to start from"},
         "confirmed": {"type": "boolean", "description": "applies to the first step run, only after the user's yes"}}}},
    {"name": "phone_list_apps",
     "description": "List installed apps (names and package names), optionally filtered.",
     "inputSchema": {"type": "object", "properties": {"filter": {"type": "string"}}}},
    {"name": "phone_wait",
     "description": "Wait a fixed time (seconds, max 10). Prefer phone_wait_for_change.",
     "inputSchema": {"type": "object", "properties": {"seconds": {"type": "number"}}}},
    {"name": "phone_status",
     "description": "Which connection is live (fast control in the Pocket Pilot app, or adb), screen size, "
                    "current app.",
     "inputSchema": {"type": "object", "properties": {}}},
]

INSTRUCTIONS = (
    "These tools drive the user's own Android phone screen. Read the screen with phone_ui_elements "
    "(text, fast), act, and trust the screen summary that comes back with each action instead of "
    "looking again. Chain known steps with phone_do, jump with phone_open_screen or phone_open_url "
    "deep links, and use phone_wait_for_change instead of fixed waits. Never send a message, post, "
    "pay or buy, delete, or change a setting or permission without first asking the user in plain "
    "words and getting a clear yes. A tool answer starting with HOLD is the signal to ask. "
    "Don't read out private content unless asked."
)


def text(s):
    return [{"type": "text", "text": s}]


def current_app():
    try:
        if use_app():
            return APP.call("info").get("pkg", "")
        out = shell("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -2", timeout=10)
        m = re.search(r"u0 ([\w.]+)/", out)
        return m.group(1) if m else ""
    except Exception:
        return ""


def do_tap(dx, dy, long_press=False):
    if long_press:
        shell("input swipe %d %d %d %d 650" % (dx, dy, dx, dy))
    else:
        shell("input tap %d %d" % (dx, dy))
    SCREEN.last_dump_t = 0


def adb_signature():
    nodes = SCREEN.dump()
    return "|".join("%s/%s/%d" % (n["text"], n["desc"], n["checked"]) for n in nodes), nodes


def adb_summary(nodes):
    labels = [n["text"] or n["desc"] for n in nodes if n["text"] or n["desc"]][:14]
    pkg = nodes[0]["pkg"] if nodes else ""
    return pkg + ": " + " | ".join(l[:40] for l in labels)


def adb_wait_change(timeout, until_text="", until_gone="", baseline=None):
    start = time.time()
    if baseline is None:
        baseline, _ = adb_signature()
    while time.time() - start < timeout:
        sig, nodes = adb_signature()
        low = sig.lower()
        if until_text or until_gone:
            if (not until_text or until_text.lower() in low) and (not until_gone or until_gone.lower() not in low):
                return True, time.time() - start, adb_summary(nodes)
        elif sig != baseline:
            return True, time.time() - start, adb_summary(nodes)
        time.sleep(0.3)
    sig, nodes = adb_signature()
    return False, time.time() - start, adb_summary(nodes)


def resolve_app(q):
    """Return (pkg, label) for an app name, using the app's launcher list when we have it."""
    ql = q.strip().lower()
    if ql in APP_ALIASES:
        return APP_ALIASES[ql], q
    if "." in ql and " " not in ql:
        return q.strip(), q
    if use_app():
        apps = APP.call("apps").get("apps", [])
        exact = [a for a in apps if a["label"].lower() == ql]
        starts = [a for a in apps if a["label"].lower().startswith(ql)]
        inside = [a for a in apps if ql in a["label"].lower() or ql.replace(" ", "") in a["pkg"].lower()]
        for group in (exact, starts, inside):
            if group:
                a = sorted(group, key=lambda a: len(a["label"]))[0]
                return a["pkg"], a["label"]
        return None, None
    pkgs = [l.split(":", 1)[1] for l in shell("pm list packages").splitlines() if ":" in l]
    hits = [p for p in pkgs if ql.replace(" ", "") in p.lower()]
    return (sorted(hits, key=len)[0], q) if hits else (None, None)


def tool_call(name, a):
    app = use_app()

    if name == "phone_screenshot":
        if app:
            r = APP.call("screenshot", {"width": int(a.get("width") or SHOT_WIDTH), "quality": 70})
            if r.get("jpeg"):
                SCREEN._set_scale(r["screen_w"])
                return [{"type": "image", "data": r["jpeg"], "mimeType": "image/jpeg"},
                        {"type": "text", "text": "Screenshot %dx%d (phone %dx%d). App: %s" % (
                            r["w"], r["h"], r["screen_w"], r["screen_h"], r.get("pkg", ""))}]
            if not online_serial():
                return text(r.get("error", "Screenshot failed.") + " Use phone_ui_elements.")
        data, mime, w, h, (dw, dh) = SCREEN.screenshot(a.get("width"))
        return [{"type": "image", "data": base64.b64encode(data).decode(), "mimeType": mime},
                {"type": "text", "text": "Screenshot %dx%d (phone %dx%d). App: %s" % (w, h, dw, dh, current_app())}]

    if name == "phone_ui_elements":
        flt = (a.get("filter") or "").lower()
        rows = []
        if app:
            r = APP.call("tree", {"all": bool(a.get("all")), "limit": 400})
            pkg = r.get("pkg", "")
            for n in r.get("nodes", []):
                label = n.get("t") or n.get("d") or n.get("hint") or ""
                hay = (n.get("t", "") + " " + n.get("d", "") + " " + n.get("id", "") + " " + n.get("hint", "")).lower()
                if flt and flt not in hay:
                    continue
                x1, y1, x2, y2 = n["b"]
                cx, cy = SCREEN.to_shot((x1 + x2) / 2.0, (y1 + y2) / 2.0)
                rows.append("%d. %s%s [%s] at (%d,%d) %s" % (
                    len(rows), json.dumps(label[:80]) if label else "-",
                    (" id=" + n["id"]) if n.get("id") else "", n.get("c", ""), cx, cy, n.get("f", "")))
                if len(rows) >= 180:
                    rows.append("... more items; use filter")
                    break
            return text("App: %s\n%s" % (pkg, "\n".join(rows) or "Nothing readable on screen. Try phone_screenshot."))
        nodes = SCREEN.dump()
        for n in nodes:
            useful = n["text"] or n["desc"] or n["clickable"] or n["edit"] or n["scroll"] or n["checkable"]
            if not useful and not a.get("all"):
                continue
            hay = (n["text"] + " " + n["desc"] + " " + n["id"]).lower()
            if flt and flt not in hay:
                continue
            x1, y1, x2, y2 = n["bounds"]
            cx, cy = SCREEN.to_shot((x1 + x2) / 2.0, (y1 + y2) / 2.0)
            flags = [f for f, on in (("tap", n["clickable"]), ("long", n["long"]), ("scroll", n["scroll"]),
                                     ("field", n["edit"]), ("on", n["checked"]), ("focused", n["focused"]),
                                     ("disabled", not n["enabled"])) if on]
            label = n["text"] or n["desc"] or ""
            rid = n["id"].split("/")[-1] if n["id"] else ""
            rows.append("%d. %s%s [%s] at (%d,%d) %s" % (
                len(rows), json.dumps(label[:80]) if label else "-", (" id=" + rid) if rid else "",
                n["cls"], cx, cy, " ".join(flags)))
            if len(rows) >= 180:
                rows.append("... more items; use filter")
                break
        pkg = nodes[0]["pkg"] if nodes else ""
        return text("App: %s\n%s" % (pkg, "\n".join(rows) or "Nothing readable on screen. Try phone_screenshot."))

    if name == "phone_tap":
        if app:
            APP.ensure_scale()
        dx, dy = SCREEN.to_dev(a["x"], a["y"])
        if app:
            r = APP.call("tap", {"x": dx, "y": dy, "long": bool(a.get("long_press")), "guard_re": GUARD_RE if GUARD else "",
                                 "confirmed": bool(a.get("confirmed")), "wait": a.get("wait", True) is not False})
            if r.get("hold"):
                return text(HOLD_TEXT % r["hold"])
            return text(("Tapped (%s,%s)." % (a["x"], a["y"])) + after_text(r))
        hold = guard_check(dx, dy, a.get("confirmed"))
        if hold:
            return text(hold)
        do_tap(dx, dy, a.get("long_press"))
        return text("Tapped (%s,%s)." % (a["x"], a["y"]))

    if name == "phone_tap_element":
        if app:
            r = APP.call("click", {"match": a["match"], "exact": bool(a.get("exact")), "index": int(a.get("index") or 0),
                                   "long": bool(a.get("long_press")), "guard_re": GUARD_RE if GUARD else "",
                                   "confirmed": bool(a.get("confirmed"))})
            if r.get("hold"):
                return text(HOLD_TEXT % r["hold"])
            if r.get("error"):
                return text(r["error"] + ". Check phone_ui_elements.")
            more = " (%d other matches)" % (r["matches"] - 1) if r.get("matches", 1) > 1 else ""
            return text("Tapped \"%s\"%s." % (r.get("tapped", a["match"]).strip(), more) + after_text(r))
        nodes = SCREEN.dump()
        m = a["match"].lower()
        def hit(n):
            vals = [n["text"].lower(), n["desc"].lower(), n["id"].lower(), n["id"].split("/")[-1].lower()]
            return (m in vals) if a.get("exact") else any(m in v for v in vals if v)
        matches = [n for n in nodes if hit(n)]
        if not matches:
            return text("Nothing on screen matches \"%s\". Check phone_ui_elements." % a["match"])
        idx = int(a.get("index") or 0)
        if idx >= len(matches):
            return text("Only %d matches for \"%s\"." % (len(matches), a["match"]))
        n = matches[idx]
        x1, y1, x2, y2 = n["bounds"]
        dx, dy = (x1 + x2) // 2, (y1 + y2) // 2
        if GUARD and not a.get("confirmed"):
            label = SCREEN.label_at(dx, dy, nodes) or (n["text"] + " " + n["desc"])
            if risky(label):
                return text(HOLD_TEXT % label.strip())
        do_tap(dx, dy, a.get("long_press"))
        more = " (%d other matches)" % (len(matches) - 1) if len(matches) > 1 else ""
        return text("Tapped \"%s\"%s." % (n["text"] or n["desc"] or n["id"], more))

    if name == "phone_swipe":
        if app:
            APP.ensure_scale()
        x1, y1 = SCREEN.to_dev(a["x1"], a["y1"])
        x2, y2 = SCREEN.to_dev(a["x2"], a["y2"])
        if app:
            r = APP.call("swipe", {"x1": x1, "y1": y1, "x2": x2, "y2": y2, "ms": int(a.get("ms") or 300),
                                   "wait": a.get("wait", True) is not False})
            return text("Swiped." + after_text(r))
        shell("input swipe %d %d %d %d %d" % (x1, y1, x2, y2, int(a.get("ms") or 300)))
        SCREEN.last_dump_t = 0
        return text("Swiped.")

    if name == "phone_scroll":
        d = a["direction"]
        if app:
            r = APP.call("scroll", {"dir": d, "match": a.get("within") or ""})
            return text("Scrolled %s." % d + after_text(r))
        SCREEN.ensure_scale()
        out = shell("wm size")
        m = re.findall(r"(\d+)x(\d+)", out)
        w, h = (int(m[-1][0]), int(m[-1][1])) if m else (1080, 2400)
        if SCREEN.screen_w and SCREEN.screen_w > w:   # landscape
            w, h = h, w
        amt = min(0.9, max(0.1, float(a.get("amount") or 0.5)))
        cx, cy = w // 2, h // 2
        if d == "down":
            p = (cx, int(cy + h * amt / 2), cx, int(cy - h * amt / 2))
        elif d == "up":
            p = (cx, int(cy - h * amt / 2), cx, int(cy + h * amt / 2))
        elif d == "right":
            p = (int(cx + w * amt / 2), cy, int(cx - w * amt / 2), cy)
        else:
            p = (int(cx - w * amt / 2), cy, int(cx + w * amt / 2), cy)
        shell("input swipe %d %d %d %d 350" % p)
        SCREEN.last_dump_t = 0
        return text("Scrolled %s." % d)

    if name == "phone_type":
        s = a["text"]
        if app:
            r = APP.call("type", {"text": s, "match": a.get("into") or "", "append": bool(a.get("append")),
                                  "submit": bool(a.get("submit")), "confirmed": bool(a.get("confirmed")),
                                  "method": a.get("method") or "auto",
                                  "submit_guard_re": SUBMIT_GUARD_RE if GUARD else ""})
            if r.get("error"):
                return text(r["error"])
            if r.get("verified") is False:
                # the app's editor didn't keep it (seen in Google Keep's note body): type it key by key
                if online_serial() and all(ord(c) <= 126 for c in s):
                    if not a.get("append") and r.get("seen"):
                        shell("input keycombination 113 29 || input keyevent 123")   # select all (or go to end)
                        shell("input keyevent 67")
                    for chunk in [s[i:i + 80] for i in range(0, len(s), 80)]:
                        shell("input text %s" % shlex.quote(text_for_input(chunk)))
                    time.sleep(0.4)
                    chk = APP.call("tree", {})
                    got = any(s.strip()[:40].lower() in (n.get("t") or "").lower()
                              for n in chk.get("nodes", []) if isinstance(n, dict))
                    return text(("Typed %d characters key by key (this field ignores quick typing)." % len(s))
                                + ("" if got else " I couldn't read it back, so take a look with phone_ui_elements."))
                return text("The field didn't keep the text (tried %s; it now shows: \"%s\"). Tap inside the "
                            "field with phone_tap and type again, or turn on Wireless debugging so I can type "
                            "it key by key." % ("/".join(r.get("tried", [])), (r.get("seen") or "")[:80]))
            if r.get("hold"):
                return text("Typed it, but didn't press Enter. " + HOLD_TEXT % r["hold"])
            msg = "Typed %d characters." % r.get("chars", len(s))
            if a.get("submit"):
                msg += " Pressed Enter." if r.get("submitted") else " " + r.get("note", "")
            return text(msg + after_text(r))
        if a.get("into"):
            tool_call("phone_tap_element", {"match": a["into"]})
        if any(ord(c) > 126 for c in s):
            s2 = "".join(c for c in s if ord(c) <= 126)
            note = " Emoji and accented letters were skipped (turn on fast control in Pocket Pilot to type them)."
        else:
            s2, note = s, ""
        for chunk in [s2[i:i + 80] for i in range(0, len(s2), 80)]:
            shell("input text %s" % shlex.quote(text_for_input(chunk)))
        if a.get("submit"):
            pkg = current_app()
            if GUARD and not a.get("confirmed") and re.search(SUBMIT_GUARD_RE, pkg, re.I):
                return text("Typed it, but didn't press Enter. " + HOLD_TEXT % ("Enter in " + pkg))
            shell("input keyevent 66")
        return text("Typed %d characters.%s" % (len(s2), note))

    if name == "phone_key":
        k = a["key"].lower().strip().replace(" ", "_")
        times = max(1, min(20, int(a.get("times") or 1)))
        if app and (k in GLOBAL_KEYS or k in MEDIA_KEYS):
            r = {}
            for _ in range(times):
                if k in GLOBAL_KEYS:
                    r = APP.call("global", {"action": "recents" if k == "app_switch" else k, "wait": _ == times - 1})
                else:
                    r = APP.call("media", {"key": k})
            return text("Pressed %s." % k + after_text(r))
        code = KEYS.get(k)
        if code is None and k.isdigit():
            code = int(k)
        if code is None:
            return text("Unknown key %s. Try: %s" % (k, ", ".join(sorted(KEYS))))
        if app and not online_serial():
            if k == "enter":
                return text("Use phone_type with submit=true to press Enter.")
            return text("The %s key needs adb (phonectl connect). Fast control can do: %s." % (
                k, ", ".join(sorted(GLOBAL_KEYS | MEDIA_KEYS))))
        for _ in range(times):
            shell("input keyevent %d" % code)
        SCREEN.last_dump_t = 0
        return text("Pressed %s." % k)

    if name == "phone_open_app":
        pkg, label = resolve_app(a["app"])
        if not pkg:
            return text("No app matching \"%s\". Try phone_list_apps." % a["app"])
        if app:
            r = APP.call("launch", {"pkg": pkg})
            if r.get("error"):
                return text(r["error"])
            return text("Opened %s." % label + after_text(r))
        out = shell("monkey -p %s -c android.intent.category.LAUNCHER 1 2>&1" % shlex.quote(pkg))
        if "No activities found" in out or "monkey aborted" in out:
            return text("Couldn't open %s." % pkg)
        SCREEN.last_dump_t = 0
        return text("Opened %s." % pkg)

    if name == "phone_open_screen":
        sc = a["screen"].strip().lower().replace(" ", "_").replace("-", "_")
        sc = {"wi_fi": "wifi", "dnd": "do_not_disturb", "power": "battery", "app": "app_info"}.get(sc, sc)
        if sc == "app_info":
            pkg, label = resolve_app(a.get("app") or "")
            if not pkg:
                return text("Which app? Pass app.")
            action, data = "android.settings.APPLICATION_DETAILS_SETTINGS", "package:" + pkg
        elif sc in SCREENS:
            action, data = SCREENS[sc], None
        else:
            return text("Unknown screen. Choose from: %s, app_info." % ", ".join(sorted(SCREENS)))
        if app:
            args = {"action": action}
            if data:
                args["data"] = data
            r = APP.call("intent", args)
            if r.get("error"):
                return text(r["error"])
            return text("Opened %s." % sc + after_text(r))
        cmd = "am start -a %s" % action + (" -d %s" % shlex.quote(data) if data else "")
        out = shell(cmd + " 2>&1")
        SCREEN.last_dump_t = 0
        return text("Opened %s." % sc if "Error" not in out else out.strip())

    if name == "phone_open_url":
        u = a["url"]
        if app:
            r = APP.call("intent", {"action": "android.intent.action.VIEW", "data": u})
            if r.get("error"):
                return text(r["error"])
            return text("Opened %s." % u + after_text(r))
        out = shell("am start -a android.intent.action.VIEW -d %s 2>&1" % shlex.quote(u))
        SCREEN.last_dump_t = 0
        return text("Opened %s." % u if "Error" not in out else out.strip())

    if name == "phone_wait_for_change":
        timeout = min(30.0, max(0.2, float(a.get("timeout") or 5)))
        ut, ug = a.get("until_text") or "", a.get("until_gone") or ""
        if app:
            r = APP.call("wait_change", {"timeout_ms": int(timeout * 1000), "settle_ms": int(a.get("settle_ms") or 300),
                                         "until_text": ut, "until_gone": ug}, timeout=timeout + 10)
            changed, waited, summ = r.get("changed"), r.get("waited_ms", 0) / 1000.0, r.get("summary", "")
        else:
            changed, waited, summ = adb_wait_change(timeout, ut, ug)
        if changed:
            return text("Changed after %.1fs. Screen: %s" % (waited, summ))
        return text("No change in %.1fs. Screen: %s" % (waited, summ))

    if name == "phone_do":
        return text(run_steps(a.get("steps") or []))

    if name == "phone_shortcut":
        return text(shortcut(a))

    if name == "phone_list_apps":
        f = (a.get("filter") or "").lower()
        if app:
            apps = sorted(APP.call("apps").get("apps", []), key=lambda x: x["label"].lower())
            rows = ["%s (%s)" % (x["label"], x["pkg"]) for x in apps
                    if not f or f in x["label"].lower() or f in x["pkg"].lower()]
            return text("\n".join(rows[:300]) or "No match.")
        pkgs = sorted(l.split(":", 1)[1] for l in shell("pm list packages -3").splitlines() if ":" in l)
        if f:
            pkgs = [p for p in pkgs if f in p.lower()]
        return text("\n".join(pkgs[:300]) or "No match.")

    if name == "phone_wait":
        time.sleep(min(10.0, max(0.1, float(a.get("seconds") or 1))))
        return text("Waited.")

    if name == "phone_status":
        parts = []
        if app:
            info = APP.call("info")
            parts.append("Fast control (Pocket Pilot app) is linked. Screen %sx%s, Android SDK %s, current app %s." % (
                info.get("w"), info.get("h"), info.get("sdk"), info.get("pkg")))
        s = online_serial() or (None if app else connect(quiet=True))
        if s:
            parts.append("adb connected via %s (%s)." % (s, shell("getprop ro.product.model").strip()))
        elif not app:
            return text("Not connected. Ask the user to turn on fast control in Pocket Pilot, or turn on "
                        "Wireless debugging and run phonectl connect.")
        else:
            parts.append("adb not connected (only needed for a few keys).")
        return text(" ".join(parts))

    raise RuntimeError("Unknown tool %s" % name)


# --------------------------------------------------------------------------- multi-step runs and shortcuts

def step_to_call(st):
    """Turn one phone_do step into (tool name, args, short description)."""
    c = bool(st.get("confirmed"))
    if "open_app" in st:
        return "phone_open_app", {"app": st["open_app"]}, "open " + str(st["open_app"])
    if "open_url" in st:
        return "phone_open_url", {"url": st["open_url"]}, "open link"
    if "open_screen" in st:
        return "phone_open_screen", {"screen": st["open_screen"], "app": st.get("app", "")}, "open " + str(st["open_screen"])
    for key, lp in (("tap", False), ("long_press", True)):
        if key in st:
            v = st[key]
            if isinstance(v, list) and len(v) == 2:
                return "phone_tap", {"x": v[0], "y": v[1], "long_press": lp, "confirmed": c}, "%s %s" % (key, v)
            return ("phone_tap_element", {"match": str(v), "index": int(st.get("index") or 0), "exact": bool(st.get("exact")),
                                          "long_press": lp, "confirmed": c}, "%s \"%s\"" % (key, v))
    if "type" in st:
        return ("phone_type", {"text": str(st["type"]), "into": st.get("into") or "", "append": bool(st.get("append")),
                               "submit": bool(st.get("submit")), "confirmed": c}, "type")
    if "key" in st:
        return "phone_key", {"key": str(st["key"]), "times": int(st.get("times") or 1)}, "key " + str(st["key"])
    if "scroll" in st:
        return "phone_scroll", {"direction": str(st["scroll"]), "within": st.get("within") or ""}, "scroll " + str(st["scroll"])
    if "swipe" in st:
        v = st["swipe"]
        return "phone_swipe", {"x1": v[0], "y1": v[1], "x2": v[2], "y2": v[3], "ms": int(st.get("ms") or 300)}, "swipe"
    if "wait_for" in st:
        return "phone_wait_for_change", {"until_text": str(st["wait_for"]), "timeout": st.get("timeout") or 8}, "wait for \"%s\"" % st["wait_for"]
    if "wait_gone" in st:
        return "phone_wait_for_change", {"until_gone": str(st["wait_gone"]), "timeout": st.get("timeout") or 8}, "wait for \"%s\" to go" % st["wait_gone"]
    if "wait_change" in st:
        t = st["wait_change"] if isinstance(st["wait_change"], (int, float)) and not isinstance(st["wait_change"], bool) else 5
        return "phone_wait_for_change", {"timeout": t}, "wait for change"
    if "sleep" in st:
        return "phone_wait", {"seconds": st["sleep"]}, "pause"
    raise ValueError("Unknown step %s" % json.dumps(st)[:80])


def screen_has(txt):
    t = txt.lower()
    if use_app():
        r = APP.call("tree", {"limit": 600})
        return any(t in (n.get("t", "") + " " + n.get("d", "")).lower() for n in r.get("nodes", []))
    return any(t in (n["text"] + " " + n["desc"]).lower() for n in SCREEN.dump())


def run_steps(steps, start=1, label="Step"):
    """Run steps in order; stop at the first HOLD or failure. Returns a short report."""
    if not isinstance(steps, list) or not steps:
        return "No steps given. " + STEP_HELP
    lines, last = [], ""
    t0 = time.time()
    for i, st in enumerate(steps, start):
        if not isinstance(st, dict):
            return "\n".join(lines + ["%s %d isn't a step object. Stopped." % (label, i)])
        if "expect" in st:
            ok = screen_has(str(st["expect"]))
            if not ok:
                lines.append("%s %d: \"%s\" isn't on screen. Stopped here." % (label, i, st["expect"]))
                return "\n".join(lines)
            lines.append("%s %d: saw \"%s\"." % (label, i, st["expect"]))
            continue
        try:
            name, args, desc = step_to_call(st)
            out = tool_call(name, args)[0]["text"]
        except Exception as e:
            lines.append("%s %d (%s) failed: %s. Stopped here." % (label, i, json.dumps(st)[:60], e))
            return "\n".join(lines)
        first = out.split(" Screen ")[0]
        if out.startswith("HOLD") or "HOLD:" in out:
            lines.append("%s %d (%s) is on HOLD. %s" % (label, i, desc, out[out.find("HOLD"):]))
            lines.append("Steps before it are done. After the user's yes, run from step %d with confirmed=true on it." % i)
            return "\n".join(lines)
        waiting_for = name == "phone_wait_for_change" and (args.get("until_text") or args.get("until_gone"))
        if re.search(r"^(Nothing on screen|No app|No text field|Unknown|Couldn't|Only \d+ matches|The \w+ key needs)", out) \
                or (waiting_for and out.startswith("No change")):
            lines.append("%s %d (%s): %s Stopped here." % (label, i, desc, out))
            return "\n".join(lines)
        lines.append("%s %d: %s" % (label, i, first))
        last = out
    summ = ""
    m = re.search(r"Screen (changed \([\d.]+s\)|looks the same|): ?(.*)$", last)
    if m:
        summ = " Screen now: " + m.group(2)
    elif "Screen:" in last:
        summ = " Screen now:" + last.split("Screen:", 1)[1]
    lines.append("All %d steps done in %.1fs.%s" % (len(steps), time.time() - t0, summ))
    return "\n".join(lines)


def load_shortcuts():
    try:
        with open(SHORTCUTS_FILE) as f:
            return json.load(f)
    except Exception:
        return {}


def save_shortcuts(d):
    os.makedirs(CONF_DIR, exist_ok=True)
    tmp = SHORTCUTS_FILE + ".tmp"
    with open(tmp, "w") as f:
        json.dump(d, f, indent=1, ensure_ascii=False)
    os.replace(tmp, SHORTCUTS_FILE)


def norm_name(s):
    return re.sub(r"[^a-z0-9 ]", "", (s or "").lower()).strip()


def find_shortcut(name):
    d = load_shortcuts()
    n = norm_name(name)
    if n in d:
        return n, d[n]
    for k in d:
        if n and (n == k or n in k.split() or k in n):
            return k, d[k]
    return None, None


def shortcut(a, from_voice=False):
    act = a.get("action")
    d = load_shortcuts()
    if act == "list":
        if not d:
            return "No shortcuts saved yet. Do something once, then say \"save that as\" a name."
        return "\n".join("%s: %s (%d steps)" % (k, v.get("about") or "", len(v.get("steps", []))) for k, v in sorted(d.items()))
    if act == "save":
        n = norm_name(a.get("name"))
        steps = a.get("steps") or []
        if not n or not steps:
            return "Give a name and the steps."
        clean = []
        for st in steps:
            if not isinstance(st, dict):
                continue
            st = {k: v for k, v in st.items() if k != "confirmed"}   # a yes never gets saved
            step_to_call(st) if "expect" not in st else None      # validates
            clean.append(st)
        d[n] = {"steps": clean, "about": a.get("about") or "", "saved": int(time.time())}
        save_shortcuts(d)
        return "Saved shortcut \"%s\" with %d steps. Say \"run %s\" any time." % (n, len(clean), n)
    if act == "delete":
        k, _ = find_shortcut(a.get("name"))
        if not k:
            return "No shortcut called \"%s\"." % a.get("name")
        d.pop(k, None)
        save_shortcuts(d)
        return "Deleted shortcut \"%s\"." % k
    if act == "run":
        k, sc = find_shortcut(a.get("name"))
        if not k:
            return "No shortcut called \"%s\". Saved ones: %s" % (a.get("name"), ", ".join(sorted(d)) or "none")
        steps = [dict(s) for s in sc.get("steps", [])]
        frm = max(1, int(a.get("from_step") or 1))
        steps = steps[frm - 1:]
        if steps and a.get("confirmed"):
            steps[0]["confirmed"] = True
        return ("Shortcut \"%s\":\n" % k) + run_steps(steps, start=frm)
    return "action must be list, run, save or delete."


# --------------------------------------------------------------------------- MCP stdio server

def serve():
    out_lock = threading.Lock()

    def send(msg):
        with out_lock:
            sys.stdout.write(json.dumps(msg) + "\n")
            sys.stdout.flush()

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            msg = json.loads(line)
        except Exception:
            continue
        mid, method = msg.get("id"), msg.get("method")
        params = msg.get("params") or {}
        if mid is None:
            continue  # notification
        try:
            if method == "initialize":
                send({"jsonrpc": "2.0", "id": mid, "result": {
                    "protocolVersion": params.get("protocolVersion", "2025-06-18"),
                    "capabilities": {"tools": {}},
                    "serverInfo": {"name": "phone", "version": VERSION},
                    "instructions": INSTRUCTIONS}})
            elif method == "tools/list":
                send({"jsonrpc": "2.0", "id": mid, "result": {"tools": TOOLS}})
            elif method == "tools/call":
                t0 = time.time()
                tname = params.get("name")
                try:
                    try:
                        content = tool_call(tname, params.get("arguments") or {})
                    except AppDown:
                        # the app link dropped mid-call: retry once over adb if we have it
                        APP.state, APP.state_t = False, time.time()
                        if BACKEND == "app" or not online_serial():
                            raise RuntimeError("Lost the link to Pocket Pilot's fast control. Check that the app "
                                               "is open and fast control is on, then try again.")
                        content = tool_call(tname, params.get("arguments") or {})
                    send({"jsonrpc": "2.0", "id": mid, "result": {"content": content}})
                    ok = True
                except Exception as e:
                    send({"jsonrpc": "2.0", "id": mid, "result": {"content": text(str(e)), "isError": True}})
                    ok = False
                log_timing({"kind": "tool", "t": int(t0), "tool": tname, "ok": ok,
                            "ms": int((time.time() - t0) * 1000), "via": "app" if APP.state else "adb"})
            elif method == "ping":
                send({"jsonrpc": "2.0", "id": mid, "result": {}})
            elif method in ("resources/list", "prompts/list"):
                key = method.split("/")[0]
                send({"jsonrpc": "2.0", "id": mid, "result": {key: []}})
            else:
                send({"jsonrpc": "2.0", "id": mid, "error": {"code": -32601, "message": "unknown method"}})
        except Exception as e:
            send({"jsonrpc": "2.0", "id": mid, "error": {"code": -32603, "message": str(e)}})


def main():
    args = sys.argv[1:]
    cmd = args[0] if args else "help"
    rest = args[1:]
    if cmd == "serve":
        return serve()
    table = {"pair": cmd_pair, "connect": cmd_connect, "pin": cmd_pin,
             "status": cmd_status, "doctor": cmd_doctor,
             "do": lambda r: print(run_steps(json.loads(" ".join(r) or "[]"))),
             "shortcuts": lambda r: print(shortcut({"action": "list"}))}
    if cmd in table:
        try:
            return table[cmd](rest) or 0
        except RuntimeError as e:
            print(e)
            return 1
        except KeyboardInterrupt:
            return 130
    print(__doc__)
    return 0


if __name__ == "__main__":
    sys.exit(main())
