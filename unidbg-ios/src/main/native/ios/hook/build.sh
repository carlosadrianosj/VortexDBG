#!/usr/bin/env bash
set -e

xcrun -sdk iphoneos clang -o libhook.dylib hook.m objc64.m -shared -lobjc -m64 -arch arm64 -miphoneos-version-min=7.1 -framework Foundation -F. -framework CydiaSubstrate -framework Fishhook && \
mv libhook.dylib ../../../resources/ios/lib/
