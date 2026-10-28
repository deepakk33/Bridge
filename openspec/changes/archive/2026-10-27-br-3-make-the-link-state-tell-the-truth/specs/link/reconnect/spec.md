## MODIFIED Requirements

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
