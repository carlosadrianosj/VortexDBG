#!/bin/bash
# Build the SecureVault demo APK: app DEX (SecureVault + TTEncryptUtils) + the real
# libttEncrypt.so (32-bit ARM) bundled under lib/armeabi-v7a/, signed with a debug key.
# Also emits keychain-aes.jar (the app's Java classes) for Vortex's VortexClassLoader.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"

SDK="$HOME/Library/Android/sdk"
BT="$SDK/build-tools/34.0.0"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
JAVAC="/Applications/JEB-Pro/bin/runtime/bin/javac"
JAR="/Applications/JEB-Pro/bin/runtime/bin/jar"
SO_SRC="$HERE/../tiktok-test/libttEncrypt.so"
KEYSTORE="$HERE/debug.keystore"

rm -rf out stage
mkdir -p out stage/lib/armeabi-v7a

echo ">>> javac (source/target 8)"
"$JAVAC" -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -d out \
    src/com/example/aeskeychain/SecureVault.java \
    src/com/bytedance/frameworks/core/encrypt/TTEncryptUtils.java

echo ">>> jar (keychain-aes.jar for VortexClassLoader)"
"$JAR" cf out/keychain-aes.jar -C out com

echo ">>> d8 (classes.dex)"
"$BT/d8" --release --min-api 21 --lib "$ANDROID_JAR" --output stage \
    $(find out -name '*.class')

echo ">>> stage apk contents"
cp "$SO_SRC" stage/lib/armeabi-v7a/libttEncrypt.so

echo ">>> aapt package base.apk"
"$BT/aapt" package -f -M AndroidManifest.xml -I "$ANDROID_JAR" -F out/base.apk

echo ">>> aapt add dex + native lib"
( cd stage && "$BT/aapt" add -f "$HERE/out/base.apk" classes.dex lib/armeabi-v7a/libttEncrypt.so >/dev/null )

echo ">>> zipalign"
"$BT/zipalign" -f -p 4 out/base.apk out/keychain-aes-aligned.apk

echo ">>> apksigner"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias dbg --out out/keychain-aes.apk out/keychain-aes-aligned.apk

echo ">>> done:"
ls -la out/keychain-aes.apk out/keychain-aes.jar
unzip -l out/keychain-aes.apk | grep -E 'classes.dex|libttEncrypt'
