## 1. Mac Clipboard Monitor

- [ ] 1.1 Create `mac/Bridge/Clipboard/ClipboardMonitor.swift` as a Swift actor that polls `NSPasteboard.general.changeCount` on a 500ms timer. Verify: actor compiles, timer fires in unit test with mock pasteboard.
- [ ] 1.2 Implement change detection: compare changeCount, read `string(forType: .string)`, skip if nil/empty or unchanged. Verify: unit test copies text to pasteboard, monitor detects the change within one polling cycle.
- [ ] 1.3 Add `isRemoteWrite` flag. When set, the monitor skips one change detection cycle and clears the flag. Verify: unit test sets flag, writes to pasteboard, confirms no outbound packet is generated.
- [ ] 1.4 Track `lastClipboardTimestamp` (epoch milliseconds) updated on every local clipboard change detected. Verify: timestamp is non-zero after a clipboard change and monotonically increasing.
- [ ] 1.5 Wire `ClipboardMonitor` into the link manager: on change detected, call send with a `bridge.clipboard` packet. Verify: integration test confirms packet is queued for sending when clipboard changes.

## 2. Android Clipboard Bridge

- [ ] 2.1 Create `ClipboardBridge.kt` in the clipboard package. Register `OnPrimaryClipChangedListener` from the foreground service. Verify: listener callback fires when clipboard changes (tested with `adb shell am broadcast` or manual copy).
- [ ] 2.2 Implement ADB grant detection: check `READ_CLIPBOARD_IN_BACKGROUND` permission at startup. Store result in `hasBackgroundClipboardAccess`. Verify: returns true when grant is active, false when revoked (test with `pm revoke`).
- [ ] 2.3 In the listener callback, read `primaryClip` text, skip if `isRemoteWrite` flag set or content is null/empty/unchanged. Send `bridge.clipboard` packet. Verify: unit test with mock ClipboardManager confirms packet sent on real change, suppressed on echo.
- [ ] 2.4 Implement degraded mode: when grant is absent, `getPrimaryClip()` returns null in background. Log the miss. Do not send a packet. Verify: with grant revoked and app backgrounded, no clipboard packets are sent.
- [ ] 2.5 Create `ClipboardTileService.kt` extending TileService for the quick-settings tile. On click, read clipboard (allowed in this context) and send `bridge.clipboard` to Mac. Verify: tile appears in quick settings, tapping it sends the current clipboard text.
- [ ] 2.6 Add degraded-mode UI to the Bridge settings screen: show "Mac to phone only" when grant is absent, "Both directions" when present. Include brief instructions pointing to the ADB grant script. Verify: UI reflects actual grant state on launch.

## 3. Protocol Packets

- [ ] 3.1 Define `bridge.clipboard` packet codec on Mac (Swift): serialize `{"id":..., "type":"bridge.clipboard", "body":{"content":"..."}}`. Verify: output matches `protocol/test-vectors/clipboard.json`.
- [ ] 3.2 Define `bridge.clipboard` packet codec on Android (Kotlin): same format. Verify: output matches the same test vector.
- [ ] 3.3 Define `bridge.clipboard.connect` packet codec on Mac: includes `body.timestamp` (Int64). Verify: output matches `protocol/test-vectors/clipboard-connect.json`.
- [ ] 3.4 Define `bridge.clipboard.connect` packet codec on Android: same format. Verify: output matches the same test vector.
- [ ] 3.5 Create shared test vectors: `protocol/test-vectors/clipboard.json` and `protocol/test-vectors/clipboard-connect.json`. Include at least one normal case and one edge case (empty content, timestamp zero). Verify: files are valid JSON, both platform test suites load and parse them.

## 4. ADB Grant Script

- [ ] 4.1 Create `scripts/adb-grant-clipboard.sh`. Accept optional `--device <serial>` for targeting a specific device. Verify: script is executable and prints usage when run with `--help`.
- [ ] 4.2 Implement grant logic: run `adb shell pm grant <package> android.permission.READ_LOGS` and `adb shell pm grant <package> android.permission.READ_CLIPBOARD_IN_BACKGROUND`. Verify: both permissions granted on connected S25.
- [ ] 4.3 Implement verification: after granting, run `adb shell dumpsys package <package>` and grep for both permissions in the "granted=true" state. Verify: script prints success summary with both permissions confirmed.
- [ ] 4.4 Implement `--check` mode: verify grant status without modifying. Verify: `--check` reports correct state when grant is present and when it is absent.
- [ ] 4.5 Handle error cases: no ADB device found, Bridge package not installed, permission already granted (idempotent). Verify: each error prints a descriptive message and exits non-zero.

## 5. Timestamp Logic

- [ ] 5.1 Implement `bridge.clipboard.connect` send on link established (Mac side): include current pasteboard text and `lastClipboardTimestamp`. Verify: packet is sent within 1 second of connection established callback.
- [ ] 5.2 Implement `bridge.clipboard.connect` send on link established (Android side): include current clipboard text and `lastClipboardTimestamp`. Verify: packet is sent within 1 second of connection.
- [ ] 5.3 Implement timestamp comparison on Mac: on receiving `bridge.clipboard.connect`, overwrite pasteboard only if remote timestamp is strictly newer and non-zero. Verify: unit test with older remote timestamp confirms no overwrite; test with newer timestamp confirms overwrite.
- [ ] 5.4 Implement timestamp comparison on Android: same logic. Verify: same test pattern as 5.3.
- [ ] 5.5 Implement tie handling: timestamps within 100ms of each other are treated as equal, neither side overwrites. Verify: unit test with timestamps 50ms apart confirms no overwrite on either side.
- [ ] 5.6 Implement zero-timestamp handling: timestamp 0 means unknown and always loses. Verify: unit test confirms a side with timestamp 0 never overwrites the other side regardless of the other timestamp value.

## 6. Testing

- [ ] 6.1 Mac unit tests for `ClipboardMonitor`: change detection, echo suppression, timer lifecycle. Verify: `swift test` passes.
- [ ] 6.2 Android unit tests for `ClipboardBridge`: listener callback, echo suppression, grant detection, degraded mode. Verify: `./gradlew test` passes.
- [ ] 6.3 Cross-platform test vector validation: both sides serialize and deserialize `clipboard.json` and `clipboard-connect.json` identically. Verify: shared vectors pass on both platforms.
- [ ] 6.4 Manual test: copy text on Mac, paste on S25. Measure latency with stopwatch. Verify: pasteable within 1 second.
- [ ] 6.5 Manual test: copy text on S25 (ADB grant active), paste on Mac. Measure latency. Verify: pasteable within 1 second.
- [ ] 6.6 Manual test: copy text on S25 without ADB grant, confirm Mac does not receive it. Open Bridge app, tap sync or use quick-settings tile, confirm Mac receives it. Verify: degraded mode works as specified.
- [ ] 6.7 Reconnect test: disconnect BLE (walk away), copy new text on Mac, return. Verify: after reconnect, the newer clipboard wins and no ping-pong occurs (check logs for at most one clipboard write).
- [ ] 6.8 Reconnect test: disconnect, copy on both devices, return. Verify: the device with the more recent copy wins; the other does not overwrite back.
- [ ] 6.9 ADB grant script test: run `scripts/adb-grant-clipboard.sh` on S25 via USB. Verify: both permissions granted, `--check` confirms, phone-to-Mac sync works immediately after.
- [ ] 6.10 ADB grant survival test: grant permissions, reboot phone, verify grant persists and clipboard sync resumes. Verify: `--check` still reports granted after reboot.
