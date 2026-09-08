#!/bin/bash
# DownloadX APK build — manual pipeline (no Gradle): aapt2 -> kotlinc -> d8 -> zip -> align -> sign
set -e
export JAVA_HOME=/opt/jdk-17.0.20.1+1
export PATH=$JAVA_HOME/bin:$PATH

SDK=/opt/android-sdk
BT=$SDK/build-tools/34.0.0
PLAT=$SDK/platforms/android-34/android.jar
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build"
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/gen" "$OUT/dex"

echo "== [1/7] aapt2 compile resources =="
$BT/aapt2 compile --dir "$ROOT/app/src/res" -o "$OUT/res.zip"

echo "== [2/7] aapt2 link -> base.apk + R.java =="
$BT/aapt2 link \
  -o "$OUT/base.apk" \
  -I "$PLAT" \
  --manifest "$ROOT/app/src/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  --version-code 3 \
  --version-name 1.0.2 \
  --auto-add-overlay \
  "$OUT/res.zip"

echo "== [3/7] javac R.java =="
javac -source 11 -target 11 -nowarn -d "$OUT/classes" "$OUT/gen/com/downloadx/app/R.java" 2>/dev/null

echo "== [4/7] kotlinc engine + app =="
/opt/kotlinc/bin/kotlinc \
  -classpath "$PLAT:/opt/coroutines.jar:$OUT/classes" \
  -jvm-target 11 \
  -d "$OUT/classes" \
  $(find "$ROOT/engine/src/main" "$ROOT/app/src/kotlin" -name '*.kt') 2>&1 | grep -v "^warning:" || true

echo "== [5/7] d8 -> classes.dex (app + engine + stdlib + coroutines + android-dispatcher) =="
(cd "$OUT/classes" && jar cf "$OUT/app-classes.jar" .)
$BT/d8 --release --min-api 26 --lib "$PLAT" \
  --output "$OUT/dex" \
  "$OUT/app-classes.jar" \
  /opt/kotlinc/lib/kotlin-stdlib.jar \
  /opt/coroutines.jar \
  /opt/coroutines-android.jar

echo "== [6/7] package dex + align =="
python3 - "$OUT" << 'PYEOF'
import sys, zipfile, os
out = sys.argv[1]
base = os.path.join(out, 'base.apk')
with zipfile.ZipFile(base, 'a') as z:
    z.write(os.path.join(out, 'dex', 'classes.dex'), 'classes.dex')
    # ServiceLoader registrations for Dispatchers.Main on Android (read from APK entries)
    with zipfile.ZipFile('/opt/coroutines-android.jar') as src:
        for name in src.namelist():
            if name.startswith('META-INF/services/') and not name.endswith('/'):
                z.writestr(name, src.read(name))
print("dex + service files added to", base)
PYEOF
$BT/zipalign -f 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "== [7/7] sign =="
if [ ! -f "$OUT/debug.keystore" ]; then
  keytool -genkeypair -keystore "$OUT/debug.keystore" -storepass android \
    -alias androiddebugkey -keypass android \
    -dname "CN=Android Debug,O=Android,C=US" -keyalg RSA -keysize 2048 -validity 10000 2>/dev/null
fi
cp "$OUT/aligned.apk" "$ROOT/DownloadX.apk"
$BT/apksigner sign \
  --ks "$OUT/debug.keystore" --ks-pass pass:android --key-pass pass:android \
  --out "$ROOT/DownloadX.apk" "$OUT/aligned.apk"
$BT/apksigner verify "$ROOT/DownloadX.apk" && echo "signature OK"

ls -la "$ROOT/DownloadX.apk"
echo "BUILD DONE: $ROOT/DownloadX.apk"
