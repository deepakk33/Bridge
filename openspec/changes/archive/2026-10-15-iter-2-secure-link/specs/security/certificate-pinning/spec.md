## Purpose

Define the self-signed certificate lifecycle, mutual TLS over the BLE L2CAP channel, and fingerprint pinning model that ensures only paired devices can communicate, with no external CA, no account, and no server.

## ADDED Requirements

### Requirement: Self-signed certificate generation

Each device generates a self-signed X.509 certificate on first launch, containing an RSA 2048-bit (or P-256 EC) key pair. The certificate is generated once and persisted permanently.

#### Scenario: Mac generates certificate on first launch

- **WHEN** the Bridge Mac app launches for the first time
- **THEN** it generates a self-signed certificate and stores both the certificate and private key in the macOS Keychain, tagged with a Bridge-specific label

#### Scenario: Android generates certificate on first launch

- **WHEN** the Bridge Android app launches for the first time
- **THEN** it generates a self-signed certificate with the private key stored in Android Keystore (hardware-backed where available) and the certificate in app-private storage

#### Scenario: Certificate survives reinstall check

- **WHEN** the app is reinstalled on either platform
- **THEN** the certificate is lost (Keychain entry removed on Mac uninstall; Keystore cleared on Android uninstall), and a new certificate is generated on next launch, requiring re-pairing

### Requirement: Mutual TLS over L2CAP

After identity exchange and successful pairing, all subsequent data on the L2CAP channel is wrapped in mutual TLS. Both sides present their self-signed certificate during the TLS handshake.

#### Scenario: TLS handshake on reconnect

- **WHEN** a previously paired device reconnects via BLE
- **THEN** both devices perform a mutual TLS handshake over the L2CAP stream, each presenting its self-signed certificate

#### Scenario: TLS wraps the existing JSON framing

- **WHEN** mutual TLS is established
- **THEN** all reads and writes on the L2CAP channel go through the TLS stream; the newline-delimited JSON framing is unchanged at the application layer

#### Scenario: Handshake failure closes the connection

- **WHEN** the TLS handshake fails (certificate mismatch, expired, or corrupt)
- **THEN** the L2CAP channel is closed and the Mac transitions to "out of range" state

### Requirement: Certificate fingerprint pinning

On successful pairing, each device stores the SHA-256 fingerprint of the peer's certificate. On every subsequent TLS handshake, the presented certificate's fingerprint is compared against the pinned value.

#### Scenario: Pinned fingerprint matches

- **WHEN** a paired device reconnects and presents its certificate during TLS handshake
- **THEN** the receiving device computes the SHA-256 fingerprint, compares it to the stored pin, and proceeds if they match

#### Scenario: Pinned fingerprint does not match

- **WHEN** a device presents a certificate whose fingerprint does not match the pinned value
- **THEN** the TLS handshake is rejected, the connection is closed, and the event is logged for diagnostics

#### Scenario: No pinned fingerprint for the device

- **WHEN** a device connects whose `deviceId` has no pinned fingerprint (not yet paired)
- **THEN** only `bridge.identity` and `bridge.pair` packets are accepted; all other packets are dropped

### Requirement: Trust store persistence

The trust store (mapping deviceId to pinned certificate fingerprint) is persisted across app restarts on both platforms.

#### Scenario: Mac trust store persistence

- **WHEN** the Mac app is quit and relaunched
- **THEN** all previously pinned certificate fingerprints are available without re-pairing

#### Scenario: Android trust store persistence

- **WHEN** the Android app is force-stopped and restarted
- **THEN** all previously pinned certificate fingerprints are available without re-pairing
