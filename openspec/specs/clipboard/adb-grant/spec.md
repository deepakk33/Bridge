# clipboard/adb-grant Specification

## Purpose
ADB grant of `READ_LOGS` and `READ_CLIPBOARD_IN_BACKGROUND` permissions to the Bridge Android app, enabling phone-to-Mac clipboard sync in the background. Without this grant, clipboard sync degrades to Mac-to-phone only (plus foreground phone-to-Mac). A shell script in the repo handles the one-time grant and includes verification.

## Requirements

### Requirement: ADB grant script grants both permissions
The script `scripts/adb-grant-clipboard.sh` runs `pm grant` for both required permissions over ADB.

#### Scenario: USB ADB grant
- **WHEN** the user connects the phone via USB with ADB enabled and runs `scripts/adb-grant-clipboard.sh`
- **THEN** both `android.permission.READ_LOGS` and `android.permission.READ_CLIPBOARD_IN_BACKGROUND` are granted to the Bridge package, and the script prints confirmation for each

#### Scenario: Wireless ADB grant
- **WHEN** the user has wireless ADB enabled and runs `scripts/adb-grant-clipboard.sh` with the phone's IP:port
- **THEN** the same two permissions are granted and confirmed

#### Scenario: ADB not connected
- **WHEN** the user runs the script but no device is reachable via ADB
- **THEN** the script prints a clear error explaining how to enable ADB (USB or wireless) and exits with a non-zero code

#### Scenario: Bridge app not installed
- **WHEN** ADB is connected but the Bridge package is not installed on the device
- **THEN** the script prints an error naming the expected package and exits with a non-zero code

---

### Requirement: ADB grant verification check
The script verifies the grant is active after applying it, and can be run standalone to check status.

#### Scenario: Verify grant after applying
- **WHEN** the grant commands succeed
- **THEN** the script runs `pm list permissions` or `dumpsys package` to confirm both permissions are granted and prints a success summary

#### Scenario: Check-only mode
- **WHEN** the user runs `scripts/adb-grant-clipboard.sh --check`
- **THEN** the script reports whether each permission is currently granted without modifying anything

---

### Requirement: Grant survives reboot but not factory reset
The ADB grant persists across normal device reboots but is lost on factory reset or phone replacement.

#### Scenario: Reboot preserves grant
- **WHEN** the phone reboots after the ADB grant was applied
- **THEN** both permissions remain granted and phone-to-Mac clipboard sync continues working

#### Scenario: Factory reset loses grant
- **WHEN** the phone is factory reset
- **THEN** both permissions are revoked, and the Bridge app falls back to degraded mode

---

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

#### Scenario: Background watcher running, UI shows full sync
- **WHEN** a background clipboard watcher is running and the link is up
- **THEN** the clipboard card shows sync as working in both directions, in the normal text
  colour, with no warning

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
