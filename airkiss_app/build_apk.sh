#!/usr/bin/env bash
set -e
WS="/d/@Herbert/Desktop/@Workbuddy/onlywell表芯WIFI/airkiss_app"
cd "$WS"
# 原生 Windows 工具(javac/aapt2/d8/apksigner/zipalign)需要 Windows 风格路径(正斜杠可被 Windows API 接受)
JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-17.0.17.10-hotspot"
SDK="C:/Android"
BT="$SDK/build-tools/34.0.0"
PLAT="$SDK/platforms/android-34"
AJAR="$PLAT/android.jar"
PY="C:/Users/heyp/.workbuddy/binaries/python/versions/3.13.12/python.exe"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

mkdir -p out/r

echo "[1/6] aapt2 compile resources"
"$BT/aapt2.exe" compile --dir res -o out/res.zip

echo "[2/6] aapt2 link -> unsigned apk + R.java"
"$BT/aapt2.exe" link --auto-add-overlay -o out/unsigned.apk -I "$AJAR" --manifest AndroidManifest.xml -R out/res.zip --java out/r

echo "[3/6] javac"
"$JAVA_HOME/bin/javac.exe" -encoding UTF-8 -cp "$AJAR;out/r" -d out/classes src/com/onlywell/airkiss/*.java

echo "[4/6] jar + d8 -> classes.dex"
"$JAVA_HOME/bin/jar.exe" cf out/classes.jar -C out/classes .
mkdir -p out/dex
"$BT/d8.bat" --min-api 21 --output out/dex out/classes.jar

echo "[5/6] inject classes.dex into apk"
"$PY" - <<'PYEOF'
import zipfile
src="out/unsigned.apk"; dst="out/unaligned.apk"
with zipfile.ZipFile(src,'r') as zin, zipfile.ZipFile(dst,'w',zipfile.ZIP_DEFLATED) as zout:
    for it in zin.infolist():
        if it.filename == 'classes.dex':
            continue
        zout.writestr(it, zin.read(it.filename))
    zout.write("out/dex/classes.dex","classes.dex")
print("injected classes.dex")
PYEOF

echo "[6/6] zipalign + sign"
"$BT/zipalign.exe" -f -p 4 out/unaligned.apk out/aligned.apk
"$JAVA_HOME/bin/keytool.exe" -genkeypair -v -keystore out/debug.keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Onlywell,O=Onlywell,C=CN" 2>/dev/null || true
"$BT/apksigner.bat" sign --ks out/debug.keystore --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out out/airkiss_config.apk out/aligned.apk

echo "DONE -> out/airkiss_config.apk"
ls -l out/airkiss_config.apk
"$BT/apksigner.bat" verify --print-certs out/airkiss_config.apk 2>&1 | head -n 12
