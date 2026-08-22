#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

echo "Building debug APK..."
./gradlew :app:assembleDebug

exec ./run_a24.sh
