# Pocket Pilot

Talk to Claude Code on your Android phone, and let it see your screen and tap, swipe and type for you. A floating bubble works from any app. Say the wake word, then what you want: "Hey Pilot, turn on Bluetooth."

Pocket Pilot has two parts:

- **The app** (`pocket-pilot.apk`): the bubble, the hot mic, and fast screen control through Android's accessibility system.
- **The bridge** (`pocket-pilot-setup.sh`): runs in Android's Linux Terminal next to Claude Code and gives it `phone_*` tools.

You need an Android phone with the **Linux Terminal** app (Android 15 or later, for example a Pixel 6 or newer; turn it on in Developer options) and your own Claude account for Claude Code.

## How the hot mic keeps your speech private

The hot mic is built so that only what you say after the wake word ever leaves the phone.

1. **The listening happens on the phone.** A speech model (Vosk, about 40 MB) runs inside the app. It works with the network off. Speech is never sent anywhere to be recognised while it waits for the wake word.
2. **No audio is kept.** Sound comes in tenth-of-a-second pieces. Each piece goes into the on-phone recogniser and is then overwritten with zeros. Nothing is recorded, and nothing goes to storage. There isn't even a rolling buffer of the last few seconds.
3. **Everything else is checked and dropped.** At each pause, the app checks the words for the wake word, then throws them away and resets the recogniser. If someone talks without a pause (a TV, say), the app drops it every 15 seconds anyway, so even its working memory stays small.
4. **A second check stops false wakes.** The indicator can switch on when the wake word seems to start a sentence. But the request only goes out if the finished sentence really contains the wake word. If it doesn't, the sentence is dropped.
5. **You can see when it's listening to you.** The bubble is slate grey with a thin white ring while it waits for the wake word. It turns red and pulses while it takes your request, with a short buzz and a "Listening..." caption. The notification says "Listening for your request..." too.
6. **Only the request goes out.** The request is the words after the wake word, as text, never audio. It goes to Claude Code in your Linux Terminal, and Claude Code sends it to Anthropic like anything you type there.

Things worth knowing:

- Android shows its green microphone dot the whole time the hot mic is on, because the mic is open even though nothing is kept.
- The hot mic pauses while Claude is speaking, so it never hears itself.
- When Claude asks you a question ("Should I send it?"), you can answer without the wake word for about 6 seconds. The red indicator is on during that window.
- **Tap-to-talk** (tapping the bubble with the hot mic off) uses Android's own speech recogniser. Depending on your phone, that may run on the phone or on Google's servers. The hot mic never uses it.
- Mute your headset, or tap the bubble, to pause the hot mic.

## Install

### 1. Turn on the Linux Terminal and install Claude Code

1. Settings > System > Developer options > **Linux development environment**: on. Open the **Terminal** app and let it set up.
2. In Terminal, install Claude Code and sign in:

```
curl -fsSL https://claude.ai/install.sh | bash
claude
```

### 2. Install the bridge

Download `pocket-pilot-setup.sh` into your phone's **Downloads** folder. Then, in Terminal:

```
bash /mnt/shared/pocket-pilot-setup.sh
```

It prints a **token** at the end. You'll paste it into the app. (`pilot token` shows it again.)

Then run `pilot chat` once. Choose **Yes** when Claude asks to trust the folder. Leave it by switching apps.

In **Terminal > settings (gear) > Port control**, allow port **8765**.

### 3. Install the app

1. Download `pocket-pilot.apk` and open it from Files > Downloads. Allow installing from Files if asked.
2. Open **Pocket Pilot**, paste the token, and go through the numbered buttons:
   1. Allow drawing over apps.
   2. Allow the microphone and notifications.
   3. Turn on fast control (Accessibility). If the switch is greyed out, tap **Open App info**, then **⋮** (top right), then **Allow restricted settings**, and try again.
   4. Test connection. It should say **Connected** and **Fast control: linked**.
3. Tap **Download wake word model** (about 40 MB, once).
4. Tap **Test wake word** and say "Hey Pilot, open the camera". It shows what it would send.
5. Tick **Hot mic on when the bubble starts** if you like, then tap **Start bubble**.

For a smooth experience, set **App battery usage** to **Unrestricted** for Terminal and Pocket Pilot (Settings > Apps), and set **Terminal > settings > Advanced > Keep awake** to its longest timer.

## Using it

- **Hot mic:** say "Hey Pilot", then your request. "Hey Pilot" on its own shows "Yes?" and waits a few seconds.
- **While Claude works:** "Hey Pilot, stop" interrupts. Any other request waits its turn.
- **Turn the hot mic on or off:** hold the bubble and tap **Hot mic**, use the notification, or say "Hey Pilot, hot mic off".
- **Tap-to-talk:** with the hot mic off, tap the bubble and talk.
- **Hold the bubble** for the chat panel: the conversation, a box to type in, and New chat.
- **Shortcuts:** after Claude does something, say "save that as morning". Later, "run morning" replays it straight away.
- **From the terminal:** `pilot chat` opens the same conversation the bubble uses.

```
pilot chat       # the shared conversation
pilot status     # is everything running?
pilot restart    # restart the bridge (the conversation keeps going)
pilot stop all   # stop everything
```

## Choosing a wake word

Type any phrase under **Wake word** in the app. **Hey Pilot** is the default.

- Two or three words work best, ending in a word you rarely say in normal talk. The last word has to be heard exactly, and the earlier words can be a little off ("hay Pilot" still works).
- **Also wake on the last word alone** lets you say "Pilot, open maps". It only counts when that word starts the sentence. It's handier, but it wakes by mistake more often.
- The model only knows words in its dictionary. Made-up words won't work. **Test wake word** shows whether it hears yours.
- **Other languages:** paste a link to another small model from the Vosk models page (alphacephei.com/vosk/models) under **Wake word model**, then download it. Requests are recognised in that language too.
- If you share or publish a build, choose a default wake word you have the right to use. A brand name like "Hey Claude" belongs to its owner.

## Safety holds

- **Claude asks before anything that sends a message, posts, pays or buys, deletes, or changes a setting or permission.** That rule is in the tool descriptions, the bridge's instructions and the voice prompt.
- **The app holds risky taps itself.** A tap on a button labelled Send, Pay, Buy, Delete, Confirm, Allow, Install, Uninstall, Sign out and similar is held until Claude has your yes. Fast control enforces this for taps by label and by position.
- **Enter in a chat or message field** is held the same way, since it usually sends.
- **Shortcuts never save a yes.** A shortcut that reaches a Send or Delete button stops there and asks you every time.
- **The bridge only answers requests that carry your token.** Voice mode can only use the phone tools and web search.
- **Private messages and notifications aren't read aloud** unless you ask.
- **Stop** in the notification interrupts Claude at any time.
- **Fast control is a normal Android accessibility service.** Switch it off any time in Settings > Accessibility, and Android shows when it's on.

Pocket Pilot acts on your phone as you, so treat it like handing someone your unlocked phone.

## If something's off

- **Test says fast control off:** switch on Pocket Pilot fast control in Settings > Accessibility. If it's greyed out, use Allow restricted settings (step 3 of the app).
- **Fast control on but not linked:** check the bridge runs (`pilot status`) and port 8765 is allowed in Terminal's Port control.
- **Hot mic won't turn on:** download the wake word model in the app first, and allow the microphone.
- **It never hears the wake word:** try **Test wake word**, say it a little slower, or pick words that are easier to tell apart.
- **It wakes by mistake:** untick **Also wake on the last word alone**, or choose a longer wake word.
- **Claude didn't pick something up:** `pilot chat` shows what the terminal is doing.

## What's tested and what isn't

- **Tested off the phone:** the wake word gate, on synthetic speech with a real Vosk model. It ignored chatter, a TV-length ramble and the last word used in an ordinary sentence. It took a request said in the same breath as the wake word, and the wake word alone ("Yes?", then the request). It timed out when nothing followed, and it left nothing held after each case. The app builds. The bridge installs and answers only with the token.
- **Not yet tried on a phone:** the microphone loop, the Bluetooth headset mic, the buzz, and how well the English model hears real voices in noisy rooms. The first real-voice test is the **Test wake word** button.

## Build from source

On any Debian or Ubuntu machine, or in the Linux Terminal itself:

```
bash app/build-apk.sh     # -> app/out/pocket-pilot.apk (downloads the Vosk and JNA libraries, checked by SHA-256)
bash make-setup.sh        # -> dist/pocket-pilot-setup.sh
```

The build makes a local debug key the first time. To publish, sign with your own key: set `POCKET_KEYSTORE`, `POCKET_KS_PASS` and `POCKET_KEY_ALIAS`.

Code map:

- `app/src/main/java/.../WakeGate.java`: the wake word logic (plain Java, testable off the phone).
- `app/src/main/java/.../WakeListener.java`: the microphone loop that zeroes each chunk.
- `app/src/main/java/.../WakeModel.java`: the one-time model download and unpack.
- `app/src/main/java/.../BubbleService.java`: the bubble, captions, voice and hot mic.
- `app/src/main/java/.../PilotAccessibilityService.java`: fast screen reading and tapping, with the risky-tap hold.
- `vm/phonectl.py`: Claude Code's phone tools (MCP). `vm/voice_bridge.py`: the bridge. `vm/pilot`: the command.

## License

Apache License 2.0. See `LICENSE` and `NOTICE`. Vosk and its models are Apache 2.0 too. JNA is used under its Apache 2.0 option.

Pocket Pilot is an independent project. It isn't made or endorsed by Anthropic or Google.
