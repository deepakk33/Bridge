# battery/status Specification

## Purpose
The phone sends `bridge.battery` packets carrying current charge level and charging state. The Mac displays this information in the menu bar, giving the user an at-a-glance view of phone battery without touching the phone.

## Requirements

### Requirement: Android sends bridge.battery packets
The phone broadcasts its battery status over the secure channel both periodically and on state changes.

#### Scenario: Periodic battery update
- **WHEN** the phone is connected to the Mac
- **THEN** a `bridge.battery` packet with `currentCharge` (integer 0-100) and `isCharging` (boolean) is sent every 5 minutes

#### Scenario: Charge state changes
- **WHEN** the phone is plugged in or unplugged (ACTION_POWER_CONNECTED / ACTION_POWER_DISCONNECTED)
- **THEN** a `bridge.battery` packet is sent within 2 seconds of the state change

#### Scenario: Battery level crosses 10% threshold
- **WHEN** the battery level changes by 10 or more percentage points since the last sent packet
- **THEN** a `bridge.battery` packet is sent immediately rather than waiting for the next periodic interval

#### Scenario: Initial connection
- **WHEN** the BLE link is established (or re-established after going out of range)
- **THEN** a `bridge.battery` packet is sent within 1 second as part of the initial handshake, so the Mac has battery info from the moment of connection

#### Scenario: Link is down
- **WHEN** the BLE channel is disconnected
- **THEN** no battery packets are queued; the Mac retains the last known value and shows it as stale

---

### Requirement: Mac displays battery in menu bar
The Mac menu bar shows the phone's battery percentage and charging state alongside existing connection information.

#### Scenario: Battery info received
- **WHEN** the Mac receives a `bridge.battery` packet
- **THEN** the menu bar updates to show the battery percentage (e.g., "72%") and a charging indicator icon if `isCharging` is true

#### Scenario: Battery low warning
- **WHEN** the received `currentCharge` is 15 or below and `isCharging` is false
- **THEN** the battery display uses a warning style (e.g., red/orange tint) to draw attention

#### Scenario: No battery data yet
- **WHEN** the Mac is connected but has not yet received a `bridge.battery` packet
- **THEN** the battery area shows a placeholder (e.g., "--%" or is hidden) rather than displaying a stale or incorrect value

#### Scenario: Disconnected
- **WHEN** the BLE link drops
- **THEN** the battery display is hidden or greyed out, since the value is no longer current

#### Scenario: Menu bar layout
- **WHEN** battery info is displayed
- **THEN** the menu bar shows: connection state icon, battery percentage with optional charging icon, and these do not overflow or overlap with other menu bar items
