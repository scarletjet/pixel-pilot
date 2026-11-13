#!/usr/bin/env bash
# Builds dist/pocket-pilot-setup.sh: one self-contained installer for Android's Linux Terminal.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p dist
PAYLOAD=$(tar -C vm -czf - phonectl.py voice_bridge.py pilot | base64 -w 0)
cat > dist/pocket-pilot-setup.sh <<EOF
#!/usr/bin/env bash
# Pocket Pilot setup for Android's Linux Terminal (Debian).
# Gives Claude Code phone_* tools (see + tap + type on your screen), the voice bridge for the bubble app,
# and one shared Claude conversation that the terminal, the bubble and the Claude app all use live.
# Safe to run again to upgrade: your token, shortcuts and pairing are kept.
#   bash /mnt/shared/pocket-pilot-setup.sh
set -euo pipefail
DIR="\$HOME/phone-control"
BIN="\$HOME/.local/bin"
mkdir -p "\$DIR" "\$BIN"

echo "== 1/5 Installing adb (backup route), Python image support, tmux =="
sudo apt-get update -qq
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq adb python3 python3-pil tmux curl >/dev/null

adbver() { adb version 2>/dev/null | sed -n 's/^Version \([0-9]*\)\..*/\1/p' | head -1; }
V=\$(adbver); V=\${V:-0}
if [ "\$V" -lt 30 ]; then
  echo "adb \$V is too old to pair. Trying Debian backports..."
  CODENAME=\$(. /etc/os-release; echo "\$VERSION_CODENAME")
  echo "deb http://deb.debian.org/debian \${CODENAME}-backports main" | sudo tee /etc/apt/sources.list.d/backports.list >/dev/null
  sudo apt-get update -qq || true
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq -t "\${CODENAME}-backports" adb >/dev/null || true
  V=\$(adbver); V=\${V:-0}
fi
if [ "\$V" -lt 30 ]; then
  echo "Note: adb is older than 30, so wireless pairing won't work. That's fine with fast control in the app."
else
  echo "adb \$V ok"
fi

echo "== 2/5 Unpacking Pocket Pilot to \$DIR =="
echo "$PAYLOAD" | base64 -d | tar -xzf - -C "\$DIR"
chmod +x "\$DIR/phonectl.py" "\$DIR/voice_bridge.py" "\$DIR/pilot"
ln -sf "\$DIR/phonectl.py" "\$BIN/phonectl"
ln -sf "\$DIR/pilot" "\$BIN/pilot"
case ":\$PATH:" in *":\$BIN:"*) ;; *) echo 'export PATH="\$HOME/.local/bin:\$PATH"' >> "\$HOME/.bashrc"; export PATH="\$BIN:\$PATH";; esac

echo "== 3/5 Adding the phone tools to Claude Code =="
if command -v claude >/dev/null || [ -x "\$BIN/claude" ]; then
  CL=\$(command -v claude || echo "\$BIN/claude")
  "\$CL" mcp remove --scope user phone >/dev/null 2>&1 || true
  "\$CL" mcp add --scope user phone -- python3 "\$DIR/phonectl.py" serve
else
  echo "!! Claude Code isn't installed yet. Install it with:"
  echo "     curl -fsSL https://claude.ai/install.sh | bash"
  echo "   then run this setup again (it adds the phone tools for you)."
fi

echo "== 4/5 Starting the voice bridge whenever Terminal opens =="
if ! grep -q "pocket-pilot autostart" "\$HOME/.bashrc" 2>/dev/null; then
  cat >> "\$HOME/.bashrc" <<'RC'
# pocket-pilot autostart
command -v pilot >/dev/null && ! tmux has-session -t =pilot 2>/dev/null && pilot start >/dev/null 2>&1
RC
fi
sed -i 's/tmux has-session -t pilot 2/tmux has-session -t =pilot 2/' "\$HOME/.bashrc"
"\$BIN/pilot" restart >/dev/null 2>&1 || "\$BIN/pilot" start

echo "== 5/5 Done =="
echo
echo "One shared conversation is now running. Next:"
echo "  1. Run:  pilot chat"
echo "     The first time, Claude asks you to trust the folder (choose Yes) and may ask you to sign in."
echo "     That's the same conversation the bubble talks to. Leave it by switching apps, or Ctrl-b then d."
echo "  2. Install pocket-pilot.apk, open Pocket Pilot and follow its numbered buttons (see the README)."
echo "  3. Optional: in the Claude app, open the session called Pocket Pilot to read along there too."
echo
echo "Bubble app token:  \$(python3 \$DIR/voice_bridge.py --token)"
echo "Where the time goes, any time:  pilot timing"
EOF
chmod +x dist/pocket-pilot-setup.sh
cp app/out/pocket-pilot.apk dist/pocket-pilot.apk 2>/dev/null || true
echo "Wrote dist/pocket-pilot-setup.sh ($(wc -c < dist/pocket-pilot-setup.sh) bytes)"
