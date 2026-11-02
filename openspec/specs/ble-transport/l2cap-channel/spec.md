# ble-transport/l2cap-channel Specification

## Purpose
Bidirectional L2CAP channel between Mac and Android for streaming JSON packets over BLE.

## Requirements

### Requirement: Android publishes L2CAP PSM
The Android peripheral opens an L2CAP server socket and publishes its PSM (Protocol/Service
Multiplexer) via a GATT characteristic so the Mac central can read it.

The server socket SHALL request Bluetooth link-layer encryption where the platform pair permits
it, so that the control channel is not carried in cleartext over the air.

Platform constraint: Android's encrypted L2CAP listen requires the remote device to be bonded,
and Core Bluetooth exposes no explicit API for a central to initiate bonding with a peripheral.
If an encrypted listen cannot be established between the target devices, the channel SHALL
remain unencrypted and the README, PRD and `security/certificate-pinning` specification SHALL
state that plainly rather than describing encryption that is not in force.

#### Scenario: Foreground service starts
- **WHEN** the foreground service starts
- **THEN** an L2CAP server socket is opened and its PSM is written to the Bridge GATT
  characteristic

#### Scenario: Mac reads PSM and connects
- **WHEN** the Mac reads the PSM characteristic
- **THEN** it opens an L2CAP channel to that PSM within 1 second

#### Scenario: Control channel is link-layer encrypted

- **WHEN** an L2CAP channel is established between two bonded devices
- **THEN** the channel is encrypted at the Bluetooth link layer, and the application-level
  newline-delimited JSON framing is unchanged

#### Scenario: Encryption unavailable is documented, not implied

- **WHEN** an encrypted L2CAP listen cannot be established between the target devices
- **THEN** the channel operates unencrypted and the project documentation states that the
  control channel is unencrypted

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

### Requirement: Packets from an unidentified peer are dropped

The control channel SHALL default to denying. Until the peer's identity is known and a trust
decision has been made, only `bridge.identity` and `bridge.pair` SHALL be dispatched to a
handler; every other packet type SHALL be dropped before its body is acted on.

The check SHALL run on every received packet. It SHALL NOT be skipped because peer identity is
still unknown — an unknown peer is an untrusted peer, which is precisely the case the check
exists for.

#### Scenario: Any packet type before identity

- **WHEN** a peer opens an L2CAP channel and sends `bridge.clipboard`,
  `bridge.notification.reply`, `bridge.notification.request` or `bridge.share.request` before
  sending `bridge.identity`
- **THEN** the packet is dropped without reaching its handler, and the drop is logged with the
  packet type

#### Scenario: Identity and pair are exempt

- **WHEN** a peer that has not yet identified itself sends `bridge.identity` or `bridge.pair`
- **THEN** the packet is processed, because these are the packets that establish identity and
  trust

#### Scenario: Identified but untrusted peer

- **WHEN** a peer has sent `bridge.identity` but its fingerprint is not pinned
- **THEN** only `bridge.identity` and `bridge.pair` continue to be processed, and all other
  packet types are dropped

#### Scenario: Ping is not exempt

- **WHEN** an unidentified peer sends `bridge.ping`
- **THEN** no pong is returned, because answering confirms a live endpoint to a peer that has not
  identified itself

### Requirement: Received lines are length-capped

A received line SHALL be bounded. Reading SHALL stop once a single line exceeds the cap, and the
connection SHALL be closed rather than the oversized line being buffered, parsed, or truncated
and processed. The cap SHALL be large enough for every legitimate control packet and far below
what would exhaust memory on either device.

#### Scenario: A line over the cap closes the connection

- **WHEN** a peer sends a line longer than the cap without a newline
- **THEN** reading stops, the connection is closed, and the event is logged with the number of
  bytes seen

#### Scenario: A legitimate large packet still fits

- **WHEN** a packet at the largest size the protocol permits on the control channel is sent
- **THEN** it is received and processed normally, since payloads above the out-of-band threshold
  travel over a separate connection rather than this one

#### Scenario: Memory is bounded regardless of peer behaviour

- **WHEN** a peer streams bytes continuously with no newline
- **THEN** memory held for that connection never exceeds the cap

### Requirement: Packet handlers survive a peripheral rebuild

Platform constraint: Android only. The phone is the BLE peripheral, and it rebuilds that
peripheral whenever the Bluetooth adapter is cycled or the service restarts its link.

The set of packet handlers the Android service registers SHALL be a property of the running
service, not of any one peripheral or channel instance. Rebuilding the BLE peripheral, the
advertiser, or the L2CAP server SHALL NOT change which packet types the phone acts on, and
SHALL NOT require the service to register anything a second time.

This covers every packet type the phone accepts from the Mac over the control channel:
`bridge.notification.reply`, `bridge.notification.request`, `bridge.clipboard`,
`bridge.clipboard.connect`, `bridge.share.request`, `bridge.pair`, and the link-level
pair-confirmation and connect events.

#### Scenario: Bluetooth is turned off and on again

- **WHEN** the Bluetooth adapter is cycled and the service rebuilds its BLE peripheral
- **THEN** a `bridge.share.request` received after the rebuild starts a file receive, exactly as
  one received before the rebuild would have

#### Scenario: Every handler, not just the one the restart path remembered

- **WHEN** the peripheral has been rebuilt
- **THEN** `bridge.notification.reply`, `bridge.notification.request`, `bridge.clipboard`,
  `bridge.clipboard.connect` and `bridge.share.request` each reach the same handler they reached
  before the rebuild

#### Scenario: Pairing still prompts after a rebuild

- **WHEN** an unpaired Mac connects after the peripheral has been rebuilt
- **THEN** the six-digit confirmation is raised and the pairing notification is posted, as on a
  first connection

---

### Requirement: A received packet that reaches no handler is logged

Platform constraint: Android only.

When the phone receives a packet of a type it declares in its identity capabilities, and the
trust gate admits it, but no handler is registered for it, the phone SHALL log a warning naming
the packet type. It SHALL NOT discard the packet without a trace.

An unknown packet type — one the phone does not declare — SHALL continue to be logged as such.

#### Scenario: A known type with no handler registered

- **WHEN** a `bridge.share.request` passes the trust gate and no share-request handler is
  registered
- **THEN** a warning naming `bridge.share.request` is logged, so the drop is visible in logcat

#### Scenario: An unknown packet type

- **WHEN** a packet arrives whose type the phone does not declare in its capabilities
- **THEN** a warning naming that type is logged, unchanged from today's behaviour
