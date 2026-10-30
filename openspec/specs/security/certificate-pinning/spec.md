# security/certificate-pinning Specification

## Purpose
Define the self-signed certificate lifecycle, the per-path encryption model, and the fingerprint pinning that ensures only paired devices can communicate, with no external CA, no account and no server. Mutual TLS covers the out-of-band payload connection; the BLE L2CAP control channel relies on proximity and is not encrypted today.

## Requirements

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

### Requirement: Certificate fingerprint pinning

On successful pairing, each device SHALL store the SHA-256 fingerprint of the peer's
certificate. On every subsequent mutual-TLS handshake, the presented certificate's fingerprint
SHALL be compared against the pinned value, and the connection SHALL proceed only on a match.

#### Scenario: Pinned fingerprint matches

- **WHEN** a paired device presents its certificate during a payload TLS handshake
- **THEN** the receiving device computes the SHA-256 fingerprint, compares it to the stored pin,
  and proceeds if they match

#### Scenario: Pinned fingerprint does not match

- **WHEN** a device presents a certificate whose fingerprint does not match the pinned value
- **THEN** the TLS handshake is rejected, the connection is closed, no re-pairing is offered
  automatically, and the event is logged for diagnostics

#### Scenario: No pinned fingerprint for the device

- **WHEN** a device connects whose `deviceId` has no pinned fingerprint (not yet paired)
- **THEN** only `bridge.identity` and `bridge.pair` packets are accepted; all other packets are
  dropped, and no payload connection is served to that device

### Requirement: Trust store persistence

The trust store (mapping deviceId to pinned certificate fingerprint) is persisted across app restarts on both platforms.

#### Scenario: Mac trust store persistence

- **WHEN** the Mac app is quit and relaunched
- **THEN** all previously pinned certificate fingerprints are available without re-pairing

#### Scenario: Android trust store persistence

- **WHEN** the Android app is force-stopped and restarted
- **THEN** all previously pinned certificate fingerprints are available without re-pairing

### Requirement: Encryption is chosen per transport path

Bridge SHALL encrypt each transport path with the strongest mechanism that path supports, rather
than claiming one mechanism for all of them. The out-of-band payload connection, which crosses a
shared network and is reachable by any host on it, SHALL use mutual TLS with pinned certificates.
The BLE L2CAP control channel, which requires physical proximity to reach, SHALL rely on
Bluetooth link-layer encryption.

Documentation SHALL describe the mechanism actually in force on each path. A path whose
encryption cannot be established on the target platforms SHALL be documented as unencrypted
rather than described aspirationally.

#### Scenario: Payload bytes never cross the network in the clear

- **WHEN** a file is transferred over Tier 2, Tier 3 or Tier 4
- **THEN** the payload connection is wrapped in mutual TLS and an observer on the same network
  captures only ciphertext

#### Scenario: Documentation matches the shipped transport

- **WHEN** the README, the PRD, the project instructions and this capability's specification are
  read together
- **THEN** each states the same encryption mechanism for each transport path, and none claims
  mutual TLS on a path that does not have it

### Requirement: An unusable trust store denies rather than permits

Every trust decision SHALL fail closed. A trust store that is uninitialised, unreadable, or
throws while being consulted SHALL be treated as "this peer is not trusted", never as "this peer
is trusted" and never as "skip the check".

#### Scenario: Trust store not yet initialised

- **WHEN** a packet requiring a trust decision arrives before the trust store has been initialised
- **THEN** the packet is dropped and the reason is logged

#### Scenario: Trust store throws while being consulted

- **WHEN** reading the trust store raises an error during a trust decision
- **THEN** the peer is treated as untrusted and the packet is dropped, rather than the error
  being swallowed and the packet processed

#### Scenario: Peer presents a certificate with no pinned fingerprint

- **WHEN** a TLS peer presents a certificate and no fingerprint is pinned for that device
- **THEN** the handshake is rejected and the connection closed, rather than the presented
  fingerprint being accepted and logged
