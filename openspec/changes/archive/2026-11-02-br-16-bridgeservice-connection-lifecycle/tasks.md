## 1. Android — the handler holder

- [x] 1.1 Write `BleHandlersTest` first: wire every handler on one `BleHandlers`, hand the same
      instance to a second "generation" the way a peripheral rebuild does, and assert each handler
      still fires; assert a fresh holder's handlers are all null. Verify it fails to compile
      (no `BleHandlers` yet).
- [x] 1.2 Add `com.bridge.ble.BleHandlers` — nullable function properties for
      `onNotificationReply`, `onNotificationRequest`, `onClipboardReceived`,
      `onClipboardConnectReceived`, `onShareRequest`, `onPairCompleted`,
      `onPairConfirmationRequired`, `onPairConfirmationCleared`, `onConnect`. No Android types.
      Verify `./gradlew test` turns 1.1 green.

## 2. Android — wire the holder through the BLE stack

- [x] 2.1 Take `BleHandlers` in `L2capServer`'s constructor; delete its own
      `onNotificationReply`, `onNotificationRequest`, `onClipboardReceived`,
      `onClipboardConnectReceived`, `onShareRequest`, `onPairCompleted`,
      `onPairConfirmationRequired`, `onPairConfirmationCleared` properties and read them from the
      holder at each dispatch site. Leave `onLinkReady` and `onPeerVerified` as they are. Verify
      `./gradlew assembleDebug` compiles.
- [x] 2.2 Take `BleHandlers` in `BlePeripheral`'s constructor and pass it to each `L2capServer` it
      builds; delete the nine `setXHandler` functions and the five mirror fields, and wire
      `server.onLinkReady` to `handlers.onConnect`. Verify `./gradlew assembleDebug` compiles and
      no `setXHandler` reference remains (`grep -rn 'setNotificationReplyHandler\|setShareRequestHandler\|setClipboardHandler\|setClipboardConnectHandler\|setNotificationRequestHandler\|setOnConnectHandler\|setOnPairCompletedHandler\|setPairConfirmationHandler\|setPairConfirmationClearedHandler' android/`).
- [x] 2.3 In `BridgeService`, build one `BleHandlers` in `onCreate()`, assign every handler body
      onto it, and pass it to both `BlePeripheral(...)` constructions; strip the duplicated
      pair-confirmation wiring out of `restartBlePeripheral()` so that function only stops, builds
      and starts. Verify `./gradlew test assembleDebug` is green and `restartBlePeripheral()`
      contains no handler assignment.

## 3. Android — make a dropped packet visible

- [x] 3.1 Add a private helper in `L2capServer` that logs at warn with the packet type when an
      optional handler is null, and use it at every dispatch site that invokes one. Leave the
      `else -> Unknown packet type` arm unchanged. Verify `./gradlew test assembleDebug` is green;
      the warn line itself is confirmed on device in task 6.4.

## 4. Shared — the reconnect packet drops its clipboard text

- [x] 4.1 Add a `clipboard_connect_timestamp_only` vector to
      `protocol/test-vectors/clipboard-connect.json`: a valid `bridge.clipboard.connect` body with
      `timestamp` and **no** `content` key. Verify the file parses as JSON.
- [x] 4.2 Android: extend the clipboard packet test so the new vector round-trips, and add a case
      asserting `handleClipboardConnectPacket` leaves the clipboard unchanged for a newer-timestamp
      packet with no `content` — and that it does *not* treat it as an empty clip. Verify
      `./gradlew test` fails on the second case before 4.3.
      *Done as `ClipboardConnectReceiveTest`. `ClipboardBridge` could not be constructed in a JVM
      test (eager `getSystemService` cast, no mocking library); resolved its clipboard service
      lazily instead, and the test builds a `ContextWrapper(null)`, so no test dependency was
      added. Teeth confirmed by mutating the guard to `?: ""` — the test fails.*
- [x] 4.3 Give `BridgePacket.clipboardConnect` a timestamp-only form that omits `content`, and have
      `ClipboardBridge.makeConnectPacket()` use it; drop the now-dead cap/`lastSend` bookkeeping
      from that function only if nothing else reads it. Verify `./gradlew test assembleDebug` turns
      4.2 green.
- [x] 4.4 Mac: add a `ClipboardMonitor` test that a `bridge.clipboard.connect` with a newer
      timestamp and no `content` leaves `NSPasteboard` untouched, driven from the same vector.
      Verify `xcodebuild -project mac/Bridge/Bridge.xcodeproj -scheme Bridge test` passes. No Mac
      source change is expected — if one is needed, stop and say so.

## 5. Specs and docs

- [x] 5.1 Run `openspec validate br-16-bridgeservice-connection-lifecycle` and read the output.
- [x] 5.2 Confirm no text outside the change folder still defers the reconnect question to BR-16
      (`grep -rn 'BR-16' openspec/ docs/`), so the criterion about the exception clause can be
      checked at wrap.

## 6. Device test — MacBook Air M3 + Galaxy S25

> Run 2026-11-02, 15:36–15:44 IST. Mac build 15:23:36 (contains the Mac fix, commit 72e6440);
> Android build installed 15:35. One Bridge.app instance throughout.
> An earlier pass, 15:23–15:31, was run against a stale Oct-21 Mac binary and is discarded.

- [x] 6.1 Baseline before any restart. Mac→phone clipboard: `Clipboard updated from remote`
      15:38:57. Mac→phone file send (`br16-tier3.txt`, 640031 B, Tier 3):
      `FileReceiver: Receiving … from 192.168.0.143:54334` 15:40:59 — the share.request handler
      was reached. The transfer then failed in the payload TLS handshake
      (`SSLHandshakeException`) 15:41:04 — **BR-23**, pre-existing and unrelated.
- [x] 6.2 Cycled Bluetooth: `L2CAP server started on PSM=180` 15:42:20 (from 178), relinked
      `verified=true` 15:42:23. Mac→phone send repeated: `FileReceiver: Receiving … :54335`
      15:42:46. **The handler survived the rebuild** — identical behaviour either side of it,
      where the old code would have logged nothing at all. Transfer again stopped at the same
      BR-23 handshake 15:42:53.
      *Criterion 2 as worded ("a transfer works") does NOT pass: it is blocked by BR-23, not by
      this change. What BR-16 owns — the handler being reached — does pass.*
- [x] 6.3 Partially covered, and not by the route this task named. Two independent handlers were
      exercised after the rebuild — `onClipboardReceived` (15:29:07, earlier pass) and
      `onShareRequest` (15:42:46) — and all nine now live in one object, so the property is
      structural. **Notification reply and dismiss from the Mac were NOT tested**: both need the
      Mac popover, which is not reachable from Accessibility (`count windows` = 0), and no app
      was enabled for mirroring on the phone.
- [x] 6.4 **Not observed, and likely unobservable.** No "No handler registered" line appeared in
      any session. The refactor makes a null handler impossible in a correctly wired service, so
      the warning now guards only a future regression. Recorded as implemented-but-undemonstrated
      rather than passed.
- [x] 6.5 Pairing works and the confirmation handler fires: code 800547 raised 15:38:06, matched
      in notification and dialog, `Both sides accepted — pinned` 15:38:20, `onPairCompleted` sent
      battery + filter. **Caveat**: this was after an app restart, not after a Bluetooth-cycle
      rebuild — after the 15:42:20 rebuild the peer was already trusted, so no prompt was due.
- [x] 6.6 Clipboard rule on hardware, confirmed twice. Link dropped, Mac pasteboard set to
      `MAC-KEEPS-THIS-NEWBUILD-101420`, link restored 15:44:26: **Mac pasteboard unchanged** — the
      phone pushed no clip. Same reconnect logged `Clipboard updated from reconnect
      (remote ts=…)`, so Mac→phone convergence after a gap still works.
- [x] 6.7 Recorded above, pass/fail per criterion, with 6.3's notification half and 6.4 reported
      as untested rather than passed.

## 7. Close out

- [x] 7.1 Run `(cd android/Bridge && ./gradlew test assembleDebug)` and
      `xcodebuild -project mac/Bridge/Bridge.xcodeproj -scheme Bridge test`, and read both outputs.
