#!/usr/bin/env bash
# Stress test: send 50 notifications rapidly via adb
# Usage: ./stress-test-notifications.sh [count]
# Requires: adb connected to Galaxy S25, Bridge running with notification access

set -euo pipefail

COUNT=${1:-50}
DELAY=0.1  # 100ms between notifications

echo "=== Bridge Notification Stress Test ==="
echo "Sending $COUNT notifications..."
echo "Start: $(date '+%H:%M:%S.%3N')"
echo ""

for i in $(seq 1 "$COUNT"); do
    # Each notification gets unique tag so none replace each other
    /opt/homebrew/share/android-commandlinetools/platform-tools/adb shell "cmd notification post -t 'Stress #$i' 'bridge_stress_$i' 'Test notification $i of $COUNT'"
    printf "\r  Sent: %d/%d" "$i" "$COUNT"
    sleep "$DELAY"
done

echo ""
echo ""
echo "End: $(date '+%H:%M:%S.%3N')"
echo "Done. Verify on Mac:"
echo "  - All $COUNT notifications arrived"
echo "  - No crashes on either device"
echo "  - No duplicate notifications"
echo ""
echo "To clean up notifications on phone:"
echo "  adb shell cmd notification cancel_all"
