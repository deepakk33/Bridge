## ADDED Requirements

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

## MODIFIED Requirements

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
