#!/bin/bash
# Build the Store app (04app): a C++ native lib with a real linked structure (static libc++),
# the app DEX (Store), a signed APK, and store.jar.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"

SDK="$HOME/Library/Android/sdk"
BT="$SDK/build-tools/34.0.0"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
JAVAC="/Applications/JEB-Pro/bin/runtime/bin/javac"
JAR="/Applications/JEB-Pro/bin/runtime/bin/jar"
NDKBIN="$SDK/ndk/21.4.7075529/toolchains/llvm/prebuilt/darwin-x86_64/bin"
CLANGXX="$NDKBIN/aarch64-linux-android21-clang++"
KEYSTORE="$HERE/debug.keystore"

rm -rf out stage
mkdir -p out stage/lib/arm64-v8a

echo ">>> clang++: libstore.so (arm64-v8a, C++ linked structure, static libc++)"
"$CLANGXX" -shared -fPIC -O2 -std=c++17 -static-libstdc++ \
    -o stage/lib/arm64-v8a/libstore.so jni/store.cpp
cp stage/lib/arm64-v8a/libstore.so out/libstore.so

echo ">>> javac (source/target 8)"
"$JAVAC" -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -d out src/com/example/store/Store.java

echo ">>> jar (store.jar for VortexClassLoader)"
"$JAR" cf out/store.jar -C out com

echo ">>> d8 (classes.dex)"
"$BT/d8" --release --min-api 21 --lib "$ANDROID_JAR" --output stage $(find out -name '*.class')

echo ">>> aapt package base.apk"
"$BT/aapt" package -f -M AndroidManifest.xml -I "$ANDROID_JAR" -F out/base.apk

echo ">>> aapt add dex + native lib"
( cd stage && "$BT/aapt" add -f "$HERE/out/base.apk" classes.dex lib/arm64-v8a/libstore.so >/dev/null )

echo ">>> zipalign + apksigner"
"$BT/zipalign" -f -p 4 out/base.apk out/store-aligned.apk
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias dbg --out out/store.apk out/store-aligned.apk

echo ">>> done:"
ls -la out/store.apk out/store.jar
"$NDKBIN/llvm-nm" -D --defined-only out/libstore.so 2>/dev/null | grep -iE 'build|rootScore|store_head' || true
