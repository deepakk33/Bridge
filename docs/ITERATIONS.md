# Bridge — Iteration Log

## Iteration 1: BLE Spike & Foundation
**Status**: Complete  
**Branch**: `iter-1/ble-spike`  
**OpenSpec**: `openspec show iter-1-ble-spike`

### Layman Summary
Mac and phone find each other over Bluetooth and open a data channel. Both apps launch (menu bar on Mac, foreground service on Android) and exchange ping/pong messages to prove the link works.

### Technical Summary
BLE L2CAP channel between Mac (CoreBluetooth CBL2CAPChannel) and Android (BluetoothServerSocket insecure L2CAP). Android advertises PSM via GATT characteristic, Mac scans and connects. Newline-delimited JSON framing over the channel. Throughput test sends 50KB of JSON lines.

### Known Issues
- Insecure channel (no encryption) — addressed in iter-2
- No auto-reconnect — addressed in iter-2

---

## Iteration 2: Secure Link Layer
**Status**: Complete  
**Branch**: `iter-2/secure-link`  
**OpenSpec**: `openspec show iter-2-secure-link`

### Layman Summary
Devices pair once with a trust-on-first-use handshake. After pairing, they remember each other and reconnect automatically. All communication is authenticated via certificate fingerprints.

### Technical Summary
Self-signed certificates (Keychain on Mac, AndroidKeyStore on Android), mutual identity exchange with fingerprint verification, trust store persistence. PairingManager handles pair request/accept flow. Ping watchdog detects stale connections and triggers reconnect. Re-pairing supported when peer loses trust store.

### Known Issues
- Mac trust store empties on app restart (mitigated by re-accept logic)

---

## Iteration 3: Notification System
**Status**: Complete  
**Branch**: `iter-3/notifications`  
**OpenSpec**: `openspec show iter-3-notifications`

### Layman Summary
Phone notifications appear on the Mac as native macOS notifications. You can reply to messages directly from the Mac notification. Battery level shows in the Mac menu bar.

### Technical Summary
Android NotificationListenerService captures notifications, serializes to `bridge.notification` packets. Mac presents via UNUserNotificationCenter with action buttons. Reply flow: Mac sends `bridge.notification.reply`, Android delivers via RemoteInput. Battery monitoring via BroadcastReceiver, `bridge.battery` packets.

### Known Issues
- Notification actions limited to reply (no generic action forwarding)

---

## Iteration 4: Clipboard Sync
**Status**: Complete  
**Branch**: `iter-4/clipboard`  
**OpenSpec**: `openspec show iter-4-clipboard`

### Layman Summary
Copy text on either device, it appears on the other within a second. Works automatically when the Bridge app is in the foreground on Android. On reconnect, the most recently copied text wins.

### Technical Summary
Both platforms poll clipboard every 500ms. Mac uses NSPasteboard.general.changeCount. Android uses ClipboardManager with Activity-context reads (Service-context reads return null on Android 16). Echo suppression via `isRemoteWrite` flag prevents ping-pong. Connection-gated sends prevent `lastContent` poisoning before BLE ready. `bridge.clipboard.connect` packet on reconnect carries timestamp for newest-wins comparison (100ms tie threshold, zero always loses). ADB grant script for background clipboard access. Quick Settings tile for manual trigger.

### Known Issues
- Android 16 restricts clipboard reads from Service context — requires app in foreground or manual button tap
- Large clipboard content with embedded newlines can break JSON framing (pre-existing serialization issue)
- Builds on iter-5 for file transfer and permission panel

---

## Iteration 5: File Transfer & Polish
**Status**: Not started  
**Branch**: `iter-5/files-polish`  
**OpenSpec**: `openspec show iter-5-files-polish`

### Layman Summary
_To be written after completion_

### Technical Summary
_To be written after completion_

### Known Issues
_To be written after completion_
