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
The sender must be ready to serve the file before advertising it.

#### Scenario: TCP listener ready before packet
- **WHEN** the sender prepares a file transfer
- **THEN** the TCP server socket is bound and listening before the `bridge.share.request` packet is sent over the control channel

#### Scenario: Port selection
- **WHEN** the sender binds the TCP server socket
- **THEN** it uses an ephemeral port (OS-assigned) and reports that port in `payloadTransferInfo`

---

### Requirement: Receiver opens TCP connection and reads exact bytes
The receiver connects to the sender's port and reads exactly `payloadSize` bytes.

#### Scenario: Successful payload download
- **WHEN** the receiver gets a `bridge.share.request` with `payloadSize: 524288` and `payloadTransferInfo: {"port": 39721}`
- **THEN** the receiver opens a TCP connection to the sender's IP on port 39721 and reads exactly 524288 bytes

#### Scenario: Incomplete transfer
- **WHEN** the TCP connection drops before `payloadSize` bytes are received
- **THEN** the receiver discards the partial file, reports failure, and does not save a corrupt file

#### Scenario: Excess bytes
- **WHEN** the sender offers more bytes than `payloadSize`
- **THEN** the receiver reads exactly `payloadSize` bytes and closes the connection, ignoring any excess

---

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
