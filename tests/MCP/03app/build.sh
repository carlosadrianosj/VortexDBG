#!/bin/bash
# Build the Secure app (03app): a C++ native lib (std::string/std::vector, libc++ linked STATICALLY
# so the emulator needs no libc++_shared.so), the app DEX (Secure), a signed APK, and secure.jar.
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

echo ">>> clang++: libsecure.so (arm64-v8a, C++ std::string/std::vector, static libc++)"
"$CLANGXX" -shared -fPIC -O2 -std=c++17 -static-libstdc++ \
    -o stage/lib/arm64-v8a/libsecure.so jni/secure.cpp
cp stage/lib/arm64-v8a/libsecure.so out/libsecure.so
echo ">>> DT_NEEDED (should NOT list libc++_shared):"
"$NDKBIN/llvm-readelf" -d out/libsecure.so 2>/dev/null | grep NEEDED || true

echo ">>> javac (source/target 8)"
"$JAVAC" -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -d out src/com/example/secure/Secure.java

echo ">>> jar (secure.jar for VortexClassLoader)"
"$JAR" cf out/secure.jar -C out com

echo ">>> d8 (classes.dex)"
"$BT/d8" --release --min-api 21 --lib "$ANDROID_JAR" --output stage $(find out -name '*.class')

echo ">>> aapt package base.apk"
"$BT/aapt" package -f -M AndroidManifest.xml -I "$ANDROID_JAR" -F out/base.apk

echo ">>> aapt add dex + native lib"
( cd stage && "$BT/aapt" add -f "$HERE/out/base.apk" classes.dex lib/arm64-v8a/libsecure.so >/dev/null )

echo ">>> zipalign + apksigner"
"$BT/zipalign" -f -p 4 out/base.apk out/secure-aligned.apk
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias dbg --out out/secure.apk out/secure-aligned.apk

echo ">>> done:"
ls -la out/secure.apk out/secure.jar
"$NDKBIN/llvm-nm" -D --defined-only out/libsecure.so 2>/dev/null | grep -iE 'process|plaintext' || true
