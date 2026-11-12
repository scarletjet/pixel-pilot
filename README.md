# Pixel Pilot

Talk to Claude Code from a floating voice bubble on Android, and let it drive your phone.
The same live conversation shows in the bubble, the on-device Linux terminal, and the Claude app (Remote Control).

## What's here
- `phonectl.py` - MCP server with phone tools: read the screen as text, tap, type, scroll, open apps, settings and deep links, shortcuts.
- `voice_bridge.py` - local bridge between the voice bubble app and Claude Code (token-protected).
- `pilot` - helper: `pilot start | chat | stop | status | log | timing | watch`.

## Safety
Actions that send, post, pay, delete or change settings return `HOLD`, and run only after the user says yes.

## Speed (measured on a Pixel)
Reading the screen 0.2s, typing 0.2s, tapping by name ~1.1s, opening an app ~1.7s.
