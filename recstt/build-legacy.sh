#!/usr/bin/env bash
# 纯手工打包：aapt2 -> javac -> d8 -> zipalign -> apksigner，无需 Gradle。
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:?请设置 ANDROID_SDK_ROOT 或 ANDROID_HOME}}"
KS="${RELEASE_KEYSTORE:-$HERE/keystore.jks}"
KS_PASS="${RELEASE_STORE_PASSWORD:?请用 RELEASE_STORE_PASSWORD 提供密钥口令}"
BT="${BUILD_TOOLS:-$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)}"
PLATFORM_DIR="${PLATFORM_DIR:-$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)}"
PLATFORM="$PLATFORM_DIR/android.jar"
JAVA_HOME="${JAVA_HOME:?请设置 JAVA_HOME 指向 JDK 17}"
JAVAC="$JAVA_HOME/bin/javac"
KEYTOOL="$JAVA_HOME/bin/keytool"

OUT="$HERE/build"
APK="$HERE/recstt.apk"
MIN_SDK=29
TARGET_SDK=36

command -v "$BT/aapt2" >/dev/null || { echo "找不到 aapt2: $BT"; exit 1; }
[ -f "$PLATFORM" ] || { echo "找不到 android.jar: $PLATFORM"; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "==> aapt2 compile"
"$BT/aapt2" compile --dir "$HERE/res" -o "$OUT/res.zip"

echo "==> aapt2 link (min=$MIN_SDK target=$TARGET_SDK)"
"$BT/aapt2" link \
    -o "$OUT/app-unsigned.apk" \
    -I "$PLATFORM" \
    --manifest "$HERE/AndroidManifest.xml" \
    --java "$OUT/gen" \
    --min-sdk-version "$MIN_SDK" \
    --target-sdk-version "$TARGET_SDK" \
    --version-code 1 \
    --version-name 0.1 \
    "$OUT/res.zip"

echo "==> javac"
find "$HERE/src" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
"$JAVAC" --release 17 -encoding UTF-8 -classpath "$PLATFORM" \
    -d "$OUT/classes" @"$OUT/sources.txt"

echo "==> d8"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
# shellcheck disable=SC2046
# shellcheck disable=SC2046
"$BT/d8" --lib "$PLATFORM" --min-api "$MIN_SDK" --output "$OUT/dex" $(cat "$OUT/classes.txt")
cp "$OUT/dex/classes.dex" "$OUT/"
(cd "$OUT" && zip -q -X app-unsigned.apk classes.dex)


echo "==> 原生二进制（userspace WireGuard: wireproxy）"
if [ -d "$HERE/native" ]; then
    mkdir -p "$OUT/lib"
    cp -r "$HERE/native/." "$OUT/lib/"
    (cd "$OUT" && zip -q -r -X app-unsigned.apk lib)
fi

echo "==> zipalign"
"$BT/zipalign" -f 4 "$OUT/app-unsigned.apk" "$OUT/app-aligned.apk"

if [ ! -f "$KS" ]; then
    echo "==> 生成签名密钥（仅本机使用）"
    "$KEYTOOL" -genkeypair -keystore "$KS" -storepass "$KS_PASS" -keypass "$KS_PASS" \
        -alias recstt -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=RecStt, O=RecStt, C=CN" >/dev/null 2>&1
fi

echo "==> apksigner"
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
    --out "$APK" "$OUT/app-aligned.apk"
"$BT/apksigner" verify --print-certs "$APK" | head -5

echo
echo "==> 产物: $APK ($(du -h "$APK" | cut -f1))"
"$BT/aapt2" dump badging "$APK" | head -4
