## 1. Certificate Generation

- [ ] 1.1 Mac: Implement `CertificateManager` that generates a self-signed X.509 certificate (P-256 EC key) on first launch and stores it in macOS Keychain with a Bridge-specific label. Verify: unit test that generates a cert, retrieves it from Keychain, and confirms the fingerprint matches across calls.
- [ ] 1.2 Mac: Add method to `CertificateManager` that returns the SHA-256 fingerprint of the stored certificate as a hex string. Verify: unit test comparing computed fingerprint against OpenSSL-generated reference.
- [ ] 1.3 Android: Implement `CertificateManager` that generates a self-signed X.509 certificate with private key in Android Keystore on first launch. Verify: instrumented test that generates a cert, retrieves it, and confirms the fingerprint is stable across app restarts.
- [ ] 1.4 Android: Store the certificate (public part) in app-private storage and expose the SHA-256 fingerprint. Verify: instrumented test confirming fingerprint survives process death.
- [ ] 1.5 Both: Add certificate generation to the app startup sequence (generate if absent, load if present). Verify: launch app twice on each platform; second launch reuses existing cert, not a new one.

## 2. Identity & Pairing Protocol

- [ ] 2.1 Both: Extend the `bridge.identity` packet to include the `certificateFingerprint` field. Update JSON codecs on both platforms. Verify: shared test vector `identity-packet.json` parses correctly on both platforms.
- [ ] 2.2 Both: Implement `bridge.pair` packet codec with fields `pair` (boolean). Verify: shared test vector `pair-request.json` covers request, accept, reject cases; both platforms parse them.
- [ ] 2.3 Mac: Implement `PairingManager` -- sends pair request, handles accept/reject response, enforces 30-second timeout via `Task.sleep`. Verify: unit test simulating accept, reject, and timeout scenarios.
- [ ] 2.4 Android: Implement `PairingManager` -- receives pair request, shows notification/dialog to user, sends accept/reject, enforces 30-second timeout via coroutine delay. Verify: unit test simulating accept, reject, and timeout scenarios.
- [ ] 2.5 Both: Implement `TrustStore` -- persist deviceId-to-fingerprint mapping (Mac: UserDefaults or Keychain; Android: EncryptedSharedPreferences). Verify: unit test that pins a fingerprint, restarts the store, and retrieves it.
- [ ] 2.6 Both: On pair acceptance, pin the peer's certificate fingerprint in `TrustStore`. On unpair, remove it. Verify: integration test of full pair-accept-pin and unpair-remove cycle.
- [ ] 2.7 Both: Drop all packets (except `bridge.identity` and `bridge.pair`) from devices whose fingerprint is not in the trust store. Verify: test that a packet from an unknown device is discarded.

## 3. TLS Integration

- [ ] 3.1 Mac: Implement `TLSWrapper` that wraps an L2CAP `NWConnection` (or raw stream) in mutual TLS using Security.framework. Configure `sec_protocol_options` with the self-signed cert and a custom verify block that checks the peer's fingerprint against `TrustStore`. Verify: unit test with a loopback TLS handshake using two self-signed certs.
- [ ] 3.2 Android: Implement `TlsWrapper` that wraps L2CAP `BluetoothSocket` streams in mutual TLS using `SSLContext` with custom `KeyManager` (serving the self-signed cert) and `TrustManager` (checking peer fingerprint against `TrustStore`). Verify: unit test with a loopback TLS handshake.
- [ ] 3.3 Both: Integrate TLS into the connection flow -- after L2CAP channel opens and identity is exchanged, perform mutual TLS handshake before any further packets. Verify: device test where both devices connect and the TLS handshake succeeds with pinned certs.
- [ ] 3.4 Both: Verify that TLS handshake fails and connection closes when a device presents an unknown certificate (not in trust store). Verify: device test with an unpaired device attempting connection.
- [ ] 3.5 Both: Measure TLS handshake latency over BLE L2CAP. Verify: log timestamps; handshake completes in under 3 seconds.

## 4. Reconnect & State Machine

- [ ] 4.1 Mac: Implement `LinkStateMachine` with four states: `disconnected`, `connecting`, `connected`, `bluetoothOff`. Wire state transitions to `CBCentralManagerDelegate` events. Verify: unit test driving state transitions through mock delegate calls.
- [ ] 4.2 Mac: On disconnect, keep the `CBCentralManager.connect()` pending (no cancel, no timeout). On peripheral rediscovery, reopen L2CAP channel and perform TLS re-handshake. Verify: device test -- disconnect by moving phone away, reconnect by bringing it back.
- [ ] 4.3 Both: Implement `bridge.ping` packet and `PingWatchdog` -- send ping every 30 seconds (configurable), declare link dead if no packet received in 60 seconds. Verify: unit test with mock timer; integration test by blocking packets and observing watchdog trigger.
- [ ] 4.4 Mac: On watchdog timeout, close L2CAP channel, transition state to `connecting`, record last-seen timestamp. Verify: unit test confirming state transition and timestamp recording.
- [ ] 4.5 Both: Add ping test vectors to `protocol/test-vectors/ping-packet.json`. Verify: both platforms parse the test vectors correctly.
- [ ] 4.6 Device: Run the kitchen test -- 10 consecutive walk-away-and-return cycles. Verify: all 10 cycles reconnect without user intervention; log each cycle's reconnect time.

## 5. Mac Status UI

- [ ] 5.1 Mac: Implement `StatusPanel` as a SwiftUI menu bar extra. Show the three states: connected (filled icon + device name), out of range (dimmed icon + "Out of range"), Bluetooth off (disabled icon + "Bluetooth off"). Verify: visual inspection on Mac with each state triggered.
- [ ] 5.2 Mac: Show "Last seen: <relative time>" in the dropdown when in out-of-range state. Update every 30 seconds. Verify: disconnect the phone, observe the timestamp updating in the menu bar.
- [ ] 5.3 Mac: Add dropdown menu items: paired device name and info (when connected), "Unpair" action (when paired), "Quit Bridge". Verify: visual inspection; unpair action triggers the unpair flow and clears the trust store.
- [ ] 5.4 Mac: Implement `PairPromptView` -- a panel or sheet showing device name, certificate fingerprint (formatted as colon-separated hex), and Accept/Reject buttons. Auto-dismiss after 30 seconds. Verify: trigger pair request from Android, observe prompt on Mac, test accept, reject, and timeout.

## 6. Testing

- [ ] 6.1 Create shared test vectors: `protocol/test-vectors/identity-packet.json`, `pair-request.json`, `ping-packet.json` with valid and malformed examples. Verify: both platforms load and parse all vectors in their test suites.
- [ ] 6.2 Mac: Unit tests for `CertificateManager`, `TrustStore`, `PairingManager`, `PingWatchdog`, `LinkStateMachine`. Verify: `xcodebuild test` passes.
- [ ] 6.3 Android: Unit tests for `CertificateManager`, `TrustStore`, `PairingManager`, `PingWatchdog`. Verify: `./gradlew test` passes.
- [ ] 6.4 Device: End-to-end pairing test on real hardware -- Mac and S25 pair successfully, certificate fingerprints are pinned, subsequent connections use mutual TLS. Verify: manual test with log inspection.
- [ ] 6.5 Device: Unpaired device rejection test -- a third BLE device (or a test app on another phone) attempts connection and is dropped. Verify: manual test confirming packets from the third device are never processed.
- [ ] 6.6 Device: Kitchen test (10 cycles) with timing log. Verify: all 10 cycles succeed; average reconnect time is under 5 seconds.
- [ ] 6.7 Device: Measure BLE battery drain over 4 hours of idle connected time (ping only, long connection interval). Verify: record battery percentage before and after on the S25; drain is under 2%.
