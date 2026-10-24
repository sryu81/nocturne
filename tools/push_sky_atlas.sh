#!/usr/bin/env bash
# Loads the sky atlas level the APK doesn't carry (order 5, ~760 MB) from the laptop mirror into
# the app's storage on the phone, over USB. Orders 0..4 are packaged in the APK (app/build.gradle.kts).
# Survives app updates; an uninstall removes it (run this again).
#
# Usage: tools/push_sky_atlas.sh [MIRROR_DIR]   (default ~/astro-data/hips/DSSColor)
set -euo pipefail

SDK="$HOME/Android/Sdk"
DEVICE="R59W50301JM"
PKG="com.nocturne"
SRC="${1:-$HOME/astro-data/hips/DSSColor}"
DEST="/sdcard/Android/data/$PKG/files/hips/DSSColor"
ORDER=5

export PATH="$SDK/platform-tools:$PATH"

[ -d "$SRC/Norder$ORDER" ] || { echo "No $SRC/Norder$ORDER — run pi-tools/hips-mirror/hips_mirror.py first" >&2; exit 1; }
count=$(find "$SRC/Norder$ORDER" -name 'Npix*.jpg' | wc -l)
[ "$count" -eq 12288 ] || echo "Warning: only $count of 12288 order-$ORDER tiles in the mirror" >&2

if ! adb devices | grep -q "^$DEVICE[[:space:]]*device$"; then
    echo "$DEVICE not connected/authorized." >&2
    exit 1
fi

adb -s "$DEVICE" shell mkdir -p "$DEST"
echo "Pushing $count order-$ORDER tiles to $DEVICE…"
adb -s "$DEVICE" push --sync "$SRC/Norder$ORDER" "$DEST/"
echo "Done. Open Gear → Rig maintenance → Sky atlas in the app to re-scan."
