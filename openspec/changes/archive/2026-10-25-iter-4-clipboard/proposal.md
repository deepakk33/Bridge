## Why

Clipboard sync is the second most-wanted feature after notifications. The PRD success criteria require that "a copied string is pasteable on the other device in under one second." The two directions have asymmetric difficulty: Mac-to-phone needs no special permission, but phone-to-Mac is blocked by Android's background clipboard restriction (PRD: "Clipboard is read-blocked in the background"). This iteration handles both directions and the reconnect timestamp logic that prevents clipboard ping-pong.

## What Changes

**Protocol packets implemented:**
- `bridge.clipboard` (both directions) -- text content of the clipboard.
- `bridge.clipboard.connect` (both directions) -- text content plus a timestamp, sent on every reconnect. The receiver ignores content whose timestamp is older than its own (PRD: "The clipboard timestamp rule matters").

**Mac changes:**
- Monitors `NSPasteboard` for changes (polling general pasteboard change count). On change, sends `bridge.clipboard` with the text content to Android.
- Receives `bridge.clipboard` from Android and writes to `NSPasteboard`. No special permission needed.
- On reconnect, sends `bridge.clipboard.connect` with current clipboard content and timestamp. Receives Android's `bridge.clipboard.connect` and applies the timestamp rule: newer wins, ties are ignored.

**Android changes:**
- **With ADB grant (full sync):** `READ_LOGS` and `READ_CLIPBOARD_IN_BACKGROUND` permissions granted via `pm grant` over ADB (PRD: "Option 1 is right for a personal build"). Foreground service monitors clipboard changes and sends `bridge.clipboard` to Mac.
- **Without ADB grant (degraded mode):** Mac-to-phone direction works normally. Phone-to-Mac is only available when the Bridge app is in the foreground or triggered from a quick-settings tile. UI clearly indicates the degraded state and provides instructions for the ADB grant.
- On reconnect, sends `bridge.clipboard.connect` with current clipboard and timestamp.

**ADB grant tooling:**
- Shell script in the repo to run the two `pm grant` commands over ADB. Documented with instructions for USB and wireless ADB.
- The grant survives reboots but is lost on factory reset (PRD: "ADB clipboard grant lost when the phone is replaced"). Script includes a check to verify the grant is active.

**Timestamp ping-pong prevention:**
- On reconnect, both sides exchange `bridge.clipboard.connect` with their current clipboard and the time it was last set.
- The receiver compares timestamps. If the incoming timestamp is newer, it overwrites the local clipboard. If older or equal, it is ignored.
- Without this rule, two devices reconnecting would overwrite each other's clipboard indefinitely (PRD: "Versioning" / clipboard timestamp rule).

## Capabilities

### New Capabilities

- **`clipboard/sync`** -- Bidirectional clipboard sync for text content. Mac-to-phone works with no special permissions. Phone-to-Mac requires ADB grant or falls back to foreground-only mode. Target latency: under 1 second (PRD success criteria). Reconnect uses timestamp comparison to prevent ping-pong.
- **`clipboard/adb-grant`** -- Shell script and documentation for granting `READ_LOGS` and `READ_CLIPBOARD_IN_BACKGROUND` via ADB. Includes a verification check. Documented that the grant survives reboots but not factory resets.

### Modified Capabilities

None. Uses the secure channel from iter-2 without modification.

## Impact

- **ADB grant is the main friction point.** Requires a one-time USB connection and developer options enabled. The grant survives reboots but is lost on factory reset or phone replacement. The re-grant script is included in the repo (PRD risk mitigation).
- **Graceful degradation without ADB grant.** Phone-to-Mac clipboard is unavailable in the background, but Mac-to-phone works normally. This matters when the phone is replaced and the grant has not been re-applied.
- **Text only in v1.** Rich clipboard content (images, files) is out of scope. Text covers the vast majority of clipboard usage.
- **Privacy consideration:** Every clipboard change is sent over the link. The secure channel from iter-2 ensures this is encrypted, but users should be aware that clipboard content crosses devices automatically.
