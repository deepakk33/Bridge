## Purpose

Gives the phone owner control over which apps' notifications are mirrored to the Mac, using a deny-by-default per-app allowlist plus a global mute, so the Mac only surfaces notifications the user has explicitly chosen.

## ADDED Requirements

### Requirement: Deny-by-default forwarding gate

The Android notification mirror SHALL forward a posted notification to the Mac (as a `bridge.notification` packet) only if the notification's source app is in the allow-set AND the global master mute is off. A notification from an app not in the allow-set SHALL be dropped on the phone and SHALL NOT produce a `bridge.notification` packet. This gate applies in addition to the existing iter-3 filters (own package, ongoing, foreground-service, group-summary notifications are still skipped).

#### Scenario: Notification from a disallowed app

- **WHEN** an app that is not in the allow-set posts a non-ongoing notification
- **THEN** no `bridge.notification` packet is sent to the Mac
- **AND** the Mac shows nothing for that notification

#### Scenario: Notification from an allowed app

- **WHEN** an app in the allow-set posts a non-ongoing notification and master mute is off
- **THEN** a `bridge.notification` packet is sent exactly as in iter-3 (unchanged shape and content)

#### Scenario: Newly-seen app defaults to silent

- **WHEN** an app posts a notification for the first time and has never been toggled on
- **THEN** the notification is not forwarded
- **AND** the app is recorded in the seen-apps set so it can be toggled on later

### Requirement: Global master mute

The filter SHALL provide a single master mute. When master mute is on, NO notification SHALL be forwarded to the Mac regardless of per-app allow state. When master mute is off, per-app allow state governs forwarding. Master mute state SHALL persist across app and device restarts.

#### Scenario: Master mute suppresses an allowed app

- **WHEN** master mute is on and an app in the allow-set posts a notification
- **THEN** no `bridge.notification` packet is sent

#### Scenario: Turning master mute off restores per-app behavior

- **WHEN** master mute is turned off
- **THEN** subsequent notifications from allowed apps are forwarded again and disallowed apps remain silent

### Requirement: Seen-apps toggle list

The phone SHALL present a settings screen listing apps that have posted at least one notification since Bridge gained notification access (the seen-apps set). Each entry SHALL show the app's icon and human-readable label and an on/off control reflecting its current allow state. Toggling an entry SHALL add or remove that app from the allow-set and take effect for subsequently posted notifications. The list SHALL be built without requiring the `QUERY_ALL_PACKAGES` permission.

#### Scenario: App appears after it notifies

- **WHEN** an app posts its first notification while Bridge has notification access
- **AND** the user opens the filter settings screen
- **THEN** that app appears in the list with its icon and label, toggled off by default

#### Scenario: Enabling an app forwards its next notification

- **WHEN** the user toggles an app on in the list
- **AND** that app later posts a notification (master mute off)
- **THEN** the notification is forwarded to the Mac

#### Scenario: Disabling an app stops forwarding

- **WHEN** the user toggles a previously-allowed app off
- **AND** that app later posts a notification
- **THEN** the notification is not forwarded

### Requirement: Persistent filter state

The allow-set, master-mute flag, and seen-apps set SHALL be persisted on the phone and restored on app launch and after device restart. The allow-set read on the notification hot path SHALL be available synchronously (no blocking I/O in the notification callback).

#### Scenario: State survives restart

- **WHEN** the user has allowed a set of apps and set master mute
- **AND** the Bridge app or the device is restarted
- **THEN** the same apps remain allowed and the master-mute state is preserved

### Requirement: No Mac-side or protocol change

This capability SHALL NOT change the `bridge.notification` packet type, add new packet types, or change Mac-side presentation. All filtering SHALL occur on the phone before a packet is sent.

#### Scenario: Mac presents whatever it receives

- **WHEN** the Mac receives a `bridge.notification` packet
- **THEN** it presents it using the existing iter-3 flow with no awareness of the filter
