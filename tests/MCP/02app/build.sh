#!/bin/bash
# Build the Guard app (02app): native libguard.so (arm64, RegisterNatives + Build reads),
# app DEX (Guard), a signed APK bundling both, and guard.jar for Vortex's VortexClassLoader.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"

SDK="$HOME/Library/Android/sdk"
BT="$SDK/build-tools/34.0.0"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
JAVAC="/Applications/JEB-Pro/bin/runtime/bin/javac"
JAR="/Applications/JEB-Pro/bin/runtime/bin/jar"
CLANG="$SDK/ndk/21.4.7075529/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android21-clang"
KEYSTORE="$HERE/debug.keystore"

rm -rf out stage
mkdir -p out stage/lib/arm64-v8a

echo ">>> clang: libguard.so (arm64-v8a)"
"$CLANG" -shared -fPIC -O2 -o stage/lib/arm64-v8a/libguard.so jni/guard.c
cp stage/lib/arm64-v8a/libguard.so out/libguard.so

echo ">>> javac (source/target 8)"
"$JAVAC" -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -d out \
    src/com/example/guard/Device.java \
    src/com/example/guard/Guard.java

echo ">>> jar (guard.jar for VortexClassLoader)"
"$JAR" cf out/guard.jar -C out com

echo ">>> d8 (classes.dex)"
"$BT/d8" --release --min-api 21 --lib "$ANDROID_JAR" --output stage $(find out -name '*.class')

echo ">>> aapt package base.apk"
"$BT/aapt" package -f -M AndroidManifest.xml -I "$ANDROID_JAR" -F out/base.apk

echo ">>> aapt add dex + native lib"
( cd stage && "$BT/aapt" add -f "$HERE/out/base.apk" classes.dex lib/arm64-v8a/libguard.so >/dev/null )

echo ">>> zipalign + apksigner"
"$BT/zipalign" -f -p 4 out/base.apk out/guard-aligned.apk
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias dbg --out out/guard.apk out/guard-aligned.apk

echo ">>> done:"
ls -la out/guard.apk out/guard.jar
unzip -l out/guard.apk | grep -E 'classes.dex|libguard'
