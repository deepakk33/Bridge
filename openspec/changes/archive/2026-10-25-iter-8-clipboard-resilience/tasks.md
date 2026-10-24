## 1. Clipboard resilience
- [x] 1.1 Attempt and empirically reject the `SYSTEM_ALERT_WINDOW` overlay on One UI 8.5; revert it
- [x] 1.2 Cache last Mac clip on denied background write (`ClipboardBridge.pendingRemoteContent`)
- [x] 1.3 Re-apply pending clip from Activity context on focus (`flushPendingRemote`)
- [x] 1.4 Wire flush into `MainActivity.onWindowFocusChanged`
- [x] 1.5 Update clipboard status text to the manual reality

## 2. Link handshake on open
- [x] 2.1 Expose `PingWatchdog.lastPacketAt`
- [x] 2.2 Add `L2capServer.probeLink()` (ping + 3 s timeout → close dead socket)
- [x] 2.3 Plumb `BlePeripheral.probeLink` → `BridgeService.requestHandshake()`
- [x] 2.4 Invoke from `MainActivity.onResume` when a socket is open
- [x] 2.5 Hardware-verify: dead link on open drops to Advertising and reconnects

## 3. Service persistence
- [x] 3.1 Add `android:stopWithTask="false"` to `BridgeService` manifest entry
