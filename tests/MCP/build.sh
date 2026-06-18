#!/bin/bash
# Build the McpDemo app: compile the native libvault.so (arm64), the app DEX (Vault + Device),
# and a signed APK that bundles both, plus mcpdemo.jar for Vortex's VortexClassLoader.
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

echo ">>> clang: libvault.so (arm64-v8a)"
"$CLANG" -shared -fPIC -O2 -o stage/lib/arm64-v8a/libvault.so jni/vault.c
cp stage/lib/arm64-v8a/libvault.so out/libvault.so

echo ">>> javac (source/target 8)"
"$JAVAC" -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -d out \
    src/com/example/mcpdemo/Device.java \
    src/com/example/mcpdemo/Vault.java

echo ">>> jar (mcpdemo.jar for VortexClassLoader)"
"$JAR" cf out/mcpdemo.jar -C out com

echo ">>> d8 (classes.dex)"
"$BT/d8" --release --min-api 21 --lib "$ANDROID_JAR" --output stage $(find out -name '*.class')

echo ">>> aapt package base.apk"
"$BT/aapt" package -f -M AndroidManifest.xml -I "$ANDROID_JAR" -F out/base.apk

echo ">>> aapt add dex + native lib"
( cd stage && "$BT/aapt" add -f "$HERE/out/base.apk" classes.dex lib/arm64-v8a/libvault.so >/dev/null )

echo ">>> zipalign + apksigner"
"$BT/zipalign" -f -p 4 out/base.apk out/mcpdemo-aligned.apk
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias dbg --out out/mcpdemo.apk out/mcpdemo-aligned.apk

echo ">>> done:"
ls -la out/mcpdemo.apk out/mcpdemo.jar
unzip -l out/mcpdemo.apk | grep -E 'classes.dex|libvault'
echo ">>> exports:"
"$SDK/ndk/21.4.7075529/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-nm" -D --defined-only out/libvault.so 2>/dev/null | grep -i 'vault' || true
