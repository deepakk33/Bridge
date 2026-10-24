## Context

Iteration 4 builds on the secure BLE link from iteration 2 and the notification infrastructure from iteration 3. Clipboard sync is the second highest-value feature after notifications (PRD feature catalogue rates both clipboard directions as "High"). The PRD success criteria require "a copied string is pasteable on the other device in under one second."

The two directions have asymmetric difficulty. Mac-to-phone is straightforward: NSPasteboard is readable by any process, and Android can write to its own clipboard from a received packet. Phone-to-Mac is blocked by Android's background clipboard restriction (API 29+), which requires an ADB grant for a personal build.

## Goals / Non-Goals

**Goals:**
- Bidirectional text clipboard sync over the existing BLE link
- Under 1 second latency end-to-end
- Timestamp-based conflict resolution on reconnect
- Graceful degradation without ADB grant (Mac-to-phone still works)
- One-time ADB grant script with verification

**Non-Goals:**
- Rich clipboard (images, files) -- text only in this iteration
- Clipboard history or log
- Cloud clipboard or any network relay
- Automatic ADB grant (always requires a manual one-time step)

## Decisions

### 1. Timestamp rule for reconnect (not sequence numbers)
**Choice:** Each side tracks when its clipboard was last set (epoch milliseconds). On reconnect, both exchange `bridge.clipboard.connect` with content and timestamp. Newer wins, ties are ignored.
**Why:** Without this, two devices reconnecting would overwrite each other's clipboard in a loop. Sequence numbers would require shared state that does not survive a restart. Wall-clock timestamps are available on both platforms and good enough for a single-user tool where both clocks are reasonably accurate. The PRD explicitly calls this out: "The clipboard timestamp rule matters."
**Edge case:** If clocks are skewed by more than a few seconds, the wrong side wins on reconnect. Acceptable for a personal tool where both devices are NTP-synced.

### 2. ADB grant over IME hack
**Choice:** Grant `READ_LOGS` and `READ_CLIPBOARD_IN_BACKGROUND` via `pm grant` over ADB.
**Why:** The PRD lists three options for background clipboard read. Option 2 (ship a no-op IME) hijacks the keyboard and degrades the phone's typing experience. Option 3 (foreground-only) is the fallback, not the primary path. Option 1 (ADB grant) is clean, survives reboots, and is appropriate for a personal build where the developer has ADB access. The PRD says "Option 1 is right for a personal build."
**Trade-off:** Requires one-time USB connection with developer options enabled. Grant is lost on factory reset. The script makes re-granting trivial.

### 3. Text-only first
**Choice:** Only sync clipboard entries that have a plain-text representation (public.utf8-plain-text on Mac, ClipData with text MIME type on Android).
**Why:** Text covers the vast majority of clipboard usage. Images would require out-of-band payload transfer (the control channel cannot carry large binaries efficiently), MIME negotiation, and pasteboard type mapping between platforms. This can be added later if the need proves real.

### 4. Polling on Mac, event-driven on Android
**Choice:** Mac polls NSPasteboard.changeCount every 500ms. Android uses ClipboardManager.OnPrimaryClipChangedListener.
**Why:** NSPasteboard has no notification API for clipboard changes. The changeCount integer increments on every change, and polling it is cheap (one integer comparison). 500ms polling means worst-case latency of 500ms for detection plus BLE transit, well within the 1-second target. Android has a proper listener callback, so no polling is needed there.

### 5. Echo suppression via local-write flag
**Choice:** Each side sets a flag when it writes to the clipboard from a received packet. The clipboard-change handler checks this flag and skips sending if it was a remote-originated write.
**Why:** Without this, writing the Mac's clipboard to Android would trigger Android's clipboard listener, which would send it back to the Mac, creating an infinite loop. The flag is cleared after one polling cycle (Mac) or after the listener callback completes (Android). The duplicate-content check is a second layer of defense.

## Mac Side

### Clipboard Monitor

`ClipboardMonitor` is an actor that polls `NSPasteboard.general.changeCount` on a 500ms timer.

**Flow:**
1. Timer fires every 500ms.
2. Read `NSPasteboard.general.changeCount`. If unchanged, return.
3. If the `isRemoteWrite` flag is set, clear it and return (this change came from a received packet).
4. Read `NSPasteboard.general.string(forType: .string)`. If nil or empty, return.
5. Record the current timestamp as `lastClipboardTimestamp`.
6. Send `bridge.clipboard` packet with the text content via the link manager.

**Receiving:**
1. On `bridge.clipboard` received, extract text.
2. If text matches current pasteboard content, ignore.
3. Set `isRemoteWrite` flag.
4. Call `NSPasteboard.general.clearContents()` then `setString(_:forType:)`.

**Reconnect:**
1. On link established, send `bridge.clipboard.connect` with current pasteboard text and `lastClipboardTimestamp`.
2. On `bridge.clipboard.connect` received, compare timestamps. If remote is newer (and not zero), write to pasteboard with `isRemoteWrite` flag set. If local is newer or equal, ignore.

### Files
- `mac/Bridge/Clipboard/ClipboardMonitor.swift` -- polling loop, change detection, echo suppression
- `mac/Bridge/Clipboard/ClipboardPacketHandler.swift` -- handles incoming `bridge.clipboard` and `bridge.clipboard.connect`

## Android Side

### Clipboard Bridge

`ClipboardBridge` is a component managed by the foreground service. It registers a `ClipboardManager.OnPrimaryClipChangedListener`.

**With ADB grant (full sync):**
1. Listener fires on clipboard change.
2. If `isRemoteWrite` flag is set, clear it and return.
3. Read `clipboardManager.primaryClip`. Extract text from item 0.
4. If null or empty, return.
5. Record timestamp as `lastClipboardTimestamp`.
6. Send `bridge.clipboard` packet to Mac.

**Without ADB grant (degraded):**
1. Listener may fire but `getPrimaryClip()` returns null in the background.
2. When the app is foregrounded or the quick-settings tile is tapped, read the clipboard and send `bridge.clipboard` if content is available.
3. UI shows "Mac to phone only" with instructions for the ADB grant.

**Receiving:**
1. On `bridge.clipboard` received, extract text.
2. If text matches current clip, ignore.
3. Set `isRemoteWrite` flag.
4. Call `clipboardManager.setPrimaryClip(ClipData.newPlainText("Bridge", text))`.

**Reconnect:**
Same as Mac: send `bridge.clipboard.connect` on link established, apply timestamp rule on receipt.

### Permission detection
At startup, `ClipboardBridge` checks whether `READ_CLIPBOARD_IN_BACKGROUND` is granted:
```
context.checkSelfPermission("android.permission.READ_CLIPBOARD_IN_BACKGROUND") == PERMISSION_GRANTED
```
This determines which mode to operate in and what UI to show.

### Files
- `android/Bridge/app/src/main/java/bridge/clipboard/ClipboardBridge.kt` -- listener, echo suppression, grant detection
- `android/Bridge/app/src/main/java/bridge/clipboard/ClipboardPacketHandler.kt` -- handles incoming packets
- `android/Bridge/app/src/main/java/bridge/clipboard/ClipboardTileService.kt` -- quick-settings tile for manual sync

## Protocol Packets

### bridge.clipboard
```json
{"id": 1697300000000, "type": "bridge.clipboard", "body": {"content": "copied text here"}}
```
Sent in both directions when the local clipboard changes. Body contains only `content` (string). No timestamp -- this is a live change, not a reconnect sync.

### bridge.clipboard.connect
```json
{"id": 1697300000000, "type": "bridge.clipboard.connect", "body": {"content": "copied text here", "timestamp": 1697299999500}}
```
Sent by both sides immediately after a link is established. Body contains `content` (string) and `timestamp` (Int64, epoch milliseconds of when this clipboard was last set). Timestamp 0 means unknown and is always treated as older than any real timestamp.

## ADB Grant Script

`scripts/adb-grant-clipboard.sh`:
- Accepts optional `--device <serial>` or `<ip:port>` for wireless ADB
- Accepts `--check` to verify without modifying
- Runs `adb shell pm grant <package> android.permission.READ_LOGS`
- Runs `adb shell pm grant <package> android.permission.READ_CLIPBOARD_IN_BACKGROUND`
- Verifies each grant via `adb shell dumpsys package <package> | grep -E 'READ_LOGS|READ_CLIPBOARD_IN_BACKGROUND'`
- Exits 0 on success, non-zero on any failure with a descriptive message

## Risks / Trade-offs

| Risk | Mitigation |
|------|-----------|
| NSPasteboard polling at 500ms misses rapid copy-paste | 500ms is fast enough for human clipboard use; could reduce to 250ms if needed |
| Clock skew causes wrong side to win on reconnect | Both devices are NTP-synced in practice; for a personal tool this is acceptable |
| ADB grant lost on factory reset without user noticing | App detects missing grant, shows degraded UI, script is in the repo |
| Clipboard content may be sensitive (passwords) | Encrypted channel from iter-2; user opted into this by running Bridge |
| Android clipboard listener may not fire with One UI battery optimization | Bridge foreground service is already Unrestricted in battery settings (iter-1 requirement) |
