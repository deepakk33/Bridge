#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$SCRIPT_DIR/../android/Bridge"

echo "Building Bridge APK..."
cd "$PROJECT_DIR"
./gradlew assembleDebug

APK_PATH="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"

if [ ! -f "$APK_PATH" ]; then
    echo "ERROR: APK not found at $APK_PATH"
    exit 1
fi

echo "Installing on connected device..."
adb install -r "$APK_PATH"

echo "Launching Bridge..."
adb shell am start -n com.bridge.app/.MainActivity

echo "Done."
