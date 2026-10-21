#!/usr/bin/env bash
set -euo pipefail

PACKAGE="com.bridge.app"

usage() {
    cat <<EOF
Usage: $(basename "$0") [OPTIONS]

Grant ADB permissions for Bridge clipboard background access.

On Android 16+, uses appops to allow background clipboard read.
On older versions, uses pm grant for READ_CLIPBOARD_IN_BACKGROUND.

Options:
  --device <serial>   Target a specific device (adb -s <serial>)
  --check             Check grant status without modifying
  --help              Show this help

Examples:
  $(basename "$0")                        # Grant via USB
  $(basename "$0") --device 192.168.1.5:5555  # Grant via wireless ADB
  $(basename "$0") --check                # Verify grant status
EOF
}

ADB_DEVICE_FLAG=()
CHECK_ONLY=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --device)
            shift
            ADB_DEVICE_FLAG=(-s "$1")
            shift
            ;;
        --check)
            CHECK_ONLY=true
            shift
            ;;
        --help)
            usage
            exit 0
            ;;
        *)
            echo "Error: unknown option '$1'"
            usage
            exit 1
            ;;
    esac
done

adb_cmd() {
    adb ${ADB_DEVICE_FLAG[@]+"${ADB_DEVICE_FLAG[@]}"} "$@"
}

check_adb_device() {
    if ! adb_cmd get-state >/dev/null 2>&1; then
        echo "Error: No ADB device found."
        echo ""
        echo "To connect:"
        echo "  USB:      Enable Developer Options > USB Debugging, then plug in"
        echo "  Wireless: Enable Wireless ADB, then run: adb connect <ip>:<port>"
        exit 1
    fi
}

check_package_installed() {
    if ! adb_cmd shell pm list packages 2>/dev/null | grep -q "package:${PACKAGE}"; then
        echo "Error: Package '${PACKAGE}' is not installed on the device."
        echo "Install Bridge first, then re-run this script."
        exit 1
    fi
}

get_api_level() {
    adb_cmd shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r'
}

check_clipboard_appops() {
    local result
    result=$(adb_cmd shell appops get "$PACKAGE" READ_CLIPBOARD 2>/dev/null | tr -d '\r')
    if echo "$result" | grep -q "allow"; then
        return 0
    fi
    return 1
}

check_read_logs() {
    local dump
    dump=$(adb_cmd shell dumpsys package "$PACKAGE" 2>/dev/null)
    if echo "$dump" | grep -q "READ_LOGS.*granted=true"; then
        return 0
    fi
    return 1
}

echo "Bridge Clipboard ADB Grant"
echo "=========================="
echo ""

check_adb_device
check_package_installed

API_LEVEL=$(get_api_level)
echo "Device API level: $API_LEVEL"
echo ""

if $CHECK_ONLY; then
    echo "Permission status for ${PACKAGE}:"
    echo ""

    if check_read_logs; then
        echo "  ✓ READ_LOGS: granted"
    else
        echo "  - READ_LOGS: not granted (optional)"
    fi

    if check_clipboard_appops; then
        echo "  ✓ READ_CLIPBOARD: allowed"
    else
        echo "  ✗ READ_CLIPBOARD: NOT allowed"
    fi

    if check_clipboard_appops; then
        echo ""
        echo "Clipboard background access granted. Full sync active."
        exit 0
    else
        echo ""
        echo "Clipboard background access missing. Run without --check to grant."
        exit 1
    fi
fi

echo "Granting clipboard background access..."
echo ""

# READ_LOGS: try pm grant (works on most versions, harmless if fails)
if adb_cmd shell pm grant "$PACKAGE" android.permission.READ_LOGS 2>/dev/null; then
    echo "  Granted: READ_LOGS"
else
    echo "  Skipped: READ_LOGS (not required on API $API_LEVEL)"
fi

# Clipboard: use appops (works on Android 16+ and older)
adb_cmd shell appops set "$PACKAGE" READ_CLIPBOARD allow
echo "  Granted: READ_CLIPBOARD (appops)"

echo ""
echo "Verifying..."
echo ""

if check_clipboard_appops; then
    echo "  ✓ READ_CLIPBOARD: confirmed"
    echo ""
    echo "Success! Clipboard background access granted."
    echo "Phone-to-Mac clipboard sync is now active."
    echo ""
    echo "Note: Grant survives reboots but is lost on factory reset."
else
    echo "  ✗ READ_CLIPBOARD: verification failed"
    echo ""
    echo "Error: Could not verify clipboard access."
    exit 1
fi
