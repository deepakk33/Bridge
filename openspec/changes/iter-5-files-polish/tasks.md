## 1. Payload Channel Protocol

- [ ] 1.1 Define `PayloadTransferInfo` struct/data class on both platforms: `port` (Int). Add `payloadSize` (Int64) and `payloadTransferInfo` (PayloadTransferInfo?) fields to `bridge.share.request` packet type. Verify: shared test vector in `protocol/test-vectors/share-request.json` parses correctly on both platforms.
- [ ] 1.2 Implement sender-side TCP server socket on Mac (Network.framework NWListener on ephemeral port). Accepts one connection, serves file bytes, closes. Verify: unit test binds, accepts a loopback connection, serves a 1 KB payload, and tears down.
- [ ] 1.3 Implement sender-side TCP server socket on Android (ServerSocket on ephemeral port via coroutine). Accepts one connection, serves file bytes, closes. Verify: unit test binds, accepts a loopback connection, serves a 1 KB payload, and tears down.
- [ ] 1.4 Implement receiver-side TCP client on Mac. Connects to sender IP + port from `payloadTransferInfo`, reads exactly `payloadSize` bytes, saves to file. Verify: unit test connects to local test server, reads exact byte count, checksum matches.
- [ ] 1.5 Implement receiver-side TCP client on Android. Connects to sender IP + port, reads exactly `payloadSize` bytes, saves to file. Verify: unit test connects to local test server, reads exact byte count, checksum matches.
- [ ] 1.6 Add 30-second inactivity timeout to both TCP client and server. Verify: unit test with stalled connection times out and cleans up within 32 seconds.
- [ ] 1.7 Add `protocol/test-vectors/share-request.json` and `protocol/test-vectors/share-request-no-payload.json`. Verify: both platforms parse both vectors; no-payload vector has absent payloadSize and payloadTransferInfo.

## 2. Bluetooth Classic Transfer (Tier 2)

- [ ] 2.1 Android: create `RfcommTransferServer` that opens a BluetoothServerSocket via `listenUsingRfcommWithServiceRecord` with Bridge file transfer UUID. Accepts one connection, streams file bytes, closes. Verify: nRF Connect or Bluetooth terminal on Mac can connect to the RFCOMM channel.
- [ ] 2.2 Mac: create `RfcommTransferClient` that connects to Android via `IOBluetoothDevice.openRFCOMMChannelAsync` (or equivalent via IOBluetooth framework). Reads file bytes from the channel. Verify: Mac connects and receives a test payload from Android.
- [ ] 2.3 Android: implement outbound RFCOMM client for receiving files sent from Mac. Verify: Android connects to Mac's RFCOMM server and receives a test file.
- [ ] 2.4 Mac: implement RFCOMM server for outbound file sends (Mac to phone). Bind, accept, stream bytes. Verify: unit test serves a file over loopback RFCOMM mock.
- [ ] 2.5 Wire Tier 2 into the payload channel: when `payloadSize < 512000` bytes, use RFCOMM for the TCP-equivalent stream. Verify: sending a 200 KB file uses RFCOMM, not Wi-Fi LAN.
- [ ] 2.6 Measure RFCOMM throughput on real hardware (Mac to S25). Log KB/s for 100 KB, 300 KB, and 500 KB files. Verify: throughput exceeds 100 KB/s for all three sizes.

## 3. Wi-Fi LAN Transfer (Tier 3)

- [ ] 3.1 Android: register mDNS service `_bridge._tcp` via NsdManager when initiating a Tier 3 transfer. Include transfer port in TXT record. Deregister after transfer. Verify: Mac's `dns-sd -B _bridge._tcp` discovers the service.
- [ ] 3.2 Mac: register mDNS service `_bridge._tcp` via NWBrowser/NWListener when initiating a Tier 3 transfer. Verify: Android's NsdManager discovers the service.
- [ ] 3.3 Implement Tier 3 sender on both platforms: bind TCP server on ephemeral port, advertise via mDNS, serve file bytes over the TCP connection. Verify: cross-platform test — Mac sends 1 MB file, Android receives with correct checksum.
- [ ] 3.4 Implement Tier 3 receiver on both platforms: discover mDNS service, resolve to IP + port, connect, read `payloadSize` bytes. Verify: cross-platform test — Android sends 1 MB file, Mac receives with correct checksum.
- [ ] 3.5 Wire Tier 3 into the payload channel: when `payloadSize >= 512000` bytes and both devices share a network, use Wi-Fi LAN TCP. Verify: sending a 2 MB file on the same Wi-Fi uses Tier 3.
- [ ] 3.6 Implement Tier 3 to Tier 2 fallback: when Tier 3 is unavailable (no shared network, mDNS timeout after 5 seconds), fall back to Tier 2 with a user-visible warning. Verify: with Wi-Fi off on one device, a 2 MB file transfers over RFCOMM and a warning is shown.
- [ ] 3.7 Verify Info.plist has `NSLocalNetworkUsageDescription` and `NSBonjourServices` includes `_bridge._tcp`. Verify: Mac prompts for Local Network permission on first Tier 3 transfer attempt.

## 4. File Drop UI

- [ ] 4.1 Mac: add drop target to NSStatusBarButton (menu bar icon). Accept file URLs via NSItemProvider. Verify: dragging a file onto the menu bar icon highlights it and triggers a send.
- [ ] 4.2 Mac: implement send flow — on drop, determine file size, select tier, send `bridge.share.request` over control channel, open payload channel. Show progress in menu bar dropdown. Verify: dropping a 100 KB PNG sends it to the phone and shows progress.
- [ ] 4.3 Mac: implement receive flow — incoming `bridge.share.request` shows a save dialog (or auto-saves to configured directory). Verify: phone sends a file, Mac shows save dialog, file saves correctly.
- [ ] 4.4 Android: implement share intent receiver. Register Bridge as a share target for all file types. On share, send `bridge.share.request` over control channel and serve file via payload channel. Verify: sharing a photo from Gallery to Bridge sends it to the Mac.
- [ ] 4.5 Android: implement receive flow — incoming `bridge.share.request` saves file to Downloads and posts a notification with an "Open" action. Verify: Mac sends a file, phone notification appears, tapping "Open" opens the file.
- [ ] 4.6 Mac: add file picker as secondary send option in menu bar dropdown ("Send File..." menu item). Verify: clicking opens a file picker, selecting a file triggers the same send flow as drag-and-drop.

## 5. Permission Panel

- [ ] 5.1 Mac: create `PermissionChecker` that queries Bluetooth (CBManager.authorization), Notification (UNUserNotificationCenter.current().notificationSettings()), and Local Network (probe connection) status. Verify: unit test returns correct state for each permission.
- [ ] 5.2 Mac: add permission status section to menu bar dropdown. Each permission shows name, status (Granted/Denied/Not Requested), and a Fix/Request button. Verify: UI shows correct states matching System Settings.
- [ ] 5.3 Mac: run permission check at launch. If any permission is denied, show a warning badge on the menu bar icon. Verify: revoking Bluetooth in System Settings, then relaunching, shows the badge and the panel highlights Bluetooth as Denied.
- [ ] 5.4 Android: create `PermissionStatusChecker` that queries Notification Access (NotificationManagerCompat.getEnabledListenerPackages), Battery Optimization (PowerManager.isIgnoringBatteryOptimizations), and ADB clipboard grant. Verify: unit test returns correct state for each.
- [ ] 5.5 Android: add permission status display to the Bridge settings screen. Each item shows name, status, and a button to open the relevant system settings. Verify: UI matches actual permission states.
- [ ] 5.6 Android: check permissions on foreground service start. Post a notification if Notification Access is disabled. Verify: disabling Notification Access and restarting Bridge shows the fix-it notification.

## 6. Watchdog & Heartbeat

- [ ] 6.1 Mac: implement heartbeat timer — send `bridge.ping` if no packet sent or received in 30 seconds. Reset timer on any packet. Verify: idle connection shows a ping every ~30 seconds in packet log.
- [ ] 6.2 Android: implement matching heartbeat timer — send `bridge.ping` if idle for 30 seconds. Verify: idle connection shows pings from both sides.
- [ ] 6.3 Mac: track last-seen timestamp (updated on every received packet). Display in menu bar as relative time ("just now", "Xs ago", "Xm ago"). Verify: menu bar shows updating last-seen time that matches packet log.
- [ ] 6.4 Mac: implement dead connection detection — if no packet received for 90 seconds, mark link as dead. Distinguish from clean BLE disconnect (out of range). Verify: killing the Android service (adb shell am force-stop) triggers "Connection lost" in menu bar within 90 seconds while BLE stays technically connected.
- [ ] 6.5 Mac: display actionable guidance in dropdown when connection is lost. Include "phone service may have been killed" message and instruction to open Bridge on the phone. Verify: force-stopping Android service shows the guidance message.
- [ ] 6.6 Android: track last-seen timestamp from Mac. Update foreground service notification if Mac connection lost (90-second timeout). Verify: turning off Mac Bluetooth, Android notification updates to "Mac connection lost" within 90 seconds.
- [ ] 6.7 Verify recovery: after dead connection, restarting the Android service causes BLE reconnect and menu bar returns to "Connected" with fresh last-seen time. Verify: full cycle — connected, force-stop, dead detected, restart, reconnected — completes within 2 minutes.

## 7. Integration Testing

- [ ] 7.1 Create end-to-end test: pairing two devices, verifying mutual TLS handshake succeeds. Verify: test passes on real hardware (MacBook Air M3 + Galaxy S25).
- [ ] 7.2 Create end-to-end test: notification mirror — send a test notification on the phone, verify it appears in macOS Notification Center within 2 seconds. Verify: test passes with measured latency logged.
- [ ] 7.3 Create end-to-end test: notification reply — reply from Mac notification action, verify reply reaches the phone app within 2 seconds. Verify: test passes with round-trip measured.
- [ ] 7.4 Create end-to-end test: clipboard sync both directions — copy on Mac, verify paste on phone; copy on phone, verify paste on Mac. Each within 1 second. Verify: test passes with latency under 1 second both ways.
- [ ] 7.5 Create end-to-end test: file transfer over Tier 2 — send a 200 KB file from Mac to phone, verify checksum match. Verify: test passes on real hardware.
- [ ] 7.6 Create end-to-end test: file transfer over Tier 3 — send a 5 MB file from Mac to phone on same Wi-Fi, verify checksum match and throughput > 1 MB/s. Verify: test passes on real hardware.
- [ ] 7.7 Create test vectors for all packet types in `protocol/test-vectors/`: identity, pair, notification, notification.reply, clipboard, clipboard.connect, share.request (with and without payload), battery, ping. Verify: both platforms parse all vectors without error.
- [ ] 7.8 Create reconnect stress test: automated 10-cycle walk-away simulation using `adb shell settings put global ble_scan_always_enabled 0/1` to toggle BLE. Verify: at least 9 of 10 reconnects succeed automatically within 15 seconds.
- [ ] 7.9 Run full test suite across all iterations. Verify: all tests pass, no regressions from iter-1 through iter-4.
