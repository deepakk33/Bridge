## ADDED Requirements

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

## REMOVED Requirements

### Requirement: No Mac-side or protocol change

**Reason**: Written when the filter was entirely a phone-side concern, this requirement forbade adding a packet
type or changing Mac-side presentation. Both are now required: the Mac must state how many apps are mirroring,
and no existing packet can carry that value honestly. The part worth keeping — that filtering itself happens on
the phone and the Mac never filters — is restated as "Filtering happens on the phone" above, which also records
why the count travels in a type of its own.

**Migration**: No data or wire migration. The `bridge.notification` packet type is unchanged, and a Mac that has
not received the new `bridge.notification.filter` packet reports the count as unknown rather than failing, so an
older Mac paired with a newer phone keeps mirroring notifications exactly as before.
