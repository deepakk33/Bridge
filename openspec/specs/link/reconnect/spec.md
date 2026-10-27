# link/reconnect Specification

## Purpose
Define the automatic reconnection behaviour using BLE pending connect, the link state machine, and the ping watchdog that together ensure the connection restores without user intervention when the phone returns to range, validated by the kitchen test (10 consecutive cycles).

## Requirements

### Requirement: Pending connect for auto-reconnect

The Mac (BLE central) uses `CBCentralManager.connect()` which has no timeout. When the phone goes out of range, the connect request stays pending in the Bluetooth stack and re-establishes automatically when the phone returns.

#### Scenario: Phone walks out of range

- **WHEN** the BLE link drops because the phone moved out of range
- **THEN** the Mac transitions to "out of range" state and the pending connect request remains active in Core Bluetooth

#### Scenario: Phone returns to range

- **WHEN** the phone returns to BLE range after being out of range
- **THEN** Core Bluetooth re-establishes the connection automatically, the L2CAP channel is reopened, identity is re-exchanged, mutual TLS re-handshake occurs, and the Mac transitions to "connected" state

#### Scenario: No user intervention required

- **WHEN** the phone leaves and returns to range
- **THEN** the full reconnection (BLE link, L2CAP channel, TLS handshake) completes without the user touching either device

### Requirement: Link state machine

The Mac maintains a connection state machine with four states: `disconnected`, `connecting`,
`connected`, and `bluetoothOff`. The machine SHALL enter `connected` only once the peer's identity
has been received and verified against the trust store — an open L2CAP channel to an unverified
peer is `connecting`, matching the Android side's rule that a socket to an untrusted peer delivers
nothing.

Packet types involved: `bridge.identity` (carries the peer's device id and certificate
fingerprint), `bridge.pair`.

#### Scenario: State transitions on connection

- **WHEN** the Mac app launches and Bluetooth is on
- **THEN** the state starts at `connecting` (scanning/pending connect) and transitions to
  `connected` only after the L2CAP channel is open **and** the peer's identity has been verified

#### Scenario: Open channel to an unverified peer

- **WHEN** the L2CAP channel opens but no `bridge.identity` has arrived, or the identity does not
  match a trusted pin
- **THEN** the state remains `connecting` and the Mac does not report a connected link

#### Scenario: Verification completes after pairing

- **WHEN** the peer becomes trusted while the channel is already open — for example a pair request
  is accepted
- **THEN** the state transitions to `connected` without needing the channel to be reopened

#### Scenario: State transitions on disconnect

- **WHEN** the BLE link drops (out of range or phone Bluetooth off)
- **THEN** the state transitions from `connected` to `connecting` and the pending connect stays
  active

#### Scenario: Bluetooth off state

- **WHEN** the Mac's Bluetooth is turned off
- **THEN** the state transitions to `bluetoothOff` and the menu bar reflects this

#### Scenario: Bluetooth turned back on

- **WHEN** the Mac's Bluetooth is turned back on
- **THEN** the state transitions to `connecting` and scanning/pending connect resumes

### Requirement: Ping watchdog

A `bridge.ping` packet is sent at a configurable interval (default: 30 seconds) by both devices. If no packet of any kind is received within the watchdog window (default: 2x the ping interval, i.e. 60 seconds), the link is considered dead.

#### Scenario: Ping keeps the link alive

- **WHEN** no application packet is exchanged for the ping interval duration
- **THEN** both devices send a `bridge.ping` packet to keep the watchdog satisfied

#### Scenario: Watchdog triggers on missed pings

- **WHEN** no packet is received within the watchdog window (60 seconds by default)
- **THEN** the device marks the link as dead, closes the L2CAP channel, and transitions to "out of range" state with a last-seen timestamp

#### Scenario: Any packet resets the watchdog

- **WHEN** any valid packet (not just ping) is received
- **THEN** the watchdog timer resets

### Requirement: TLS re-handshake on reconnect

Every reconnection performs a full mutual TLS handshake, verifying pinned certificate fingerprints. There is no session resumption or cached TLS state across disconnects.

#### Scenario: Reconnect performs fresh TLS handshake

- **WHEN** the BLE link re-establishes after a disconnect
- **THEN** both devices perform a new mutual TLS handshake over the L2CAP channel, verifying the peer's certificate fingerprint against the pinned value

### Requirement: Kitchen test validation

The reconnect mechanism is validated by the kitchen test: 10 consecutive walk-away-and-return cycles with no manual intervention.

#### Scenario: Kitchen test passes

- **WHEN** the phone is carried out of BLE range and brought back 10 times consecutively
- **THEN** each time the link re-establishes automatically, the TLS handshake succeeds, and the Mac transitions back to "connected" state without the user touching either device

#### Scenario: Queued packets are dropped on reconnect

- **WHEN** the link re-establishes after being out of range
- **THEN** no packets queued during the disconnect are replayed; the session starts fresh (a notification that arrives 20 minutes late is noise)
