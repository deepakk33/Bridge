## ADDED Requirements

### Requirement: The payload connection is mutual TLS with pinned certificates

The out-of-band payload connection SHALL be wrapped in mutual TLS. Both ends SHALL present their
self-signed certificate and SHALL verify the peer's certificate against the fingerprint pinned
for that `deviceId` at pairing time. Verification SHALL be by pinned fingerprint only; no
certificate authority, hostname check, or system trust store takes part in the decision.

A payload connection SHALL NOT be served to, or accepted from, a peer with no pinned fingerprint.

#### Scenario: Paired peers transfer over TLS

- **WHEN** two paired devices transfer a file over Tier 2, Tier 3 or Tier 4
- **THEN** the payload connection completes a mutual TLS handshake, both fingerprints match their
  pins, and the file bytes flow inside the TLS session

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

## MODIFIED Requirements

### Requirement: Sender listens on TCP port before sending request
The sender SHALL be ready to serve the file, with mutual TLS configured against the peer's
pinned fingerprint, before advertising it.

#### Scenario: TCP listener ready before packet
- **WHEN** the sender prepares a file transfer
- **THEN** the TCP server socket is bound and listening, configured for mutual TLS with the
  peer's pinned fingerprint, before the `bridge.share.request` packet is sent over the control
  channel

#### Scenario: Port selection
- **WHEN** the sender binds the TCP server socket
- **THEN** it uses an ephemeral port (OS-assigned) and reports that port in `payloadTransferInfo`

#### Scenario: No pinned fingerprint means no listener
- **WHEN** a share is attempted toward a peer with no pinned fingerprint
- **THEN** no payload listener is opened and the transfer is refused before the
  `bridge.share.request` packet is sent

### Requirement: Receiver opens TCP connection and reads exact bytes
The receiver SHALL connect to the sender's port, SHALL complete a mutual TLS handshake against
the sender's pinned fingerprint, and SHALL read exactly `payloadSize` bytes of plaintext from
the TLS session.

#### Scenario: Successful payload download
- **WHEN** the receiver gets a `bridge.share.request` with `payloadSize: 524288` and
  `payloadTransferInfo: {"port": 39721}`
- **THEN** the receiver opens a TCP connection to the sender's IP on port 39721, completes the
  mutual TLS handshake, and reads exactly 524288 bytes

#### Scenario: Incomplete transfer
- **WHEN** the connection drops before `payloadSize` bytes are received
- **THEN** the receiver discards the partial file, reports failure, and does not save a corrupt
  file

#### Scenario: Excess bytes
- **WHEN** the sender offers more bytes than `payloadSize`
- **THEN** the receiver reads exactly `payloadSize` bytes and closes the connection, ignoring any
  excess

#### Scenario: Handshake rejected by the receiver
- **WHEN** the sender's certificate does not match the fingerprint pinned for its `deviceId`
- **THEN** the receiver closes the connection without reading any payload bytes and reports the
  transfer as failed
