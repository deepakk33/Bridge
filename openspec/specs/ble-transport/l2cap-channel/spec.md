# ble-transport/l2cap-channel Specification

## Purpose
Bidirectional L2CAP channel between Mac and Android for streaming JSON packets over BLE.

## Requirements

### Requirement: Android publishes L2CAP PSM
The Android peripheral opens an L2CAP server socket and publishes its PSM (Protocol/Service Multiplexer) via a GATT characteristic so the Mac central can read it.

#### Scenario: Foreground service starts
- **WHEN** the foreground service starts
- **THEN** an L2CAP server socket is opened and its PSM is written to the Bridge GATT characteristic

#### Scenario: Mac reads PSM and connects
- **WHEN** the Mac reads the PSM characteristic
- **THEN** it opens an L2CAP channel to that PSM within 1 second

---

### Requirement: Bidirectional JSON exchange
Both endpoints send and receive single-line JSON messages terminated by newline (`\n`) over the L2CAP channel.

#### Scenario: Mac sends JSON line
- **WHEN** the Mac writes `{"id":1234,"type":"bridge.ping","body":{}}\n` to the channel
- **THEN** the Android side receives and parses the complete JSON object

#### Scenario: Android sends JSON line
- **WHEN** Android writes `{"id":5678,"type":"bridge.ping","body":{}}\n` to the channel
- **THEN** the Mac side receives and parses the complete JSON object

#### Scenario: Rapid exchange
- **WHEN** 100 ping packets are sent in quick succession from each side
- **THEN** all 200 packets are received and parsed correctly (no corruption, no loss)

---

### Requirement: Throughput measurement
The spike must measure actual L2CAP throughput to validate that clipboard sync (~1KB) will feel instant.

#### Scenario: Throughput test
- **WHEN** one side sends a 50KB payload as consecutive JSON lines
- **THEN** throughput is logged in KB/s and must exceed 30 KB/s for the spike to pass

---

### Requirement: Reconnect via pending connect
The Mac issues a CBCentralManager connect that has no timeout. When the phone walks out of range and returns, the system reconnects without application code.

#### Scenario: Phone leaves range
- **WHEN** the phone moves out of BLE range
- **THEN** the Mac detects disconnection within 30 seconds

#### Scenario: Phone returns to range
- **WHEN** the phone returns to BLE range after being away
- **THEN** the connection re-establishes automatically without any user action or app-side reconnect timer

### Requirement: A send reports whether it was written

Sending a packet over the L2CAP channel SHALL report success or failure to the caller. Success
means every byte of the serialised line was accepted by the output stream. A serialisation
failure, an absent or closed stream, a stream write error, and a partial write that cannot be
completed SHALL each be reported as a failure.

The channel SHALL complete a partial write by continuing to write the remaining bytes rather than
reporting success for a truncated line, since a truncated line cannot be parsed by the peer.

A send SHALL also be reported as a failure when the peer is not verified, because nothing is
delivered to an unverified peer.

Platform: Mac only — this is the Mac's `CBL2CAPChannel` output stream. Android's socket write
already throws on failure.

Packet types: all, including `bridge.ping`, `bridge.notification.reply` and
`bridge.notification.request`.

#### Scenario: Successful send

- **WHEN** a packet is sent over an open channel to a verified peer and the stream accepts every
  byte
- **THEN** the send reports success

#### Scenario: No channel

- **WHEN** a packet is sent while no L2CAP channel is open
- **THEN** the send reports failure

#### Scenario: Stream refuses or errors

- **WHEN** the output stream has no space available, or the write returns an error
- **THEN** the send reports failure rather than logging and returning success

#### Scenario: Partial write is completed or failed

- **WHEN** the output stream accepts fewer bytes than the serialised line contains
- **THEN** the channel writes the remainder, and reports failure if the remainder cannot be
  written

#### Scenario: Unverified peer

- **WHEN** a packet other than identity or pairing is sent while the peer is not verified
- **THEN** the send reports failure

#### Scenario: A failed reply tells the user

- **WHEN** the user replies to a mirrored notification and the send reports failure
- **THEN** the Mac posts the "Reply could not be delivered" notification
