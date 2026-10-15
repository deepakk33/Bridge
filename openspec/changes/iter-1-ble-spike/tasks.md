## 1. Project Setup

- [ ] 1.1 Create Xcode project at `mac/Bridge/` as a macOS menu bar app (SwiftUI lifecycle, no dock icon). Verify: project builds and shows a menu bar icon on launch.
- [ ] 1.2 Create Gradle project at `android/Bridge/` targeting API 36 with Kotlin. Verify: `./gradlew assembleDebug` succeeds.
- [ ] 1.3 Create self-signed code signing certificate in Keychain Access (10-year expiry, name "Bridge Dev"). Sign the Xcode project with it. Verify: app launches after rebuild without Gatekeeper re-prompt.
- [ ] 1.4 Create `protocol/test-vectors/` with `ping.json` containing a sample bridge.ping packet. Verify: file is valid JSON and both platforms can read it in tests.
- [ ] 1.5 Add `scripts/adb-install.sh` that builds and installs the Android APK via ADB. Verify: script installs app on connected S25.

## 2. Android BLE Peripheral

- [ ] 2.1 Add manifest permissions: BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT, FOREGROUND_SERVICE, FOREGROUND_SERVICE_CONNECTED_DEVICE. Verify: app installs and requests Bluetooth permissions on first launch.
- [ ] 2.2 Create `BridgeService` foreground service with type `connectedDevice`. Show persistent notification "Bridge active". Verify: service starts when app opens and survives backgrounding. Check via `adb shell dumpsys activity services | grep Bridge`.
- [ ] 2.3 Start BLE advertising with Bridge service UUID from the foreground service. Verify: Mac's Bluetooth Explorer or `nRF Connect` sees the Bridge service.
- [ ] 2.4 Add GATT server with two characteristics: identity (read, returns JSON with deviceId/deviceName/deviceType) and PSM (read, returns L2CAP channel PSM number). Verify: nRF Connect can read both characteristics.
- [ ] 2.5 Open L2CAP server socket (BluetoothServerSocket via createL2capChannel or listenUsingL2capChannel). Write PSM to the GATT characteristic. Verify: PSM value is a valid integer in the dynamic range.
- [ ] 2.6 Accept L2CAP connections. Read newline-delimited JSON from the stream. Log received packets. Echo back a ping response. Verify: unit test with mock BluetoothSocket parses JSON correctly.

## 3. Mac BLE Central

- [ ] 3.1 Add NSBluetoothAlwaysUsageDescription to Info.plist. Create `BLEManager` class wrapping CBCentralManager. Verify: Bluetooth permission prompt appears on first launch.
- [ ] 3.2 Scan for Bridge service UUID. On discovery, issue `connect()` (pending, no timeout). Verify: Mac discovers the Android peripheral when it is advertising.
- [ ] 3.3 On connect, discover services, read identity characteristic, log device info. Verify: Mac logs the Android device name and ID.
- [ ] 3.4 Read PSM characteristic. Open L2CAP channel to that PSM using `CBPeripheral.openL2CAPChannel()`. Verify: channel opens successfully (delegate callback fires).
- [ ] 3.5 Send and receive newline-delimited JSON over the L2CAP channel. Implement a simple stream parser that buffers until newline. Verify: ping packet sent from Mac is echoed by Android.
- [ ] 3.6 Update menu bar icon based on connection state: disconnected (grey), connected (blue), Bluetooth off (strikethrough). Verify: icon changes when phone connects and disconnects.

## 4. Protocol Foundation

- [ ] 4.1 Define `BridgePacket` struct/data class on both platforms: `id` (Int64 millis), `type` (String), `body` (JSON object). Verify: both platforms serialize and deserialize the same test vector identically.
- [ ] 4.2 Implement `bridge.ping` handler on both sides. Ping carries no body. Verify: Mac sends ping, Android receives and logs it; Android sends ping, Mac receives and logs it.
- [ ] 4.3 Create `protocol/test-vectors/ping.json` and `protocol/test-vectors/malformed.json`. Both test suites load and validate against them. Verify: `swift test` and `./gradlew test` both pass using shared vectors.

## 5. Spike Validation

- [ ] 5.1 Throughput test: Mac sends 50KB of JSON lines to Android, measure wall time. Log KB/s. Verify: throughput exceeds 30 KB/s.
- [ ] 5.2 Reconnect test: with both devices connected, carry the phone out of BLE range (~10m or next room). Wait for disconnect. Return. Verify: connection re-establishes automatically within 10 seconds of return, no user action needed.
- [ ] 5.3 Run reconnect test 10 times. Log success/failure. Verify: at least 9 of 10 reconnects succeed automatically.
- [ ] 5.4 Battery impact: leave connected for 1 hour with idle ping every 30 seconds. Check phone battery drain. Verify: drain is under 2% per hour with screen off.
- [ ] 5.5 Document results in `docs/spike-results.md`: throughput, reconnect reliability, battery drain, any surprises. Verify: file exists and contains all four measurements.
