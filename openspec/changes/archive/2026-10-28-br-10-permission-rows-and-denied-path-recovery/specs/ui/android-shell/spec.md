## MODIFIED Requirements

### Requirement: Redesigned main screen

The main screen SHALL present a status card showing the connection state, the paired Mac's name (when paired), and the last-seen time, plus grouped sections for Notifications, Clipboard, and Permissions. The Notifications section SHALL provide an entry point to the notification-filter screen. The layout SHALL use Material Components via Android Views (no Jetpack Compose).

The status card SHALL state the link's condition once. It SHALL NOT carry a decorative
application icon, which tells a user already inside the app nothing it does not already know.

#### Scenario: Status card reflects connection state

- **WHEN** the main screen is shown
- **THEN** a status card displays the current connection state and, when connected, the paired Mac name and last-seen time

#### Scenario: Entry point to notification filter

- **WHEN** the user taps the Notifications section
- **THEN** the notification-filter screen opens

#### Scenario: No launcher glyph inside the app

- **WHEN** the main screen is shown in any link state
- **THEN** the status card contains no launcher-icon image

## ADDED Requirements

### Requirement: Opening the app presents no modal about the link

Opening or resuming the Bridge main screen SHALL NOT present a dialog about the link's state.
Any guidance about a link that is not working SHALL appear inline on the status card, where the
state itself is shown, and SHALL be dismissible without blocking the screen.

Rationale: the state pill, the device line and the top-bar action already name the same state;
a modal makes it a fourth statement and an obstacle, and it reappears on every resume.

Platform constraint: Android only. The Mac panel is transient and is covered by its own
capability.

#### Scenario: Resuming with a broken link shows no dialog

- **WHEN** the user opens Bridge while the link is down, unverified, unpaired, or Bluetooth is
  off
- **THEN** no dialog is presented, and the screen is immediately usable

#### Scenario: Guidance appears inline instead

- **WHEN** the link is in a state that needs the user's attention
- **THEN** the status card shows one line of guidance for that state together with the action
  that addresses it

#### Scenario: Guidance tracks the state live

- **WHEN** the link state changes while the screen is open
- **THEN** the inline guidance updates or disappears to match, without the user reopening the
  screen

#### Scenario: A working link shows no guidance

- **WHEN** the link is connected and verified
- **THEN** the status card shows no guidance line
