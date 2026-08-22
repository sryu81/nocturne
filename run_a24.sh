#!/usr/bin/env bash
set -euo pipefail

SDK="$HOME/Android/Sdk"
DEVICE="R59W50301JM"
APK="app/build/outputs/apk/debug/app-debug.apk"
PKG="com.nocturne"

export ANDROID_SDK_ROOT="$SDK"
export PATH="$SDK/platform-tools:$PATH"

if ! adb devices | grep -q "^$DEVICE[[:space:]]*device$"; then
    echo "$DEVICE not connected/authorized. Plug it in and accept the USB debugging prompt." >&2
    adb devices
    exit 1
fi

echo "Installing APK on $DEVICE..."
adb -s "$DEVICE" install -r "$APK"

echo "Launching app..."
adb -s "$DEVICE" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1

echo "Done. Logcat: adb -s $DEVICE logcat --pid=\$(adb -s $DEVICE shell pidof $PKG)"
