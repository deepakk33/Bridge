# ui/mac-status-panel Specification

## Purpose
Define the Mac menu bar status panel that shows connection state and last-packet time, providing the observability foundation required by the PRD so that silent failures are visible to the user.

## Requirements

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

### Requirement: Menu bar dropdown content

The panel provides essential status information and controls. Content that reports a healthy state and offers
the user no decision SHALL be omitted rather than displayed, so that the panel's contents are always
either actionable or informative about a problem.

#### Scenario: Dropdown shows device info when connected

- **WHEN** the user clicks the menu bar icon while connected
- **THEN** the panel shows the paired device name, connection state, and protocol version

#### Scenario: Dropdown shows unpair option

- **WHEN** the user clicks the menu bar icon while paired
- **THEN** the panel includes an "Unpair" option that triggers the unpair flow

#### Scenario: Dropdown shows quit option

- **WHEN** the user clicks the menu bar icon
- **THEN** the panel includes a "Quit Bridge" option

#### Scenario: Send action is the panel's primary control

- **WHEN** the panel is open and a file can be sent
- **THEN** the send control is styled as the panel's primary action and is distinguishable at a glance from the
  status labels around it

---

### Requirement: Pairing prompt UI

When a pair request arrives, the Mac SHALL present the confirmation inside the menu bar panel itself, above the
panel's other content, so that it is visible as soon as the panel is opened and requires no second interaction
to reach.

Platform constraint: the panel is a non-activating window, so a separately presented modal attached to it cannot
be relied upon to appear or accept input. The confirmation is therefore part of the panel's own content.

Packet types: `bridge.pair` (request and response).

#### Scenario: Pair request prompt appears

- **WHEN** a `bridge.pair` request with `pair: true` is received from an unpaired device and the user opens the
  menu bar panel
- **THEN** the top of the panel shows the requesting device name and its certificate fingerprint with "Accept"
  and "Reject" controls, without the user having to open anything further

#### Scenario: Pair request is visible without reopening the panel

- **WHEN** a `bridge.pair` request arrives while the panel is already open
- **THEN** the confirmation appears in the open panel

#### Scenario: Pair prompt dismissed on timeout

- **WHEN** 30 seconds elapse without user action on a pair confirmation that is on screen
- **THEN** the confirmation is dismissed and the pair request is rejected

#### Scenario: Panel remains usable while a request is pending

- **WHEN** a pair confirmation is displayed
- **THEN** the rest of the panel's status content remains visible below it

### Requirement: Panel renders as a window, not a menu

The Mac menu bar panel SHALL render as a window-style popover with its own layout, not as a system menu whose
rows are flattened into individual menu items.

Platform constraint: SwiftUI's `MenuBarExtra` defaults to a menu presentation, under which multi-element rows are
split across separate items, non-button content is drawn as disabled, drop targets are inert and modal
presentation is impossible. The window presentation is what makes every other requirement in this capability
achievable.

#### Scenario: Composite rows stay on one line

- **WHEN** the user opens the menu bar panel and a permission needs attention
- **THEN** that permission's indicator, name, status and action appear together as a single row, not as separate
  stacked entries

#### Scenario: Informational text is not drawn as disabled

- **WHEN** the panel displays status text alongside interactive controls
- **THEN** the interactive controls are visually distinguishable from the surrounding labels, and the panel's
  primary action reads as enabled whenever it is enabled

#### Scenario: Panel width is stable

- **WHEN** the panel is opened in any connection state
- **THEN** it presents at a fixed width, and its width does not change as the content of individual rows changes

---

### Requirement: The panel does not claim a drop target it cannot offer

The menu bar panel is dismissed as soon as another application takes key focus, so pressing the mouse on a file
in Finder closes it before a drag can begin, and dragging over the menu bar icon does not spring it open. A drop
target on the panel is therefore unreachable in normal use. The panel SHALL NOT display instructional copy about
dragging a file onto it, and SHALL NOT present a drop affordance it cannot honour.

Verified on a MacBook Air M3: with the panel open, starting a drag in Finder dismissed it; hovering the dragged
file over the menu bar icon opened nothing on two attempts.

Sending a file from the Mac remains available through the panel's own send action, which is unaffected.

#### Scenario: No drag instruction is shown

- **WHEN** the user opens the panel in any connection state
- **THEN** the panel shows no text inviting the user to drag a file onto it

#### Scenario: Sending a file is still offered

- **WHEN** the phone is connected and the user opens the panel
- **THEN** the panel offers a send control that opens a file chooser and sends the chosen file
