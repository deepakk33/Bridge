# notifications/filter Specification

## Purpose
Gives the phone owner control over which apps' notifications are mirrored to the Mac, using a deny-by-default per-app allowlist plus a global mute, so the Mac only surfaces notifications the user has explicitly chosen.

## Requirements

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

### Requirement: The all-denied state is stated, and escapable in one step

Because forwarding is deny-by-default and the allow-set starts empty, a new install mirrors nothing. That state
SHALL be stated plainly rather than presenting as an ordinary empty list, and the filter screen SHALL offer a
single action that allows every app currently in the seen-apps set.

This is the alternative to flipping the default. Allowing everything by default was rejected: it would mirror
one-time passcodes and banking alerts to the Mac before the user had chosen anything, over a control channel
that is not yet encrypted. The user keeps the choice; the product stops hiding that a choice is needed.

#### Scenario: Nothing is allowed yet

- **WHEN** the user opens the filter screen and no app is allowed
- **THEN** the screen states that no notifications are being mirrored and that nothing will reach the Mac until
  an app is enabled, and offers an action to enable every seen app

#### Scenario: Enabling every seen app in one step

- **WHEN** the user takes that action
- **THEN** every app in the seen-apps set becomes allowed, the list reflects it, and a notification from any of
  those apps is forwarded to the Mac while master mute is off

#### Scenario: Nothing has been seen yet

- **WHEN** no app has posted a notification since Bridge gained notification access, so the seen-apps set is
  empty
- **THEN** the screen says that apps appear here once they notify, and does not offer an enable-all action that
  would do nothing

#### Scenario: An upgrade does not silently start mirroring

- **WHEN** an existing install that has never allowed an app is upgraded to a version carrying this behaviour
- **THEN** the allow-set is still empty and nothing is mirrored until the user acts; the seen-apps set is not
  used to seed it

---

### Requirement: Both home screens state how many apps are mirroring

The phone's home screen and the Mac's status panel SHALL each state how many apps are currently mirroring, so
that "nothing is arriving" is distinguishable from "nothing is being sent" without opening a settings screen.

#### Scenario: Some apps are mirroring

- **WHEN** apps are allowed and master mute is off
- **THEN** both home screens state how many apps are mirroring

#### Scenario: No apps are mirroring

- **WHEN** no app is allowed
- **THEN** both home screens say that no apps are mirroring, rather than showing a count of zero without
  explanation or showing nothing at all

#### Scenario: Master mute is on

- **WHEN** master mute is on, whatever the allow-set contains
- **THEN** both home screens say mirroring is paused, rather than reporting the allowed count as if it were
  active

#### Scenario: The count follows a change made on the phone

- **WHEN** the user allows or disallows an app, or changes master mute, while the link is up
- **THEN** both home screens reflect the new state without the user reopening either screen

#### Scenario: The Mac has no filter state yet

- **WHEN** the Mac has not received the phone's filter state during this connection
- **THEN** the Mac states that the count is unknown rather than displaying a stale or invented number

### Requirement: Filtering happens on the phone

All filtering SHALL occur on the phone before a `bridge.notification` packet is sent: the Mac SHALL NOT filter,
and the `bridge.notification` packet type SHALL NOT change. The Mac is told the resulting count so it can
report it, which is reporting rather than filtering.

A dedicated packet type carries that count. It is not folded into `bridge.identity`, which describes which
device this is and is sent once per connection, whereas the count changes whenever the user toggles an app.

#### Scenario: Mac presents whatever it receives

- **WHEN** the Mac receives a `bridge.notification` packet
- **THEN** it presents it using the existing iter-3 flow, with no filtering of its own and no awareness of which
  apps are allowed

#### Scenario: Filter state is reported, not negotiated

- **WHEN** the phone's filter state changes or a connection is established
- **THEN** the phone sends the resulting count to the Mac, and the Mac SHALL NOT be able to change the phone's
  filter state

#### Scenario: Handling the filter-state packet is idempotent

- **WHEN** the Mac receives the same filter state more than once
- **THEN** the result is the same as receiving it once
