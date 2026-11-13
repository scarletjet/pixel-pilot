#!/usr/bin/env python3
"""voice_bridge: the small local server between the Pocket Pilot app and Claude Code.

It does three jobs:
  1. Voice: the bubble sends what you said into ONE shared Claude Code conversation (shared mode,
     the default): an interactive session in tmux that the terminal (pilot chat) and the Claude app
     (Remote Control) also use. Speech is typed into it; replies are read from its transcript as
     they're written and spoken. PILOT_MODE=headless brings back the v2 private voice session.
  2. Fast control broker: the app's accessibility service long-polls /agent/next for commands;
     the phone tools (phonectl) send commands through /agent/call. No adb needed.
  3. Shortcuts: "run <name>" replays saved steps straight away, without waiting for Claude.

  python3 voice_bridge.py            start (port 8765, token from config)
  python3 voice_bridge.py --token    print the token to type into the app

Endpoints (all need header X-Token):
  GET  /health                       {"ok":true,"phone":bool,"agent":bool}
  POST /ask   {"text","heard_at"}    {"job": "..."}
  GET  /job?id=...                   {"state","progress":[...],"say":[...],"reply","timing":{...}}
  POST /stop  {"job": "..."}         stop a running job
  POST /reset                        start a fresh conversation
  GET  /history                      last exchanges, for the bubble's transcript
  GET  /timing                       last turns' timing
  GET  /agent/next                   (app) wait up to 25 s for a command, 204 if none
  POST /agent/result {"id","result"} (app) answer a command
  POST /agent/call {"cmd","args"}    (phonectl) run a command on the phone and wait for it
  GET  /agent/status                 is the app linked
"""

import collections
import json
import os
import re
import secrets
import select
import shutil
import socket
import string
import subprocess
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

HERE = os.path.dirname(os.path.abspath(__file__))
CONF_DIR = os.path.expanduser("~/.config/phone-control")
BRIDGE_CONF = os.path.join(CONF_DIR, "bridge.json")
WORKDIR = os.path.expanduser("~/phone-control/workdir")
MCP_CONF = os.path.join(CONF_DIR, "mcp.json")
TIMING_LOG = os.path.expanduser("~/phone-control/timing.log")
CLAUDE_ERR = os.path.expanduser("~/phone-control/claude.err")
PORT = int(os.environ.get("PILOT_PORT", "8765"))
IDLE_RESET_S = 20 * 60
PERSISTENT = os.environ.get("PILOT_PERSISTENT", "1") != "0"
AGENT_POLL_S = 25

VOICE_PROMPT = """You are talking to the user through a floating voice bubble on their Android phone.
They speak, you answer out loud, so:
- Reply in one to three short spoken sentences. No markdown, lists, code, links or emoji.
- When a task takes several steps, first say in under eight words what you're doing, then act.
- You drive their phone with the phone_* tools. Read the screen with phone_ui_elements (fast text).
  Every action answers with what the screen shows once it settles, so don't look again unless
  something is unclear. Chain steps you're sure of with phone_do. Jump straight to places with
  phone_open_screen (settings pages) and phone_open_url (deep links). Use phone_wait_for_change,
  never fixed waits. Use phone_screenshot only for pictures, maps or games.
- Before anything that sends a message, posts, pays or buys, deletes, or changes a setting or
  permission, stop and ask them in one short question, like "Should I send it to Sam?". Do it only
  after they say yes in their next turn. A tool answer starting with HOLD means you must ask.
- If they say "save that as <name>", save the steps you just used with phone_shortcut (never with
  confirmed), and tell them they can say "run <name>" next time.
- When you finish, say what you did in one sentence. If you got stuck, say where.
- Don't read private messages or notifications aloud unless they asked.
- The voice app's own floating dot and caption text are not part of the screen; ignore them."""

YES = {"yes", "yeah", "yep", "yup", "sure", "ok", "okay", "go ahead", "do it", "confirm", "yes please",
       "go", "please do", "send it", "yes do it", "correct"}
NO = {"no", "nope", "cancel", "stop", "dont", "don't", "no thanks", "never mind", "wait"}


def load_bridge_conf():
    try:
        with open(BRIDGE_CONF) as f:
            return json.load(f)
    except Exception:
        return {}


def token():
    conf = load_bridge_conf()
    if not conf.get("token"):
        alphabet = string.ascii_lowercase + string.digits
        conf["token"] = "".join(secrets.choice(alphabet) for _ in range(16))
        os.makedirs(CONF_DIR, exist_ok=True)
        with open(BRIDGE_CONF, "w") as f:
            json.dump(conf, f)
        os.chmod(BRIDGE_CONF, 0o600)
    return conf["token"]


def write_mcp_conf():
    os.makedirs(CONF_DIR, exist_ok=True)
    cfg = {"mcpServers": {"phone": {"command": "python3",
                                    "args": [os.path.join(HERE, "phonectl.py"), "serve"]}}}
    with open(MCP_CONF, "w") as f:
        json.dump(cfg, f)


def claude_bin():
    for c in [shutil.which("claude"), os.path.expanduser("~/.local/bin/claude")]:
        if c and os.path.exists(c):
            return c
    return "claude"


def nice_tool(name):
    n = name.replace("mcp__phone__phone_", "").replace("mcp__phone__", "")
    return {"screenshot": "Looking at the screen", "ui_elements": "Reading the screen",
            "tap": "Tapping", "tap_element": "Tapping", "swipe": "Swiping", "scroll": "Scrolling",
            "type": "Typing", "key": "Pressing a key", "open_app": "Opening an app",
            "open_url": "Opening a link", "open_screen": "Opening settings", "wait": "Waiting",
            "wait_for_change": "Waiting for the screen", "do": "Doing a few steps",
            "shortcut": "Using a shortcut", "list_apps": "Checking your apps",
            "status": "Checking the connection", "WebSearch": "Searching the web"}.get(n, n)


def log_timing(entry):
    try:
        os.makedirs(os.path.dirname(TIMING_LOG), exist_ok=True)
        with open(TIMING_LOG, "a") as f:
            f.write(json.dumps(entry) + "\n")
    except Exception:
        pass


# --------------------------------------------------------------------------- fast control broker

class Agent:
    def __init__(self):
        self.cond = threading.Condition()
        self.queue = collections.deque()
        self.results = {}
        self.last_poll = 0.0
        self.polling = 0

    def connected(self):
        # a poll is open right now, or the app is between two polls
        return self.polling > 0 or time.time() - self.last_poll < 5

    def next(self, alive):
        """Hand the next command to the app. alive() says whether the app's connection is still open."""
        deadline = time.time() + AGENT_POLL_S
        with self.cond:
            self.polling += 1
            self.last_poll = time.time()
            try:
                while not self.queue:
                    left = deadline - time.time()
                    if left <= 0:
                        return None
                    self.cond.wait(min(1.0, left))
                    if not alive():
                        return None
                return self.queue.popleft()
            finally:
                self.polling -= 1
                self.last_poll = time.time()

    def requeue(self, item):
        with self.cond:
            if item["id"] in self.results:
                self.queue.appendleft(item)
                self.cond.notify_all()

    def result(self, cid, res):
        with self.cond:
            if cid in self.results:
                self.results[cid] = res
                self.cond.notify_all()

    def call(self, cmd, args, timeout):
        if not self.connected():
            return None, "offline"
        cid = uuid.uuid4().hex[:10]
        with self.cond:
            self.results[cid] = None
            self.queue.append({"id": cid, "cmd": cmd, "args": args})
            self.cond.notify_all()
            start = time.time()
            deadline = start + timeout
            while self.results.get(cid) is None:
                waiting = any(item["id"] == cid for item in self.queue)
                left = deadline - time.time()
                if waiting and time.time() - start > 3:
                    # nobody picked it up: the app isn't really there
                    self.results.pop(cid, None)
                    for item in list(self.queue):
                        if item["id"] == cid:
                            self.queue.remove(item)
                    return None, "offline"
                if left <= 0:
                    self.results.pop(cid, None)
                    return None, "timeout"
                self.cond.wait(min(0.5, left))
            return self.results.pop(cid), None


AGENT = Agent()


# --------------------------------------------------------------------------- Claude, kept warm

class Claude:
    """One Claude Code process that stays running between questions (stream-json in and out)."""

    def __init__(self):
        self.lock = threading.Lock()
        self.proc = None
        self.session = None
        self.last_used = 0.0
        self.persistent = PERSISTENT

    def base_cmd(self):
        cmd = [claude_bin(), "-p", "--output-format", "stream-json", "--verbose",
               "--append-system-prompt", VOICE_PROMPT,
               "--mcp-config", MCP_CONF, "--strict-mcp-config",
               "--allowedTools", "mcp__phone", "WebSearch"]
        if os.environ.get("PILOT_MODEL"):
            cmd += ["--model", os.environ["PILOT_MODEL"]]
        return cmd

    def _err(self):
        os.makedirs(os.path.dirname(CLAUDE_ERR), exist_ok=True)
        return open(CLAUDE_ERR, "a")

    def warm(self):
        """Start the process ahead of time, so the next question skips the start-up."""
        if not self.persistent:
            return
        with self.lock:
            if self.proc and self.proc.poll() is None:
                return
            cmd = self.base_cmd() + ["--input-format", "stream-json"]
            if self.session:
                cmd += ["--resume", self.session]
            os.makedirs(WORKDIR, exist_ok=True)
            try:
                self.proc = subprocess.Popen(cmd, cwd=WORKDIR, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                             stderr=self._err(), text=True, bufsize=1)
            except Exception:
                self.proc = None

    def kill(self):
        p = self.proc
        self.proc = None
        if p and p.poll() is None:
            try:
                p.terminate()
                p.wait(timeout=3)
            except Exception:
                try:
                    p.kill()
                except Exception:
                    pass

    def reset(self):
        self.kill()
        self.session = None

    def turn(self, text_in, on_event):
        """Run one turn. Calls on_event(ev) for every stream event. Returns the result event or None."""
        if time.time() - self.last_used > IDLE_RESET_S and self.last_used:
            self.reset()
        self.last_used = time.time()
        if self.persistent:
            self.warm()
            p = self.proc
            if p is not None:
                try:
                    p.stdin.write(json.dumps({"type": "user", "message": {"role": "user", "content": text_in}}) + "\n")
                    p.stdin.flush()
                    res = self._read(p, on_event)
                    if res is not None:
                        return res
                except (BrokenPipeError, OSError, ValueError):
                    pass
                if p.poll() is not None and not getattr(p, "_saw_event", False):
                    # this claude doesn't take stream input; use one process per turn from now on
                    self.persistent = False
                self.proc = None
                if getattr(p, "_stopped", False):
                    return None
        # one process per turn
        cmd = self.base_cmd()
        cmd.insert(2, text_in)
        if self.session:
            cmd += ["--resume", self.session]
        os.makedirs(WORKDIR, exist_ok=True)
        p = subprocess.Popen(cmd, cwd=WORKDIR, stdout=subprocess.PIPE, stderr=self._err(), text=True, bufsize=1)
        self.proc_once = p
        res = self._read(p, on_event)
        p.wait()
        return res

    def _read(self, p, on_event):
        for line in p.stdout:
            try:
                ev = json.loads(line)
            except Exception:
                continue
            p._saw_event = True
            if ev.get("session_id"):
                self.session = ev["session_id"]
            on_event(ev)
            if ev.get("type") == "result":
                return ev
        return None

    def stop(self):
        for p in (self.proc, getattr(self, "proc_once", None)):
            if p and p.poll() is None:
                p._stopped = True
                try:
                    p.terminate()
                except Exception:
                    pass
        self.proc = None


# --------------------------------------------------------------------------- one shared conversation (v3)

SHARED_DIR = os.path.expanduser(os.environ.get("PILOT_SHARED_DIR", "~/pocket-pilot"))
SHARED_TMUX = "pilot-claude"
SHARED_PROMPT_FILE = os.path.join(CONF_DIR, "shared-prompt.txt")
VOICE_TAG = "[voice]"
END_REASONS = {"end_turn", "stop_sequence", "max_tokens", "refusal"}

SHARED_PROMPT = ("This one Claude Code conversation is shared live by three places: the Linux Terminal, "
                 "the Claude app (Remote Control), and the Pocket Pilot voice bubble. A message that starts "
                 "with " + VOICE_TAG + " was spoken into the bubble, and your reply to it is read out loud, "
                 "so for those messages follow these rules:\n" + VOICE_PROMPT +
                 "\nMessages without " + VOICE_TAG + " were typed in the terminal or the Claude app: answer "
                 "those normally. The safety rule above (ask first before sending, posting, paying, deleting "
                 "or changing settings) applies to every message, typed or spoken.")


def tmux(*args, timeout=5):
    return subprocess.run(["tmux"] + list(args), capture_output=True, text=True, timeout=timeout)


def slug(path):
    return re.sub(r"[^A-Za-z0-9]", "-", path)


class SharedClaude:
    """The bubble joins one interactive Claude Code session running in tmux.

    Speech goes in as typed input (tmux send-keys), so the terminal and the Claude app's Remote
    Control see every bubble turn as it happens. Replies are read from the session's own transcript
    file as Claude writes it, for captions and speech.
    """

    shared = True

    def __init__(self):
        self.last_used = time.time()
        self.session = None
        self.persistent = True
        self.reset_at = 0

    # --- session --------------------------------------------------------------
    def write_prompt(self):
        os.makedirs(CONF_DIR, exist_ok=True)
        with open(SHARED_PROMPT_FILE, "w") as f:
            f.write(SHARED_PROMPT)

    def running(self):
        try:
            return tmux("has-session", "-t", SHARED_TMUX).returncode == 0
        except Exception:
            return False

    def warm(self):
        if self.running():
            return
        self.write_prompt()
        os.makedirs(SHARED_DIR, exist_ok=True)
        pilot = os.path.join(HERE, "pilot")
        tmux("new-session", "-d", "-s", SHARED_TMUX, "-x", "120", "-y", "40", "-c", SHARED_DIR, pilot + " _claude")

    def pane(self):
        try:
            return tmux("capture-pane", "-p", "-t", SHARED_TMUX).stdout
        except Exception:
            return ""

    def blocker(self):
        """Something on the terminal screen only a person can answer."""
        lines = [l for l in self.pane().splitlines() if l.strip()]
        low = "\n".join(lines[-14:]).lower()     # only what's on screen now, near the bottom
        if "trust the files" in low or "trust this folder" in low:
            return ("Claude wants you to approve its folder once. Open Terminal, run pilot chat, "
                    "and choose yes.")
        if "select login method" in low or "please run /login" in low or "not logged in" in low:
            return "Claude needs you to sign in. Open Terminal, run pilot chat, and sign in."
        if re.search(r"do you want to (proceed|make this edit|create|allow)", "\n".join(lines[-10:]).lower()):
            return ("Claude is waiting for a permission answer. Answer it in the Claude app or with "
                    "pilot chat in Terminal.")
        return None

    def kill(self):
        pass   # the shared conversation outlives the bridge

    def reset(self):
        self.reset_at = time.time()
        self.send_text("/clear")

    def stop(self):
        try:
            tmux("send-keys", "-t", SHARED_TMUX, "Escape")
        except Exception:
            pass

    def send_text(self, s):
        tmux("send-keys", "-t", SHARED_TMUX, "-l", s)
        time.sleep(0.15)
        tmux("send-keys", "-t", SHARED_TMUX, "Enter")

    # --- transcript -----------------------------------------------------------
    def project_dir(self):
        return os.path.join(os.path.expanduser("~/.claude/projects"), slug(SHARED_DIR))

    def transcripts(self):
        d = self.project_dir()
        try:
            return [os.path.join(d, f) for f in os.listdir(d) if f.endswith(".jsonl")]
        except Exception:
            return []

    def current_transcript(self):
        ts = self.transcripts()
        return max(ts, key=os.path.getmtime) if ts else None

    @staticmethod
    def user_text(ev):
        c = (ev.get("message") or {}).get("content")
        if isinstance(c, str):
            return c
        if isinstance(c, list):
            return " ".join(b.get("text", "") for b in c if isinstance(b, dict) and b.get("type") == "text")
        return ""

    # --- one turn -------------------------------------------------------------
    def turn(self, text_in, on_event):
        self.warm()
        # give a freshly started Claude a moment to draw its prompt
        for _ in range(60):
            if self.pane().strip():
                break
            time.sleep(0.25)
        b = self.blocker()
        if b:
            return {"result": b}
        offsets = {p: os.path.getsize(p) for p in self.transcripts()}
        marker = "%s %s" % (VOICE_TAG, text_in.replace("\n", " ").strip())
        self.send_text(marker)
        self.last_used = time.time()
        bufs, found, final, said_perm, queued = {}, None, None, False, False
        t0 = time.time()
        while time.time() - t0 < 900:
            time.sleep(0.15)
            for p in self.transcripts():
                if found and p != found:
                    continue
                off = offsets.get(p, 0)
                try:
                    size = os.path.getsize(p)
                except OSError:
                    continue
                if size <= off:
                    continue
                with open(p, "rb") as f:
                    f.seek(off)
                    chunk = f.read(size - off)
                offsets[p] = size
                data = bufs.get(p, b"") + chunk
                lines = data.split(b"\n")
                bufs[p] = lines.pop()          # keep a half-written last line for next time
                for raw in lines:
                    try:
                        ev = json.loads(raw)
                    except Exception:
                        continue
                    if ev.get("isSidechain"):
                        continue
                    t = ev.get("type")
                    if not found:
                        if t == "user" and marker[:200] in self.user_text(ev):
                            found = p
                            self.session = ev.get("sessionId")
                        elif (t == "queue-operation" and not queued
                              and marker[:200] in str(ev.get("content", ""))):
                            queued = True    # Claude is busy with a typed message; ours is next
                            on_event({"type": "assistant", "message": {"content": [{"type": "text",
                                      "text": "Finishing what you typed first, then this."}]}})
                        continue
                    if t == "user":
                        ut = self.user_text(ev)
                        if "[Request interrupted" in ut:
                            return {"result": "Stopped.", "stopped": True}
                        on_event(ev)
                    elif t == "assistant":
                        on_event(ev)
                        msg = ev.get("message") or {}
                        texts = [bl.get("text", "") for bl in msg.get("content") or [] if bl.get("type") == "text"]
                        if texts:
                            final = texts[-1]
                        if msg.get("stop_reason") in END_REASONS and texts:
                            return {"result": final, "session_id": self.session}
            if not found and time.time() - t0 > (600 if queued else 20):
                return {"result": self.blocker() or ("Claude didn't pick that up. It may be busy in the "
                                                     "terminal; open pilot chat to see.")}
            if found and not said_perm and int(time.time() - t0) % 3 == 0:
                b = self.blocker()
                if b and "permission" in b:
                    said_perm = True
                    on_event({"type": "assistant", "message": {"content": [{"type": "text", "text": b}]}})
        return {"result": final or "That took too long, so I stopped waiting. It may still finish in the terminal."}

    # --- the whole shared conversation, for the bubble's chat panel ------------
    def history(self, limit=30):
        p = self.current_transcript()
        if not p or os.path.getmtime(p) < self.reset_at:
            return []
        out, cur = [], None
        try:
            with open(p, "rb") as f:
                f.seek(max(0, os.path.getsize(p) - 4 * 1024 * 1024))
                for raw in f:
                    try:
                        ev = json.loads(raw)
                    except Exception:
                        continue
                    if ev.get("isSidechain") or ev.get("isMeta"):
                        continue
                    if ev.get("type") == "user":
                        c = (ev.get("message") or {}).get("content")
                        txt = c if isinstance(c, str) else self.user_text(ev)
                        if (not txt or "tool_result" in json.dumps(c)[:200] or txt.startswith("<")
                                or txt.startswith("[Request interrupted")):
                            continue
                        spoken = txt.startswith(VOICE_TAG)
                        cur = {"you": txt[len(VOICE_TAG):].strip() if spoken else txt,
                               "claude": "", "via": "voice" if spoken else "typed"}
                        out.append(cur)
                    elif ev.get("type") == "assistant" and cur is not None:
                        for bl in (ev.get("message") or {}).get("content") or []:
                            if bl.get("type") == "text" and bl.get("text", "").strip():
                                cur["claude"] = bl["text"].strip()
        except Exception:
            return []
        return out[-limit:]


MODE = os.environ.get("PILOT_MODE", "shared").lower()
if MODE == "shared" and not shutil.which("tmux"):
    MODE = "headless"
CLAUDE = SharedClaude() if MODE == "shared" else Claude()


# --------------------------------------------------------------------------- state

class State:
    def __init__(self):
        self.lock = threading.Lock()
        self.jobs = {}
        self.history = []   # [{"you":..., "claude":..., "t":...}]
        self.timing = collections.deque(maxlen=30)
        self.pending = None  # a shortcut held at a risky step, waiting for a spoken yes


STATE = State()


def last_err_line():
    try:
        with open(CLAUDE_ERR) as f:
            lines = [l.strip() for l in f.readlines()[-20:] if l.strip()]
        return lines[-1] if lines else ""
    except Exception:
        return ""


def finish_job(job, reply, timing):
    job["reply"] = reply
    job["timing"] = timing
    with STATE.lock:
        STATE.history.append({"you": job["you"], "claude": reply, "t": int(time.time())})
        STATE.history = STATE.history[-30:]
        STATE.timing.append(dict(timing, you=job["you"][:60]))
    log_timing(dict(timing, kind="turn", t=int(time.time())))


def run_job(job_id, text_in, heard_at):
    job = STATE.jobs[job_id]
    t0 = time.time()
    timing = {"tool_calls": 0, "tool_ms": 0}
    if heard_at:
        lag = int(t0 * 1000 - heard_at)
        if 0 <= lag < 60000:
            timing["heard_to_bridge_ms"] = lag
    tool_started = {}

    def on_event(ev):
        now = time.time()
        timing.setdefault("first_event_ms", int((now - t0) * 1000))
        t = ev.get("type")
        if t == "assistant":
            for block in (ev.get("message") or {}).get("content") or []:
                if block.get("type") == "tool_use":
                    tool_started[block.get("id", "")] = now
                    timing["tool_calls"] += 1
                    job["progress"].append(nice_tool(block.get("name", "")) + "...")
                elif block.get("type") == "text" and block.get("text", "").strip():
                    s = block["text"].strip()
                    timing.setdefault("first_word_ms", int((now - t0) * 1000))
                    job["progress"].append(s)
                    job["say"].append(s)
            job["progress"] = job["progress"][-12:]
        elif t == "user":
            for block in (ev.get("message") or {}).get("content") or []:
                if isinstance(block, dict) and block.get("type") == "tool_result":
                    st = tool_started.pop(block.get("tool_use_id", ""), None)
                    if st:
                        timing["tool_ms"] += int((now - st) * 1000)

    reply = ""
    try:
        res = CLAUDE.turn(text_in, on_event)
    except Exception as e:
        res = None
        job["error_hint"] = str(e)
    if res:
        reply = (res.get("result") or "").strip()
        if res.get("is_error") and not reply:
            reply = "Something went wrong on my side."
    timing["total_ms"] = int((time.time() - t0) * 1000)
    timing["thinking_ms"] = max(0, timing["total_ms"] - timing["tool_ms"])
    if job.get("stopped"):
        job["state"] = "done"
        finish_job(job, "Stopped.", timing)
    elif not reply:
        hint = job.get("error_hint") or last_err_line() or "no answer"
        if "login" in hint.lower() or "auth" in hint.lower():
            hint = "Claude needs you to sign in. Open the terminal and run claude once."
        job["state"] = "error"
        finish_job(job, "I hit a problem: %s" % hint[:200], timing)
    else:
        job["state"] = "done"
        finish_job(job, reply, timing)
    job.pop("proc", None)
    threading.Thread(target=CLAUDE.warm, daemon=True).start()


# --------------------------------------------------------------------------- shortcuts by voice

def phonectl_module():
    sys.path.insert(0, HERE)
    import phonectl  # noqa: E402
    return phonectl


def clean_words(s):
    return re.sub(r"[^a-z0-9' ]", "", s.lower()).strip()


def shortcut_request(text_in):
    """'run morning', 'do the morning shortcut', or just a saved shortcut's name."""
    try:
        pc = phonectl_module()
    except Exception:
        return None
    w = clean_words(text_in)
    m = re.match(r"^(?:please )?(?:run|do|start|play) (?:my |the )?(.+?)(?: shortcut)?(?: please)?$", w)
    name = m.group(1) if m else w
    if not name:
        return None
    k, sc = pc.find_shortcut(name)
    if not k:
        return None
    # a bare word must be the whole shortcut name, so "settings" doesn't trigger "open settings fast"
    if not m and pc.norm_name(name) != k:
        return None
    return k


def run_shortcut_job(job_id, name, from_step=1, confirmed=False):
    job = STATE.jobs[job_id]
    t0 = time.time()
    job["progress"].append("Running %s..." % name)
    try:
        pc = phonectl_module()
        report = pc.shortcut({"action": "run", "name": name, "from_step": from_step, "confirmed": confirmed})
    except Exception as e:
        report = "failed: %s" % e
    hold = re.search(r"Step (\d+) \((.*?)\) is on HOLD", report)
    if hold:
        step = int(hold.group(1))
        label = re.search(r'looks like "(.*?)"', report)
        what = label.group(1) if label else hold.group(2)
        STATE.pending = {"name": name, "step": step, "t": time.time()}
        reply = "The %s shortcut wants to tap %s. Should I go ahead?" % (name, what[:60])
    elif "Stopped here" in report or report.startswith("failed") or report.startswith("No shortcut"):
        line = [l for l in report.splitlines() if "Stopped" in l or "failed" in l or "No shortcut" in l]
        reply = "The %s shortcut stopped: %s" % (name, (line[-1] if line else report)[:160])
        STATE.pending = None
    else:
        reply = "Done, ran %s." % name
        STATE.pending = None
    job["state"] = "done"
    job["say"].append(reply)
    finish_job(job, reply, {"tool_calls": 0, "tool_ms": int((time.time() - t0) * 1000),
                            "total_ms": int((time.time() - t0) * 1000), "shortcut": name})


# --------------------------------------------------------------------------- HTTP

def phone_connected():
    try:
        out = subprocess.run(["adb", "devices"], capture_output=True, text=True, timeout=5).stdout
        return any(l.strip().endswith("\tdevice") for l in out.splitlines()[1:])
    except Exception:
        return False


class Handler(BaseHTTPRequestHandler):
    server_version = "pocket-pilot/2"
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def _send(self, code, obj=None):
        body = b"" if obj is None else json.dumps(obj).encode()
        self.send_response(code)
        if code != 204:
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if body:
            self.wfile.write(body)

    def _client_alive(self):
        try:
            r, _, _ = select.select([self.connection], [], [], 0)
            if r:
                return bool(self.connection.recv(1, socket.MSG_PEEK))
            return True
        except Exception:
            return False

    def _authed(self):
        if secrets.compare_digest(self.headers.get("X-Token", ""), token()):
            return True
        self._body()  # drain, so keep-alive stays in step
        self._send(401, {"error": "wrong token"})
        return False

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n <= 0:
            return {}
        raw = self.rfile.read(n)
        if n > 8 * 1024 * 1024:
            return {}
        try:
            return json.loads(raw.decode())
        except Exception:
            return {}

    def do_GET(self):
        if not self._authed():
            return
        u = urlparse(self.path)
        if u.path == "/agent/next":
            item = AGENT.next(self._client_alive)
            if item is None:
                return self._send(204)
            try:
                return self._send(200, item)
            except Exception:
                AGENT.requeue(item)
                raise
        if u.path == "/agent/status":
            return self._send(200, {"connected": AGENT.connected(), "last_poll_age_s": round(time.time() - AGENT.last_poll, 1)})
        if u.path == "/health":
            return self._send(200, {"ok": True, "agent": AGENT.connected(), "phone": phone_connected(),
                                    "mode": MODE, "shared_running": MODE == "shared" and CLAUDE.running()})
        if u.path == "/job":
            jid = (parse_qs(u.query).get("id") or [""])[0]
            job = STATE.jobs.get(jid)
            if not job:
                return self._send(404, {"error": "no such job"})
            return self._send(200, {k: v for k, v in job.items() if k != "proc"})
        if u.path == "/history":
            if MODE == "shared":
                return self._send(200, {"history": CLAUDE.history(), "shared": True})
            return self._send(200, {"history": STATE.history})
        if u.path == "/timing":
            return self._send(200, {"turns": list(STATE.timing)})
        self._send(404, {"error": "not found"})

    def do_POST(self):
        if not self._authed():
            return
        u = urlparse(self.path)
        b = self._body()
        if u.path == "/agent/result":
            AGENT.result(b.get("id", ""), b.get("result") or {})
            return self._send(200, {"ok": True})
        if u.path == "/agent/call":
            timeout = min(60.0, max(1.0, float(b.get("timeout") or 25)))
            res, err = AGENT.call(b.get("cmd", ""), b.get("args") or {}, timeout)
            if err == "offline":
                return self._send(503, {"offline": True, "error": "Pocket Pilot fast control isn't linked"})
            if err:
                return self._send(504, {"error": "The phone didn't answer in time"})
            return self._send(200, {"result": res})
        if u.path == "/ask":
            text_in = (b.get("text") or "").strip()
            if not text_in:
                return self._send(400, {"error": "empty"})
            running = [j for j in STATE.jobs.values() if j["state"] == "running"]
            if running:
                return self._send(409, {"error": "busy", "job": running[0]["id"]})
            jid = uuid.uuid4().hex[:12]
            STATE.jobs[jid] = {"id": jid, "state": "running", "progress": [], "say": [], "reply": "", "you": text_in}
            for old in list(STATE.jobs)[:-20]:
                if STATE.jobs[old]["state"] != "running":
                    STATE.jobs.pop(old, None)
            target, args = run_job, (jid, text_in, b.get("heard_at"))
            pend = STATE.pending
            if pend and time.time() - pend["t"] > 180:
                pend = STATE.pending = None
            w = clean_words(text_in)
            if pend and w in YES:
                STATE.pending = None
                target, args = run_shortcut_job, (jid, pend["name"], pend["step"], True)
            elif pend and w in NO:
                STATE.pending = None
                job = STATE.jobs[jid]
                job["state"] = "done"
                job["say"].append("Okay, I won't.")
                finish_job(job, "Okay, I won't.", {"tool_calls": 0, "tool_ms": 0, "total_ms": 0})
                return self._send(200, {"job": jid})
            else:
                STATE.pending = None
                name = shortcut_request(text_in)
                if name:
                    target, args = run_shortcut_job, (jid, name)
            threading.Thread(target=target, args=args, daemon=True).start()
            return self._send(200, {"job": jid})
        if u.path == "/stop":
            for j in STATE.jobs.values():
                if j["state"] == "running" and (not b.get("job") or j["id"] == b.get("job")):
                    j["stopped"] = True
            CLAUDE.stop()
            STATE.pending = None
            return self._send(200, {"ok": True})
        if u.path == "/reset":
            CLAUDE.reset()
            with STATE.lock:
                STATE.history = []
                STATE.pending = None
            threading.Thread(target=CLAUDE.warm, daemon=True).start()
            return self._send(200, {"ok": True})
        self._send(404, {"error": "not found"})


class Server(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 64


def main():
    if "--token" in sys.argv:
        print(token())
        return 0
    if "--write-prompt" in sys.argv:
        SharedClaude().write_prompt()
        return 0
    write_mcp_conf()
    tok = token()
    srv = Server(("0.0.0.0", PORT), Handler)
    print("Pocket Pilot bridge v3 (%s mode) on port %d. Token for the app: %s" % (MODE, PORT, tok), flush=True)
    if MODE == "shared":
        CLAUDE.write_prompt()
    threading.Thread(target=CLAUDE.warm, daemon=True).start()
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    CLAUDE.kill()
    return 0


if __name__ == "__main__":
    sys.exit(main())
