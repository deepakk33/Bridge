## MODIFIED Requirements

### Requirement: Three connection states in menu bar

The menu bar icon and the panel's status line SHALL render the link **health** state defined in
`link/health`, not the raw transport state, and SHALL distinguish every health state the user can
act on differently: connected, verifying, stale, not responding, out of range, Bluetooth off and
disconnected.

Platform: Mac only.

#### Scenario: Connected state

- **WHEN** the peer is verified and packets are flowing
- **THEN** the menu bar shows a connected indicator and the panel shows the paired device name

#### Scenario: Verifying state

- **WHEN** an L2CAP channel is open but the peer has not yet been verified against the trust store
- **THEN** the panel says the link is verifying and does not say "Connected"

#### Scenario: Stale state

- **WHEN** no packet has arrived for at least the stale threshold while the peer is verified
- **THEN** the panel shows a warning indicator with the last-seen time, and does not show a plain
  "Connected"

#### Scenario: Not responding state

- **WHEN** the link health is "Not responding"
- **THEN** the panel shows "Not responding" together with the restart guidance from `link/health`,
  and the menu bar indicator differs from both the connected and the out-of-range indicator

#### Scenario: Out of range state

- **WHEN** the link health is "Out of range"
- **THEN** the panel shows "Out of range" with the last-seen time

#### Scenario: Bluetooth off state

- **WHEN** the Mac's Bluetooth adapter is powered off
- **THEN** the menu bar shows a disabled indicator and the panel shows "Bluetooth off"

---

### Requirement: Last-packet timestamp

When the link is not healthy, the panel displays how long ago the last packet was received, so the
user knows how long the link has been down. The last-seen line SHALL be shown for every unhealthy
state in which a packet has previously been received — stale, not responding and out of range —
not only for out of range.

#### Scenario: Last-seen time shown when out of range

- **WHEN** the link health is "Out of range"
- **THEN** the panel shows "Last seen: <relative time>"

#### Scenario: Last-seen time shown when not responding

- **WHEN** the link health is "Not responding"
- **THEN** the panel shows the last-seen time alongside the restart guidance

#### Scenario: Last-seen time updates while disconnected

- **WHEN** the link remains down over time
- **THEN** the last-seen display updates on the health re-evaluation cadence to reflect elapsed time

#### Scenario: Last-seen time clears on reconnect

- **WHEN** the link re-establishes and health returns to "Connected"
- **THEN** the last-seen time is no longer displayed
