# ui/mac-status-panel Specification

## Purpose
Define the Mac menu bar status panel that shows connection state and last-packet time, providing the observability foundation required by the PRD so that silent failures are visible to the user.

## Requirements

### Requirement: Three connection states in menu bar

The menu bar icon and label reflect exactly three states: connected, out of range, and Bluetooth off.

#### Scenario: Connected state

- **WHEN** the BLE link is up, TLS handshake has succeeded, and packets are flowing
- **THEN** the menu bar shows a connected indicator (e.g., a filled icon) and the paired device name

#### Scenario: Out of range state

- **WHEN** the BLE link has dropped and a pending connect is active
- **THEN** the menu bar shows an out-of-range indicator (e.g., a dimmed or slashed icon) and the text "Out of range"

#### Scenario: Bluetooth off state

- **WHEN** the Mac's Bluetooth adapter is powered off
- **THEN** the menu bar shows a disabled indicator and the text "Bluetooth off"

### Requirement: Last-packet timestamp

When disconnected, the menu bar displays the time the last packet was received, so the user knows how long the link has been down.

#### Scenario: Last-seen time shown when out of range

- **WHEN** the link state is "out of range"
- **THEN** the menu bar dropdown shows "Last seen: <relative time>" (e.g., "Last seen: 2 minutes ago")

#### Scenario: Last-seen time updates while disconnected

- **WHEN** the link remains down over time
- **THEN** the last-seen display updates periodically (e.g., every 30 seconds) to reflect elapsed time

#### Scenario: Last-seen time clears on reconnect

- **WHEN** the link re-establishes and transitions to "connected"
- **THEN** the last-seen time is no longer displayed

### Requirement: Menu bar dropdown content

The dropdown menu provides essential status information and controls.

#### Scenario: Dropdown shows device info when connected

- **WHEN** the user clicks the menu bar icon while connected
- **THEN** the dropdown shows the paired device name, connection state, and protocol version

#### Scenario: Dropdown shows unpair option

- **WHEN** the user clicks the menu bar icon while paired
- **THEN** the dropdown includes an "Unpair" option that triggers the unpair flow

#### Scenario: Dropdown shows quit option

- **WHEN** the user clicks the menu bar icon
- **THEN** the dropdown includes a "Quit Bridge" option

### Requirement: Pairing prompt UI

When a pair request arrives, the Mac displays a prompt for the user to accept or reject.

#### Scenario: Pair request prompt appears

- **WHEN** a `bridge.pair` request with `pair: true` is received from an unpaired device
- **THEN** a system dialog or panel appears showing the device name and certificate fingerprint with "Accept" and "Reject" buttons

#### Scenario: Pair prompt dismissed on timeout

- **WHEN** 30 seconds elapse without user action on the pair prompt
- **THEN** the prompt is dismissed automatically and the pair request is rejected
