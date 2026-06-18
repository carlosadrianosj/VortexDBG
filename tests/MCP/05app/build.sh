#!/bin/bash
# Build the Faulty app (05app): a C native lib that throws a Java exception via JNI, the app DEX,
# a signed APK, and faulty.jar.
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

echo ">>> clang: libfaulty.so (arm64-v8a)"
"$CLANG" -shared -fPIC -O2 -o stage/lib/arm64-v8a/libfaulty.so jni/faulty.c
cp stage/lib/arm64-v8a/libfaulty.so out/libfaulty.so

echo ">>> javac (source/target 8)"
"$JAVAC" -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -d out src/com/example/faulty/Faulty.java

echo ">>> jar (faulty.jar for VortexClassLoader)"
"$JAR" cf out/faulty.jar -C out com

echo ">>> d8 (classes.dex)"
"$BT/d8" --release --min-api 21 --lib "$ANDROID_JAR" --output stage $(find out -name '*.class')

echo ">>> aapt package base.apk"
"$BT/aapt" package -f -M AndroidManifest.xml -I "$ANDROID_JAR" -F out/base.apk

echo ">>> aapt add dex + native lib"
( cd stage && "$BT/aapt" add -f "$HERE/out/base.apk" classes.dex lib/arm64-v8a/libfaulty.so >/dev/null )

echo ">>> zipalign + apksigner"
"$BT/zipalign" -f -p 4 out/base.apk out/faulty-aligned.apk
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias dbg --out out/faulty.apk out/faulty-aligned.apk

echo ">>> done:"; ls -la out/faulty.apk out/faulty.jar
