## Why

The entire Bridge project rests on one assumption: BLE L2CAP can carry newline-delimited JSON between Swift and Kotlin fast enough for clipboard and notification payloads. If that assumption is wrong, every later iteration is wasted effort. This iteration exists to answer three spike questions from the PRD (Phase 1): Can a Swift central open an L2CAP channel to a Kotlin peripheral and pass a JSON line both ways? Does a pending connect re-establish itself when the phone leaves and returns? And what sustained throughput does the channel actually deliver?

Secondary goal: stand up the project skeletons on both platforms so that later iterations have something to build on rather than starting from zero.

## What Changes

**Mac (new project root `/mac`):**
- Swift 6 menu bar app skeleton using SwiftUI. Registered via `SMAppService` for launch-at-login (PRD: Lifecycle section).
- Core Bluetooth central role: scan for the Bridge service UUID, connect, read the identity characteristic.
- Open an L2CAP PSM channel on connect. Send and receive newline-delimited JSON lines.
- Measure throughput: bytes/second over the L2CAP channel logged to console.
- Pending connect: issue a connect with no timeout so the system handles reconnect automatically (PRD: "Losing range and getting it back").
- Self-signed code signing certificate created in Keychain Access (PRD: "Signing, without paying Apple").

**Android (new project root `/android`):**
- Kotlin foreground service of type `connectedDevice` (PRD: "Foreground service type is mandatory and policed").
- `BluetoothLeAdvertiser` advertising the Bridge service UUID whenever disconnected.
- L2CAP server socket (`createL2capChannel`) accepting connections from the Mac.
- Send and receive newline-delimited JSON lines over the channel.
- Throughput measurement: timed transfer of a known payload size, logged.

**Shared:**
- Bridge service UUID defined and documented.
- Minimal JSON line format for the spike: `{"id": <timestamp>, "type": "bridge.ping", "body": {}}`.
- Test procedure: connect, exchange pings, measure throughput, walk away, walk back, confirm reconnect.

## Capabilities

### New Capabilities

- **`ble-transport/discovery`** -- Mac scans for and discovers the Android peripheral by Bridge UUID. Android advertises whenever it is not connected. Discovery works with Wi-Fi off on both devices.
- **`ble-transport/l2cap-channel`** -- Bidirectional L2CAP channel carrying newline-delimited JSON. Supports pending connect for automatic reconnection on range restoration. Throughput measured and logged (target: 50-100 KB/s per PRD transport ladder).
- **`app-skeleton/mac-menubar`** -- Swift 6 / SwiftUI menu bar app. Signed with self-signed certificate. Registered with `SMAppService` for login item. Info.plist carries `NSBluetoothAlwaysUsageDescription`.
- **`app-skeleton/android-service`** -- Kotlin foreground service (`connectedDevice` type). Handles BLE peripheral role. Starts from foreground activity context to avoid `ForegroundServiceStartNotAllowedException`.

### Modified Capabilities

None. This is the first iteration.

## Impact

- Creates `/mac` and `/android` project roots in the repo. All subsequent iterations build on these.
- No protocol security yet -- the L2CAP channel is unencrypted and unauthenticated. Iter-2 adds TLS and pairing.
- No user-facing features yet -- this is infrastructure validation only.
- Requires Bluetooth permission grant on Mac (one-time TCC prompt) and Bluetooth/location permissions on Android.
- The throughput measurement directly gates iter-3 and iter-4: if L2CAP sustains less than ~20 KB/s, clipboard sync will feel sluggish and the transport ladder may need revision.
- The pending-connect test directly gates iter-2: if automatic reconnect does not work reliably, a manual reconnect mechanism must be designed.
