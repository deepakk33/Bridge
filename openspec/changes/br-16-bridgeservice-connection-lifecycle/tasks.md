## 1. Android — the handler holder

- [ ] 1.1 Write `BleHandlersTest` first: wire every handler on one `BleHandlers`, hand the same
      instance to a second "generation" the way a peripheral rebuild does, and assert each handler
      still fires; assert a fresh holder's handlers are all null. Verify it fails to compile
      (no `BleHandlers` yet).
- [ ] 1.2 Add `com.bridge.ble.BleHandlers` — nullable function properties for
      `onNotificationReply`, `onNotificationRequest`, `onClipboardReceived`,
      `onClipboardConnectReceived`, `onShareRequest`, `onPairCompleted`,
      `onPairConfirmationRequired`, `onPairConfirmationCleared`, `onConnect`. No Android types.
      Verify `./gradlew test` turns 1.1 green.

## 2. Android — wire the holder through the BLE stack

- [ ] 2.1 Take `BleHandlers` in `L2capServer`'s constructor; delete its own
      `onNotificationReply`, `onNotificationRequest`, `onClipboardReceived`,
      `onClipboardConnectReceived`, `onShareRequest`, `onPairCompleted`,
      `onPairConfirmationRequired`, `onPairConfirmationCleared` properties and read them from the
      holder at each dispatch site. Leave `onLinkReady` and `onPeerVerified` as they are. Verify
      `./gradlew assembleDebug` compiles.
- [ ] 2.2 Take `BleHandlers` in `BlePeripheral`'s constructor and pass it to each `L2capServer` it
      builds; delete the nine `setXHandler` functions and the five mirror fields, and wire
      `server.onLinkReady` to `handlers.onConnect`. Verify `./gradlew assembleDebug` compiles and
      no `setXHandler` reference remains (`grep -rn 'setNotificationReplyHandler\|setShareRequestHandler\|setClipboardHandler\|setClipboardConnectHandler\|setNotificationRequestHandler\|setOnConnectHandler\|setOnPairCompletedHandler\|setPairConfirmationHandler\|setPairConfirmationClearedHandler' android/`).
- [ ] 2.3 In `BridgeService`, build one `BleHandlers` in `onCreate()`, assign every handler body
      onto it, and pass it to both `BlePeripheral(...)` constructions; strip the duplicated
      pair-confirmation wiring out of `restartBlePeripheral()` so that function only stops, builds
      and starts. Verify `./gradlew test assembleDebug` is green and `restartBlePeripheral()`
      contains no handler assignment.

## 3. Android — make a dropped packet visible

- [ ] 3.1 Add a private helper in `L2capServer` that logs at warn with the packet type when an
      optional handler is null, and use it at every dispatch site that invokes one. Leave the
      `else -> Unknown packet type` arm unchanged. Verify `./gradlew test assembleDebug` is green;
      the warn line itself is confirmed on device in task 6.4.

## 4. Shared — the reconnect packet drops its clipboard text

- [ ] 4.1 Add a `clipboard_connect_timestamp_only` vector to
      `protocol/test-vectors/clipboard-connect.json`: a valid `bridge.clipboard.connect` body with
      `timestamp` and **no** `content` key. Verify the file parses as JSON.
- [ ] 4.2 Android: extend the clipboard packet test so the new vector round-trips, and add a case
      asserting `handleClipboardConnectPacket` leaves the clipboard unchanged for a newer-timestamp
      packet with no `content` — and that it does *not* treat it as an empty clip. Verify
      `./gradlew test` fails on the second case before 4.3.
- [ ] 4.3 Give `BridgePacket.clipboardConnect` a timestamp-only form that omits `content`, and have
      `ClipboardBridge.makeConnectPacket()` use it; drop the now-dead cap/`lastSend` bookkeeping
      from that function only if nothing else reads it. Verify `./gradlew test assembleDebug` turns
      4.2 green.
- [ ] 4.4 Mac: add a `ClipboardMonitor` test that a `bridge.clipboard.connect` with a newer
      timestamp and no `content` leaves `NSPasteboard` untouched, driven from the same vector.
      Verify `xcodebuild -project mac/Bridge/Bridge.xcodeproj -scheme Bridge test` passes. No Mac
      source change is expected — if one is needed, stop and say so.

## 5. Specs and docs

- [ ] 5.1 Run `openspec validate br-16-bridgeservice-connection-lifecycle` and read the output.
- [ ] 5.2 Confirm no text outside the change folder still defers the reconnect question to BR-16
      (`grep -rn 'BR-16' openspec/ docs/`), so the criterion about the exception clause can be
      checked at wrap.

## 6. Device test — MacBook Air M3 + Galaxy S25

> Exclusive: this window holds the devices for the whole of section 6, and no second
> `Bridge.app` runs. Say so before starting and when done.

- [ ] 6.1 Install the debug build, pair phone and Mac, and confirm a Mac→phone file transfer
      completes — the baseline, before any restart.
- [ ] 6.2 Cycle Bluetooth on the phone so the service rebuilds its peripheral (confirm the PSM
      changes in logcat), let the link come back, then send a file Mac→phone again and confirm it
      completes. This is the criterion the bug was found on.
- [ ] 6.3 After the rebuild, confirm the other handlers reach the phone: send a clipboard item
      Mac→phone, reply to a mirrored notification from the Mac, and dismiss one from the Mac.
      Confirm each takes effect on the phone.
- [ ] 6.4 Confirm the no-handler warning is reachable and correctly formatted in logcat — either
      from a genuinely unhandled type seen during the session, or by noting that none occurred and
      reporting the criterion accordingly rather than claiming it passed.
- [ ] 6.5 Unpair and re-pair after a rebuild: confirm the six-digit prompt is raised and the
      pairing notification is posted, so the one handler the old restart path did keep still works.
- [ ] 6.6 Clipboard rule on hardware: copy text on the phone, drop and restore the link, and
      confirm the Mac pasteboard is **unchanged**; then tap the Quick Settings tile and confirm the
      clip arrives. Separately, copy on the Mac, drop and restore the link, and confirm the phone
      still receives it.
- [ ] 6.7 Record each `Done when` criterion pass/fail with what was observed, and report anything
      untested as untested.

## 7. Close out

- [ ] 7.1 Run `(cd android/Bridge && ./gradlew test assembleDebug)` and
      `xcodebuild -project mac/Bridge/Bridge.xcodeproj -scheme Bridge test`, and read both outputs.
