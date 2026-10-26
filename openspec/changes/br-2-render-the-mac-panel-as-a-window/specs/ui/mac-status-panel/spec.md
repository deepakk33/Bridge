## ADDED Requirements

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

### Requirement: File drop onto the open panel

The Mac panel SHALL accept a file dragged onto it and send that file to the paired phone, using the same
transport selection as the panel's own send action.

#### Scenario: Dropped file is sent

- **WHEN** the user drags a file from Finder onto the open panel while the phone is connected
- **THEN** the file is sent to the phone over the tier the transport ladder selects, and the panel reports the
  transfer's progress and outcome

#### Scenario: Drop affordance matches reality

- **WHEN** the panel displays instructional copy about dragging a file
- **THEN** that copy describes the surface that actually accepts the drop

## MODIFIED Requirements

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
