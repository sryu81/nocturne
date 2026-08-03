#!/usr/bin/env bash
set -euo pipefail

SDK="$HOME/Android/Sdk"
AVD="Pixel_8"
APK="app/build/outputs/apk/debug/app-debug.apk"
PKG="com.nocturne"

export ANDROID_SDK_ROOT="$SDK"
export PATH="$SDK/emulator:$SDK/platform-tools:$PATH"

# start emulator if none running
if ! adb devices | grep -q "emulator"; then
    echo "Booting $AVD..."
    emulator -avd "$AVD" -gpu host -netdelay none -netspeed full &
    adb wait-for-device
    # wait for full boot
    until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
        sleep 2
    done
    sleep 5
fi

echo "Installing APK..."
adb install -r "$APK"

echo "Launching app..."
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1

echo "Done. Logcat: adb logcat --pid=\$(adb shell pidof $PKG)"
