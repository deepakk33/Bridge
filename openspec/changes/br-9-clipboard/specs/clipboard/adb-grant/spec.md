## MODIFIED Requirements

### Requirement: Degraded mode without ADB grant
When the ADB grant is absent, clipboard sync degrades gracefully rather than failing
entirely, and it degrades quietly: the app SHALL NOT keep a recurring clipboard read
running that the OS is guaranteed to deny.

#### Scenario: Mac-to-phone works without grant
- **WHEN** the ADB grant has not been applied (or was lost)
- **THEN** Mac-to-phone clipboard sync works normally (Android can always write to its own clipboard from a received packet)

#### Scenario: No recurring read without the grant
- **WHEN** the ADB grant is absent and the Bridge foreground service is running
- **THEN** no recurring clipboard read is scheduled for the lifetime of the service, so the
  service costs no battery for a read that cannot succeed

#### Scenario: Phone-to-Mac unavailable in background without grant
- **WHEN** the ADB grant is absent and the Bridge app is in the background
- **THEN** Android cannot read the clipboard, so no `bridge.clipboard` packets are sent from phone to Mac

#### Scenario: Phone-to-Mac available in foreground without grant
- **WHEN** the ADB grant is absent and the user opens the Bridge app (foreground)
- **THEN** Android can read the clipboard while in the foreground, and the user can send the
  current clipboard to the Mac with the in-app send control or the quick-settings tile

#### Scenario: Quick-settings tile triggers clipboard send
- **WHEN** the ADB grant is absent and the user taps the Bridge quick-settings tile
- **THEN** Bridge momentarily reads the clipboard (the tile tap brings it into a context where reading is allowed) and sends a `bridge.clipboard` packet to the Mac

---

### Requirement: UI indicates degraded state
The Android app clearly communicates the clipboard sync state and provides instructions for
the ADB grant. The clipboard card SHALL reflect the current state rather than a fixed
message, and SHALL use its warning colour only when something is wrong — an absent grant, a
down link, or a clip that had to be truncated. All of its text SHALL come from string
resources.

#### Scenario: No ADB grant, UI shows degraded
- **WHEN** the Bridge app starts and detects the ADB grant is absent
- **THEN** the clipboard card shows sync as manual / "Mac to phone automatic", in the warning
  colour, with the reason and the ADB grant command reachable from the app

#### Scenario: ADB grant active, UI shows full sync
- **WHEN** the Bridge app starts and detects the clipboard grant is active and the link is up
- **THEN** the clipboard card shows sync as working in both directions, in the normal text
  colour, with no warning

#### Scenario: Link down is distinguished from grant missing
- **WHEN** the grant is active but the link to the Mac is down
- **THEN** the card says the link is down rather than blaming the grant, in the warning colour

#### Scenario: Truncated clip is reported on the card
- **WHEN** the most recent phone-to-Mac send was truncated at the 10 KB cap
- **THEN** the card states that the clip was truncated and gives the original size, in the
  warning colour, until a later send succeeds whole

#### Scenario: Grant lost after phone replacement
- **WHEN** the user sets up Bridge on a new phone without running the ADB grant
- **THEN** the app shows the degraded-mode card and the user can follow the instructions to re-run the grant script
