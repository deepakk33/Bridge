## ADDED Requirements

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

## MODIFIED Requirements

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

## REMOVED Requirements

### Requirement: Mutual TLS over L2CAP

**Reason**: Never implemented, and not implementable at proportionate cost. `TLSWrapper` on the
Mac and `CertificateManager.createSSLContext()` on Android were both written for this requirement
and both have zero callers, so the requirement has described nothing since it was written. On
Android the L2CAP link is a `BluetoothSocket`, which is not a `java.net.Socket`, so an
`SSLSocketFactory` cannot wrap it; mutual TLS there means hand-driving an `SSLEngine` handshake
and buffer state machine. The Mac's implementation was built on Secure Transport, deprecated
since macOS 10.15, and busy-spins on `errSSLWouldBlock` against a non-blocking stream. Against
that cost, the L2CAP channel is the *less* exposed path: reaching it needs physical proximity and
sniffer hardware, where the payload path is reachable from any host on the Wi-Fi network.

**Migration**: Encryption of the L2CAP control channel is covered by "Encryption is chosen per
transport path" above, which places it on Bluetooth link-layer encryption. Confidentiality for
bulk data, and certificate pinning, move to the payload connection — see
`file-transfer/payload-channel`. Peer authorisation on the control channel is covered by
`ble-transport/l2cap-channel`'s default-deny requirement and by `protocol/pairing`. No paired
device needs to re-pair: pinned fingerprints are unchanged and are still the durable identity.
