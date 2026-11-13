#!/usr/bin/env bash
# Build the Pocket Pilot APK without Android Studio or Gradle.
# Works in Android's Linux Terminal (Debian arm64), on a server, or any Debian/Ubuntu box.
#   bash build-apk.sh            -> out/pocket-pilot.apk
# Signing: set POCKET_KEYSTORE / POCKET_KS_PASS / POCKET_KEY_ALIAS to sign with your own release key.
# Without them a local debug key is made in the cache (fine for trying it, not for publishing).
set -euo pipefail
cd "$(dirname "$0")"
APP=$PWD
OUT=$APP/out
CACHE=${POCKET_CACHE:-$HOME/.cache/pocket-pilot}
PKG=live.scarletsworkshop.pocketpilot
VERSION_CODE=1
VERSION_NAME=1.0
MAVEN=(https://maven-central.storage-download.googleapis.com/maven2 https://repo1.maven.org/maven2)
ANDROID_JAR=${ANDROID_JAR:-$CACHE/android-all-14.jar}
# Offline speech (wake word) libraries, checked by SHA-256
VOSK_AAR=com/alphacephei/vosk-android/0.3.47/vosk-android-0.3.47.aar
VOSK_SHA=e3b82bb877753569f5e5f007807e4bc6165c34ba04e772d1df93552af0d27ffa
JNA_AAR=net/java/dev/jna/jna/5.13.0/jna-5.13.0.aar
JNA_SHA=3a03daf5a7212201c3a9b44f734dfc9babb3addcb5cbfa639f9f535e887925c0
ABI=${POCKET_ABI:-arm64-v8a}

need=()
command -v aapt >/dev/null || need+=(aapt)
command -v dalvik-exchange >/dev/null || need+=(dalvik-exchange)
command -v zipalign >/dev/null || need+=(zipalign)
command -v apksigner >/dev/null || need+=(apksigner)
command -v javac >/dev/null || need+=(default-jdk-headless)
command -v unzip >/dev/null || need+=(unzip)
command -v zip >/dev/null || need+=(zip)
if [ ${#need[@]} -gt 0 ]; then
  echo "Installing build tools: ${need[*]}"
  sudo apt-get update -qq && sudo DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends "${need[@]}"
fi

fetch() {  # fetch <maven path> <dest> [sha256]
  local path=$1 dest=$2 sha=${3:-}
  if [ -s "$dest" ] && { [ -z "$sha" ] || echo "$sha  $dest" | sha256sum -c --status; }; then return 0; fi
  for m in "${MAVEN[@]}"; do
    if curl -fsSL --retry 3 -o "$dest.part" "$m/$path"; then
      if [ -z "$sha" ] || echo "$sha  $dest.part" | sha256sum -c --status; then mv "$dest.part" "$dest"; return 0; fi
      echo "Checksum mismatch for $path from $m"
    fi
  done
  rm -f "$dest.part"; echo "Couldn't download $path"; exit 1
}

mkdir -p "$CACHE"
if [ ! -s "$ANDROID_JAR" ]; then
  echo "Downloading Android 14 class library (about 130 MB, once)..."
  fetch org/robolectric/android-all/14-robolectric-10818077/android-all-14-robolectric-10818077.jar "$ANDROID_JAR"
fi
fetch "$VOSK_AAR" "$CACHE/vosk-android.aar" "$VOSK_SHA"
fetch "$JNA_AAR" "$CACHE/jna.aar" "$JNA_SHA"

rm -rf "$OUT/build"; mkdir -p "$OUT/build/gen" "$OUT/build/classes" "$OUT/build/dex" "$OUT/build/libs" "$OUT/build/apk/lib/$ABI"
B=$OUT/build
unzip -q -o -j "$CACHE/vosk-android.aar" classes.jar -d "$B/libs/vosk" && mv "$B/libs/vosk/classes.jar" "$B/libs/vosk.jar"
unzip -q -o -j "$CACHE/jna.aar" classes.jar -d "$B/libs/jna" && mv "$B/libs/jna/classes.jar" "$B/libs/jna.jar"
unzip -q -o -j "$CACHE/vosk-android.aar" "jni/$ABI/libvosk.so" -d "$B/apk/lib/$ABI"
unzip -q -o -j "$CACHE/jna.aar" "jni/$ABI/libjnidispatch.so" -d "$B/apk/lib/$ABI"
# JNA's desktop native folders and build stamp aren't needed in the app
(cd "$B/libs" && zip -q -d jna.jar 'com/sun/jna/*/libjnidispatch*' 'META-INF/*' >/dev/null 2>&1 || true)

# 1. manifest with package + SDK levels (Gradle adds these itself; we add them here)
python3 - "$APP/src/main/AndroidManifest.xml" "$B/AndroidManifest.xml" "$PKG" "$VERSION_CODE" "$VERSION_NAME" <<'PY'
import sys
s = open(sys.argv[1]).read()
s = s.replace('<manifest xmlns:android="http://schemas.android.com/apk/res/android">',
 '<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n'
 '    package="%s" android:versionCode="%s" android:versionName="%s">\n'
 '    <uses-sdk android:minSdkVersion="29" android:targetSdkVersion="35" />' % (sys.argv[3], sys.argv[4], sys.argv[5]), 1)
open(sys.argv[2], "w").write(s)
PY

# 2. resources + R.java
aapt package -f -m -J "$B/gen" -M "$B/AndroidManifest.xml" -S "$APP/src/main/res" \
  -I "$ANDROID_JAR" -F "$B/res.apk" --min-sdk-version 29 --target-sdk-version 35

# 3. compile
CP="$ANDROID_JAR:$B/libs/vosk.jar:$B/libs/jna.jar"
javac --release 8 -nowarn -Xlint:-options -cp "$CP" -d "$B/classes" \
  $(find "$APP/src/main/java" "$B/gen" -name '*.java') 2>&1 | grep -v JAVA_TOOL_OPTIONS || true
[ -f "$B/classes/live/scarletsworkshop/pocketpilot/WakeGate.class" ] || { echo "Compile failed"; exit 1; }

# 4. dex (app + offline speech libraries)
dalvik-exchange --dex --min-sdk-version=26 --output="$B/dex/classes.dex" "$B/classes" "$B/libs/vosk.jar" "$B/libs/jna.jar"

# 5. package, align, sign
cp "$B/res.apk" "$B/unsigned.apk"
(cd "$B/dex" && zip -q -j "$B/unsigned.apk" classes.dex)
(cd "$B/apk" && zip -q -r "$B/unsigned.apk" lib)
zipalign -f -p 4 "$B/unsigned.apk" "$B/aligned.apk"
KS=${POCKET_KEYSTORE:-$CACHE/pocket-debug.keystore}
PASS=${POCKET_KS_PASS:-pocketpilot}
ALIAS=${POCKET_KEY_ALIAS:-pocket}
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass "$PASS" -keypass "$PASS" -alias "$ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Pocket Pilot debug" >/dev/null 2>&1
fi
apksigner sign --ks "$KS" --ks-pass "pass:$PASS" --key-pass "pass:$PASS" --ks-key-alias "$ALIAS" \
  --min-sdk-version 29 --out "$OUT/pocket-pilot.apk" "$B/aligned.apk"
apksigner verify "$OUT/pocket-pilot.apk"
echo "Built $OUT/pocket-pilot.apk"
