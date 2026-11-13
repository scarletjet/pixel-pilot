#!/usr/bin/env bash
# Off-phone test of the wake word gate with a real Vosk model and synthetic speech.
#   bash test/run-wake-test.sh /path/to/vosk-model "hey pilot"
# Needs: java, espeak-ng, sox, curl. Uses English speech; pass another model + phrase + ESPEAK_VOICE=es for Spanish.
set -euo pipefail
cd "$(dirname "$0")/.."
MODEL=${1:?path to an unpacked Vosk model}; PHRASE=${2:-hey pilot}; V=${ESPEAK_VOICE:-en-us}
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
M=https://maven-central.storage-download.googleapis.com/maven2
curl -fsSL -o "$W/vosk.jar" $M/com/alphacephei/vosk/0.3.45/vosk-0.3.45.jar
curl -fsSL -o "$W/jna.jar" $M/net/java/dev/jna/jna/5.13.0/jna-5.13.0.jar
CP="$W/vosk.jar:$W/jna.jar"
mkdir -p "$W/src/live/scarletsworkshop/pocketpilot" "$W/classes"
cp app/src/main/java/live/scarletsworkshop/pocketpilot/WakeGate.java test/WakeGateTest.java "$W/src/live/scarletsworkshop/pocketpilot/"
javac -nowarn -cp "$CP" -d "$W/classes" "$W"/src/live/scarletsworkshop/pocketpilot/*.java
say() { espeak-ng -v "$V" -s 130 -w "$W/t.wav" "$2"; sox "$W/t.wav" -r 16000 -c 1 -b 16 -e signed-integer "$W/$1.raw" 2>/dev/null; }
sil() { sox -n -r 16000 -c 1 -b 16 -e signed-integer "$W/$1.raw" trim 0 "$2"; }
sil s05 0.5; sil s15 1.6; sil s7 7
say chat "we are going to have dinner at my mother's house and then go to the park with the kids"
say cmd "$PHRASE open the camera"; say wake "$PHRASE"; say req "turn off the wifi please"
say word "the ${PHRASE##* } of the plane arrived late yesterday"
cat "$W/s05.raw" "$W/chat.raw" > "$W/1_chatter_only.raw"
cat "$W/s05.raw" "$W/chat.raw" "$W/cmd.raw" > "$W/2_same_breath.raw"
cat "$W/s05.raw" "$W/wake.raw" "$W/s15.raw" "$W/req.raw" > "$W/3_wake_pause_request.raw"
cat "$W/s05.raw" "$W/wake.raw" "$W/s7.raw" > "$W/4_wake_then_nothing.raw"
cat "$W/s05.raw" "$W/word.raw" > "$W/5_last_word_in_chatter.raw"
java -Dfile.encoding=UTF-8 -Djna.encoding=UTF-8 -cp "$W/classes:$CP" live.scarletsworkshop.pocketpilot.WakeGateTest \
  "$MODEL" "$PHRASE" strict "$W"/[1-5]_*.raw
echo "Expected: 1 [] · 2 [WAKE, CMD[open the camera]] · 3 [WAKE, YES?, CMD[...]] · 4 [WAKE, YES?, TIMEOUT] · 5 []"
