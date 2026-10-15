## Context

Builds on iteration 1 (BLE spike), which established:
- Mac as BLE central (Swift, Core Bluetooth), phone as BLE peripheral (Kotlin, BluetoothLeAdvertiser)
- L2CAP CoC channel carrying newline-delimited JSON both ways
- GATT service with identity characteristic and PSM for L2CAP
- Measured throughput above 30 KB/s target
- Validated that `CBCentralManager.connect()` pending connect works for auto-reconnect

The raw L2CAP channel from iter-1 has no authentication or encryption. Any nearby device could connect and read packets, or a rogue peripheral could impersonate the phone. Before sending private data (notifications, clipboard, files), the link must be trusted. This iteration adds the security layer.

## Goals

- **Encrypted channel**: Mutual TLS over the L2CAP stream so all data is encrypted in transit, with no custom cryptography.
- **Trust model**: Self-signed certificates pinned on first pairing, so subsequent connections are authenticated without passwords, accounts, or servers.
- **Unattended reconnect**: Walk away, come back, link restores with full TLS -- no user action. Validated by 10 consecutive kitchen test cycles.

## Non-Goals

- **No Wi-Fi transport yet.** Tiers 2-4 from the PRD transport ladder are deferred to later iterations. This iteration operates entirely over BLE L2CAP.
- **No real features.** No notifications, clipboard, or file transfer. The only packet types are `bridge.identity`, `bridge.pair`, and `bridge.ping`.
- **No Android pairing UI polish.** The Android side needs a basic accept/reject prompt (notification or dialog), but no settings screen or device management UI.
- **No multi-device support.** One phone, one Mac. The pairing flow assumes a single paired device at a time.

## Decisions

### 1. Self-signed certificates, not pre-shared keys

**Choice:** Each device generates a self-signed X.509 certificate on first launch and presents it during TLS handshake.

**Why:** Pre-shared keys would require a secure channel to exchange the key in the first place, which is the problem we are solving. A pre-shared PIN (like Bluetooth Classic pairing) requires user input on every reconnect or a stored secret that is equivalent to a certificate anyway. Self-signed certificates give us standard TLS machinery, mutual authentication, and the fingerprint is a natural trust anchor for pinning.

**Alternative rejected:** Diffie-Hellman key exchange without certificates. This would protect against passive eavesdropping but not against active MITM. Certificate pinning on first pairing (trust-on-first-use) closes that gap for all subsequent connections.

### 2. TLS over L2CAP, not custom encryption

**Choice:** Wrap the L2CAP stream in standard TLS using platform libraries (Security.framework on Mac, javax.net.ssl on Android).

**Why:** Rolling custom encryption over a stream socket is a well-known source of vulnerabilities. Both platforms ship mature TLS implementations that handle cipher negotiation, certificate validation, and stream framing. The L2CAP CoC channel from iter-1 already behaves like a stream socket, so TLS sits on top naturally. The JSON-line framing is unchanged at the application layer.

**Alternative rejected:** NaCl/libsodium box with manual key management. Simpler API surface, but we would need to implement our own session resumption, certificate verification, and stream framing -- all things TLS does out of the box.

### 3. 30-second pairing timeout

**Choice:** A pair request that is not accepted or rejected within 30 seconds is automatically rejected.

**Why:** The pairing prompt appears on a device the user is physically holding (within 1 metre). 30 seconds is long enough to read the fingerprint and tap accept, short enough that a stale prompt does not linger. The KDE Connect project uses the same timeout. A shorter timeout (10 seconds) risks rejecting when the user is simply picking up the phone; a longer timeout (2 minutes) leaves an open prompt that could be accidentally accepted later.

### 4. Unpair-and-repair flow for device replacement

**Choice:** Either side can send `bridge.pair` with `pair: false` to unpair. Re-pairing is a fresh request with user confirmation on both sides.

**Why:** The PRD states "device identity is never hard-coded" and the phone may be replaced. When a phone is replaced, it has a new certificate, so the old pin is useless. The Mac must be able to clear the old pin and accept a new one. Making unpair an explicit action (not automatic on fingerprint mismatch) prevents a rogue device from silently replacing a legitimate one.

**PRD risk addressed:** "Certificate pinning breaks after a reinstall" -- the unpair-and-repair flow handles this case, surfaced in the UI menu bar dropdown.

## Component Layout

### Mac (Swift)

| Component | Responsibility |
|-----------|---------------|
| `CertificateManager` | Generate self-signed cert on first launch, store in Keychain, load on subsequent launches. Expose fingerprint. |
| `TrustStore` | Persist pinned fingerprints (deviceId -> fingerprint mapping) in UserDefaults or Keychain. |
| `TLSWrapper` | Wrap the L2CAP `NWConnection` stream in TLS using `sec_protocol_options`, configured for mutual auth with the self-signed cert. |
| `PairingManager` | Handle `bridge.pair` send/receive, 30s timeout, prompt display, fingerprint pinning on accept. |
| `PingWatchdog` | Send `bridge.ping` on interval, track last-received timestamp, fire disconnect on timeout. |
| `LinkStateMachine` | Four states: `disconnected`, `connecting`, `connected`, `bluetoothOff`. Drives the menu bar UI. |
| `StatusPanel` (SwiftUI) | Menu bar icon + dropdown showing state, device name, last-seen time, unpair action. |
| `PairPromptView` (SwiftUI) | Modal showing device name, fingerprint, accept/reject buttons with 30s auto-dismiss. |

### Android (Kotlin)

| Component | Responsibility |
|-----------|---------------|
| `CertificateManager` | Generate self-signed cert on first launch, private key in Android Keystore, cert in app storage. Expose fingerprint. |
| `TrustStore` | Persist pinned fingerprints in EncryptedSharedPreferences. |
| `TlsWrapper` | Wrap the L2CAP `BluetoothSocket` InputStream/OutputStream in TLS using `SSLContext` with custom `TrustManager` and `KeyManager`. |
| `PairingManager` | Handle `bridge.pair` send/receive, 30s timeout, prompt via notification or dialog. |
| `PingWatchdog` | Send `bridge.ping` on interval, track last-received, notify `LinkManager` on timeout. |

### Protocol (shared test vectors)

New test vectors in `protocol/test-vectors/`:
- `identity-packet.json` -- valid identity packets with all fields
- `pair-request.json` -- pair request, accept, reject, and timeout sequences
- `ping-packet.json` -- valid ping packets

## Risks

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|-----------|
| Certificate generation on Android fails on some devices | Low | Blocking -- no cert, no TLS | Use Android Keystore with `setUserAuthenticationRequired(false)`. Test on real S25 hardware. Fallback: generate with Bouncy Castle if Keystore is unavailable. |
| TLS handshake latency on BLE | Medium | Reconnect feels slow (>2s) | Measure in this iteration. L2CAP MTU of ~500 bytes means TLS handshake (~2-4 KB) takes several round trips. If >3 seconds, consider TLS session tickets for resumption in a future iteration. |
| Platform TLS libraries reject self-signed certs by default | Medium | Handshake fails silently | Both sides must configure custom trust evaluation: `sec_protocol_options_set_verify_block` on Mac, custom `X509TrustManager` on Android. Test this early. |
| L2CAP stream does not behave like a TCP stream for TLS | Medium | TLS framing breaks | L2CAP CoC is connection-oriented and ordered, so it should work. If not, add a thin framing layer (length-prefixed records) between L2CAP and TLS. |
| Keychain/Keystore cleared by OS update or user action | Low | Silent pairing break | The unpair-and-repair flow handles this. Menu bar shows connection failure so the user knows to re-pair. |
