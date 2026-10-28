## ADDED Requirements

### Requirement: Background phone-to-Mac clipboard is unobtainable on Android 16
On Android 16 (One UI 8.5) only the focused window or the active IME may read the clipboard.
The permission that lifts that rule, `READ_CLIPBOARD_IN_BACKGROUND`, is role-managed: `pm grant`
refuses it with `SecurityException: Permission ... is managed by role`, and no `appops` mode
substitutes for it. A sideloaded Bridge build therefore SHALL NOT claim, offer, or depend on
background clipboard reads. Phone-to-Mac sync is manual, permanently and by platform design, and
the app SHALL NOT schedule a recurring clipboard read, since such a read can only return nothing
while still costing battery. Mac-to-phone sync is unaffected: writing to the phone's own
clipboard from a received packet needs no permission.

#### Scenario: No recurring read is ever scheduled
- **WHEN** the Bridge foreground service starts on Android 16
- **THEN** no recurring clipboard read is scheduled for the lifetime of the service, and the
  reason is logged once

#### Scenario: Mac-to-phone is unaffected
- **WHEN** the Mac clipboard changes while the Bridge app is backgrounded
- **THEN** the phone's clipboard is updated from the received packet as normal

#### Scenario: Backgrounded phone sends nothing
- **WHEN** the user copies text on the phone while Bridge is in the background
- **THEN** the OS denies the read, and no `bridge.clipboard` packet is sent

#### Scenario: A new phone needs no setup step
- **WHEN** the user installs Bridge on a new phone
- **THEN** both phone-to-Mac triggers work immediately, with no grant, script, adb command or
  setup step anywhere in the product

#### Scenario: Nothing instructs the user to obtain the grant
- **WHEN** the user looks anywhere Bridge can speak to them — the permission list, the clipboard
  card, the repository scripts, the README or the specs
- **THEN** no text names an adb command, a grant or a script for background clipboard, because
  none can succeed

---

### Requirement: Phone-to-Mac clipboard sends are user-initiated
A phone-to-Mac send SHALL occur only when the user asks for it: the in-app send control or the
Quick Settings tile. The app SHALL NOT send the clipboard as a side effect of being opened or
brought to the foreground, and SHALL NOT send a clip that Bridge itself wrote from a Mac packet.

The one exception is the `bridge.clipboard.connect` packet required by "Reconnect exchanges
bridge.clipboard.connect with timestamp", which carries the phone's current clip on every
connection and is therefore not user-initiated. Whether that exchange should be gated the same
way is an open question, tracked as BR-16; this requirement does not resolve it.

#### Scenario: Opening the app sends nothing
- **WHEN** the user opens or returns to the Bridge app while connected, having copied
  something on the phone
- **THEN** no `bridge.clipboard` packet is sent, and the Mac clipboard is unchanged

#### Scenario: Explicit send works
- **WHEN** the user taps the in-app send control or the Quick Settings tile
- **THEN** the current clipboard text is sent to the Mac and the Mac can paste it

#### Scenario: Change originated from Mac is not echoed
- **WHEN** the clipboard change was caused by receiving a `bridge.clipboard` packet from the Mac
  — Bridge itself wrote the clip — and a send is then triggered
- **THEN** no `bridge.clipboard` packet carrying that same clip is sent back to the Mac

#### Scenario: Reconnect still exchanges clips
- **WHEN** the link is re-established
- **THEN** the phone sends its current clip in a `bridge.clipboard.connect` packet as the
  reconnect requirement specifies, capped per the 10 KB rule — this change does not alter
  that behaviour

#### Scenario: Incoming Mac clip still lands on focus
- **WHEN** the app regains focus and a Mac clip is pending from while it was backgrounded
- **THEN** the pending clip is still written to the phone clipboard — removing the outbound
  send does not disable the inbound flush

---

### Requirement: Clipboard card reflects the current sync state
The Android app clearly communicates the clipboard sync state. The clipboard card SHALL
reflect the current state rather than a fixed message, and SHALL use its warning colour only
for a condition the user can act on — a down link, or a clip that had to be truncated. Manual
sync is the permanent, correct state on Android 16, so it SHALL NOT be shown as a warning: a
colour that never clears trains the user to ignore the honest ones. All of the card's text
SHALL come from string resources, and it SHALL NOT instruct the user to run a command the OS
refuses.

#### Scenario: No background watcher, UI explains manual sync
- **WHEN** the Bridge app starts and no background clipboard watcher is running
- **THEN** the clipboard card states that sync is manual, names the two phone-to-Mac triggers,
  gives the OS reason it is manual, and renders in the normal text colour with no warning

#### Scenario: Link down is distinguished from manual sync
- **WHEN** the link to the Mac is down
- **THEN** the card says the link is down rather than describing the sync mode, in the warning colour

#### Scenario: Truncated clip is reported on the card
- **WHEN** the most recent phone-to-Mac send was truncated at the 10 KB cap
- **THEN** the card states that the clip was truncated and gives the original size, in the
  warning colour, until a later send succeeds whole

#### Scenario: A new phone needs no setup to reach the manual state
- **WHEN** the user sets up Bridge on a new phone
- **THEN** the app shows the manual-sync card, and the two phone-to-Mac triggers work with no
  grant, script or setup step

## REMOVED Requirements

### Requirement: Android clipboard change sends bridge.clipboard to Mac (with ADB grant)
**Reason**: Its only trigger is `onPrimaryClipChanged` firing for a background service with the
ADB grant active. That grant is role-managed on Android 16 and cannot be held by a sideloaded
build, so the requirement's single send scenario is unreachable and its title states a
precondition the product can never satisfy.
**Migration**: The part that survives — Bridge never echoes a clip it wrote itself from a Mac
packet — is restated as the "Change originated from Mac is not echoed" scenario of
"Phone-to-Mac clipboard sends are user-initiated" above, which is where the reachable send
triggers are now specified.

### Requirement: Phone-to-Mac clipboard sends are user-initiated or grant-backed
**Reason**: Its rule is right but its name and one of its two limbs are not. "Grant-backed" is
not a state this app can be in, so the requirement offers an alternative to user initiation that
does not exist, and a reader could satisfy it by shipping a background watcher.
**Migration**: Replaced by "Phone-to-Mac clipboard sends are user-initiated" above, which keeps
every scenario, keeps the BR-16 exception clause unchanged, and drops only the grant limb.

### Requirement: Android 16 background clipboard access is gated on the ADB grant
**Reason**: "Gated on the grant" describes a switch the user can flip. There is no switch: the
grant is role-managed and unobtainable, and `READ_CLIPBOARD_IN_BACKGROUND` is not even in the
Android manifest, so `checkSelfPermission` could never report it granted. Its "Watcher runs with
the grant" scenario specifies behaviour no Android 16 build can exhibit.
**Migration**: Replaced by "Background phone-to-Mac clipboard is unobtainable on Android 16"
above, which keeps the no-recurring-read guarantee and the denied-background-read scenario and
states the limitation as permanent.
