## Purpose

Defines the observable shell of the Android Bridge app: a redesigned main screen, a top-bar connect action that adapts to link state, a purpose-built launcher icon, and a minimized foreground-service notification.

## ADDED Requirements

### Requirement: Minimized foreground-service notification

The foreground-service notification SHALL use a channel at `IMPORTANCE_MIN` and SHALL present no title or body text, only a small status icon. The notification SHALL remain present whenever the Bridge foreground service runs (required by Android for a `connectedDevice` foreground service) but SHALL be visually unobtrusive (collapsed, silent, no heads-up, low in the shade).

#### Scenario: Service running shows a silent minimal notification

- **WHEN** the Bridge foreground service is active
- **THEN** a persistent notification exists at minimum importance with an icon and no title/text
- **AND** it makes no sound, shows no heads-up, and sorts to the bottom of the shade

#### Scenario: Notification is not removable while the link is on

- **WHEN** the user attempts to dismiss the foreground-service notification
- **THEN** it cannot be swiped away while the service runs (ongoing), preserving the background link

### Requirement: Smart connect action in the top app bar

The main screen SHALL present a top app bar containing a single primary connect action whose label and behavior adapt to the current link state:
- No trusted Mac is paired → action reads **Pair Mac** and starts the pairing/trust handshake.
- A Mac is paired but the link is down → action reads **Reconnect** and triggers a fresh BLE scan/advertise attempt.
- The link is connected → action reads **Connected** and performs no action (status only).

The action SHALL reflect state changes without requiring the user to leave and reopen the screen.

#### Scenario: Unpaired device offers pairing

- **WHEN** no trusted Mac exists in the trust store
- **THEN** the top-bar action reads "Pair Mac"
- **AND** tapping it begins the pairing handshake

#### Scenario: Paired but disconnected offers reconnect

- **WHEN** a trusted Mac exists but the link is currently down
- **THEN** the action reads "Reconnect"
- **AND** tapping it starts a fresh connection attempt

#### Scenario: Connected shows status only

- **WHEN** the link is connected
- **THEN** the action reads "Connected" and tapping it does nothing

#### Scenario: Action updates live on state change

- **WHEN** the link transitions from disconnected to connected while the screen is open
- **THEN** the action label updates from "Reconnect" to "Connected" without reopening the screen

### Requirement: Redesigned main screen

The main screen SHALL present a status card showing the connection state, the paired Mac's name (when paired), and the last-seen time, plus grouped sections for Notifications, Clipboard, and Permissions. The Notifications section SHALL provide an entry point to the notification-filter screen. The layout SHALL use Material Components via Android Views (no Jetpack Compose).

#### Scenario: Status card reflects connection state

- **WHEN** the main screen is shown
- **THEN** a status card displays the current connection state and, when connected, the paired Mac name and last-seen time

#### Scenario: Entry point to notification filter

- **WHEN** the user taps the Notifications section
- **THEN** the notification-filter screen opens

### Requirement: Purpose-built launcher icon

The app SHALL ship an adaptive launcher icon with a distinct foreground layer and background layer, not a reuse of the monochrome notification icon. A separate monochrome icon SHALL remain for the status/notification small-icon slot.

#### Scenario: Launcher shows the app icon

- **WHEN** the user views the app in the launcher
- **THEN** the adaptive Bridge icon is shown with its own foreground and background layers

#### Scenario: Status icon stays monochrome

- **WHEN** the foreground-service or a mirrored notification is posted
- **THEN** its small icon uses the monochrome status glyph, not the full launcher icon

### Requirement: No protocol or Mac change

This capability SHALL NOT add or change any packet type and SHALL NOT change Mac-side behavior. The connect action SHALL reuse the existing pairing and reconnect mechanisms.

#### Scenario: Connect action reuses existing mechanisms

- **WHEN** the user triggers Pair or Reconnect
- **THEN** the existing iter-2 pairing/trust and BLE reconnect paths run, with no new packet types
