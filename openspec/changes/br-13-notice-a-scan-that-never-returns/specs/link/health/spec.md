## MODIFIED Requirements

### Requirement: Link health is the single reported state

The Mac SHALL classify the link into exactly one health state at a time and SHALL report that
state — not the underlying transport state — everywhere the link is shown to the user.

The states are:

| State | Meaning |
|---|---|
| Connected | the peer is verified and a packet has been seen within the stale threshold |
| Verifying | a channel is open but the peer is not yet verified against the trust store |
| Stale | the peer is verified but no packet has arrived for at least the stale threshold |
| Not responding | no packet for at least the dead threshold while the BLE peripheral is still connected, or the peer terminated the connection from its side |
| Searching | no peripheral is connected, the Mac is scanning, and no scan watchdog period has elapsed yet |
| Out of range | the BLE link dropped on a supervision timeout, or the Mac has been scanning without discovering anything for at least one scan watchdog period |
| Bluetooth off | the Mac's Bluetooth adapter is off, unauthorized or unsupported |
| Disconnected | the user or the app closed the link deliberately |

Platform: Mac only. Android reports its own state through `LinkState.resolve`, which this
classification deliberately mirrors.

#### Scenario: One state at a time

- **WHEN** the link is in any condition
- **THEN** exactly one health state applies, and the menu bar icon, the panel's status line and
  any guidance text all derive from that same state

#### Scenario: Transport state is not shown directly

- **WHEN** the underlying transport state would say one thing and health another — for example
  an open channel to an unverified peer
- **THEN** the user sees the health state ("Verifying"), never the transport state ("Connected")

#### Scenario: Looking is not the same as having looked

- **WHEN** the Mac has just started scanning and nothing has been discovered yet
- **THEN** the state is "Searching", and it becomes "Out of range" only once the Mac has been
  looking for at least one scan watchdog period with nothing found

---

### Requirement: A dead phone service is not reported as distance

The Mac SHALL NOT attribute a link failure to distance unless the evidence supports distance.
Packets ceasing while the BLE peripheral connection is still up, or a disconnect that the peer
terminated, SHALL be reported as **Not responding**; only a supervision-timeout disconnect, or a
scan that has run for at least one watchdog period without discovering anything, SHALL be
reported as **Out of range**.

A scan that has not yet run for a watchdog period is evidence of nothing and SHALL be reported
as **Searching**.

Platform constraint: Core Bluetooth reports a remote-terminated disconnect and a
supervision-timeout disconnect with different `CBError` codes; this distinction is the only
signal available, since a force-stopped Android app stops advertising exactly as a departed
phone does.

#### Scenario: Phone service is force-stopped

- **WHEN** the Bridge service on the phone is force-stopped while the phone is on the desk
- **THEN** the Mac reports "Not responding", not "Out of range"

#### Scenario: Phone is carried out of range

- **WHEN** the phone is carried beyond BLE range and the link drops on a supervision timeout
- **THEN** the Mac reports "Out of range"

#### Scenario: Packets stop with the peripheral still connected

- **WHEN** no packet of any kind has arrived for the dead threshold but the BLE peripheral
  connection is still open
- **THEN** the Mac reports "Not responding"

#### Scenario: A fresh scan is not called out of range

- **WHEN** the app has just launched, or the scan has just been restarted, and nothing has been
  discovered yet
- **THEN** the Mac reports "Searching", not "Out of range"

---

### Requirement: A problem state carries its own guidance

Every health state that represents a problem the user can act on SHALL be accompanied by one
line of guidance saying what to do, not only what broke. Healthy and transient states SHALL show
no guidance line.

#### Scenario: Not responding shows restart guidance

- **WHEN** the health state is "Not responding"
- **THEN** the panel shows "The phone's Bridge service may have been stopped by battery
  management. Open Bridge on the phone to restart."

#### Scenario: Out of range shows what will happen

- **WHEN** the health state is "Out of range"
- **THEN** the panel says the phone will reconnect automatically when back in range

#### Scenario: Bluetooth off points at the fix

- **WHEN** the health state is "Bluetooth off"
- **THEN** the panel says Bluetooth is off and where to re-enable it

#### Scenario: A healthy link shows no guidance

- **WHEN** the health state is "Connected"
- **THEN** no guidance line is shown

#### Scenario: Searching is transient and shows no guidance

- **WHEN** the health state is "Searching"
- **THEN** no guidance line is shown, because there is nothing for the user to do while the Mac
  is still looking
