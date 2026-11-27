#!/bin/bash
# Builds the APK with plain aapt + javac + d8 (no Gradle).
# aircast and airshim ship as lib/<abi>/lib*.so and are run from nativeLibraryDir.
#
# Signing: by default a local debug.keystore (created on first build). For a release, set
#   KEYSTORE=path/to/release.jks KEY_ALIAS=name KS_PASS=... [KEY_PASS=...]
# or put those lines in keystore.properties (gitignored), which is read if present.
# (KS_PASS/KEY_PASS are passed to apksigner as env: values, not on the command line).
set -euo pipefail
cd "$(dirname "$0")"
if [ -f keystore.properties ] && [ -z "${KEYSTORE:-}" ]; then . ./keystore.properties; fi
. ./sdk-env.sh
# javac classpath separator (Windows javac wants ";" even under Git Bash)
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) CPSEP=";" ;; *) CPSEP=":" ;; esac
CP=$ANDROID_JAR; for j in libs/*.jar; do CP=$CP$CPSEP$j; done   # libs/: Shizuku API (MIT)
VC=${VERSION_CODE:-11}; VN=${VERSION_NAME:-1.1.0}
rm -rf build && mkdir -p build/gen build/classes build/dex
tool "$BT/aapt" package -f -m -J build/gen -M AndroidManifest.xml -S res -I "$ANDROID_JAR" \
  --min-sdk-version 28 --target-sdk-version 34 --version-code "$VC" --version-name "$VN" \
  -F build/app.unsigned.apk
javac -nowarn -Xlint:-options --release 11 -encoding UTF-8 -classpath "$CP" \
  -d build/classes $(find build/gen src -name '*.java')
tool "$BT/d8" --min-api 28 --lib "$ANDROID_JAR" --output build/dex $(find build/classes -name '*.class') libs/*.jar
cp build/dex/classes.dex build/
(cd build && tool "$BT/aapt" add -f app.unsigned.apk classes.dex >/dev/null)
(tool "$BT/aapt" add -f build/app.unsigned.apk lib/*/*.so >/dev/null)
tool "$BT/zipalign" -f -p 4 build/app.unsigned.apk build/app.aligned.apk
if [ -n "${KEYSTORE:-}" ]; then
  export KS_PASS KEY_PASS="${KEY_PASS:-${KS_PASS:-}}"
  tool "$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "${KEY_ALIAS:?set KEY_ALIAS}"     --ks-pass env:KS_PASS --key-pass env:KEY_PASS --out build/airplay-bridge.apk build/app.aligned.apk
else
  [ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android     -alias debug -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=AirPlay Bridge debug" >/dev/null 2>&1
  tool "$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --ks-key-alias debug     --out build/airplay-bridge.apk build/app.aligned.apk
fi
tool "$BT/apksigner" verify build/airplay-bridge.apk && echo "Built build/airplay-bridge.apk ($VN)"
