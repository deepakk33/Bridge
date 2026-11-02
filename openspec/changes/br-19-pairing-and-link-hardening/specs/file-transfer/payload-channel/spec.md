## MODIFIED Requirements

### Requirement: The payload connection is mutual TLS with pinned certificates

The out-of-band payload connection SHALL be wrapped in mutual TLS. Both ends SHALL present their
self-signed certificate and SHALL verify the peer's certificate against the fingerprint pinned
for that `deviceId` at pairing time. Verification SHALL be by pinned fingerprint only; no
certificate authority, hostname check, or system trust store takes part in the decision.

The `deviceId` SHALL be the identity of the peer on the control connection that requested or
offered the transfer. It SHALL NOT be derived from the contents of the trust store, which may
hold more than one device and holds them in no particular order.

A payload connection SHALL NOT be served to, or accepted from, a peer with no pinned fingerprint.

#### Scenario: Paired peers transfer over TLS

- **WHEN** two paired devices transfer a file over Tier 2, Tier 3 or Tier 4
- **THEN** the payload connection completes a mutual TLS handshake, both fingerprints match their
  pins, and the file bytes flow inside the TLS session

#### Scenario: More than one device is pinned

- **WHEN** the trust store holds pins for two or more devices and a transfer is requested by one
  of them
- **THEN** the payload handshake is verified against the pin for that requesting device's
  `deviceId`, and a certificate matching a different pinned device is refused

#### Scenario: An observer on the network sees only ciphertext

- **WHEN** a third host on the same network captures the payload connection's traffic
- **THEN** the captured bytes are TLS records, and the file's contents are not recoverable from
  them

#### Scenario: Unpinned peer is refused

- **WHEN** a host connects to the payload port presenting a certificate with no pinned
  fingerprint for its `deviceId`
- **THEN** the handshake is rejected and the connection closed before any payload byte is written

#### Scenario: Mismatched fingerprint is refused

- **WHEN** a peer presents a certificate whose fingerprint differs from the pinned value
- **THEN** the handshake is rejected, the connection is closed, no partial file is written, and
  the transfer is reported as failed

#### Scenario: A handshake failure is reported, not retried in the clear

- **WHEN** the payload TLS handshake fails for any reason
- **THEN** the transfer fails and is surfaced to the user, and no unencrypted fallback connection
  is attempted
