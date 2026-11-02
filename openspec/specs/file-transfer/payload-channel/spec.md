# file-transfer/payload-channel Specification

## Purpose
Out-of-band payload transfer mechanism based on the KDE Connect protocol. The `bridge.share.request` packet carries `payloadSize` and `payloadTransferInfo` (containing a TCP port). The receiver opens a separate TCP connection to that port and reads exactly `payloadSize` bytes. This keeps the Tier 1 BLE control channel free for notifications and clipboard during file transfers.

## Requirements

### Requirement: Sender sets payloadSize and payloadTransferInfo
The sender includes the file size and a transfer endpoint in the `bridge.share.request` packet.

#### Scenario: Mac sends share request with payload metadata
- **WHEN** the Mac initiates a file transfer
- **THEN** the `bridge.share.request` packet includes `payloadSize` (exact byte count) and `payloadTransferInfo` containing `{"port": <tcp_port>}` where the sender is listening

#### Scenario: Android sends share request with payload metadata
- **WHEN** Android initiates a file transfer via share intent
- **THEN** the `bridge.share.request` packet includes `payloadSize` and `payloadTransferInfo` with the sender's listening port

#### Scenario: Zero payloadSize
- **WHEN** a `bridge.share.request` has `payloadSize` of 0 or absent
- **THEN** the receiver treats it as metadata only (no file body to fetch) and ignores `payloadTransferInfo`

---

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

### Requirement: TCP connection torn down after transfer
Each payload transfer uses a fresh TCP connection that is closed when the transfer completes.

#### Scenario: Connection cleanup on success
- **WHEN** the receiver has read `payloadSize` bytes successfully
- **THEN** both sides close the TCP connection within 1 second

#### Scenario: Connection cleanup on failure
- **WHEN** the transfer times out or an error occurs
- **THEN** both sides close the TCP connection and the sender closes its server socket within 2 seconds

#### Scenario: Timeout
- **WHEN** no data is received on the TCP connection for 30 seconds
- **THEN** the receiver aborts the transfer, closes the connection, and reports a timeout error

---

### Requirement: Control channel remains responsive during payload transfer
The out-of-band TCP transfer must not block the Tier 1 BLE or Tier 3 control channel.

#### Scenario: Notification during payload transfer
- **WHEN** a phone notification arrives while a payload transfer is in progress
- **THEN** the `bridge.notification` packet is delivered over the control channel within 2 seconds

#### Scenario: Multiple transfers
- **WHEN** two files are queued for transfer
- **THEN** they are transferred sequentially (one TCP connection at a time) and the control channel is never blocked

---

### Requirement: Tier routing uses payloadSize
The system selects the transfer tier based on the file size.

#### Scenario: Small file uses Tier 2
- **WHEN** `payloadSize` is under 500 KB (512000 bytes)
- **THEN** the TCP transfer runs over Bluetooth Classic RFCOMM (Tier 2)

#### Scenario: Large file uses Tier 3
- **WHEN** `payloadSize` is 500 KB or above and both devices share a network
- **THEN** the TCP transfer runs over Wi-Fi LAN (Tier 3)

#### Scenario: Large file without shared network
- **WHEN** `payloadSize` is 500 KB or above but no shared network is detected
- **THEN** the transfer falls back to Tier 2 (RFCOMM) with a user-visible slow-transfer warning

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
