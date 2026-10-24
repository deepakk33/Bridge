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
When the ADB grant is absent, clipboard sync degrades gracefully rather than failing entirely.

#### Scenario: Mac-to-phone works without grant
- **WHEN** the ADB grant has not been applied (or was lost)
- **THEN** Mac-to-phone clipboard sync works normally (Android can always write to its own clipboard from a received packet)

#### Scenario: Phone-to-Mac unavailable in background without grant
- **WHEN** the ADB grant is absent and the Bridge app is in the background
- **THEN** Android cannot read the clipboard, so no `bridge.clipboard` packets are sent from phone to Mac

#### Scenario: Phone-to-Mac available in foreground without grant
- **WHEN** the ADB grant is absent and the user opens the Bridge app (foreground)
- **THEN** Android can read the clipboard while in the foreground, and a manual sync or quick-settings tile tap sends the current clipboard to the Mac

#### Scenario: Quick-settings tile triggers clipboard send
- **WHEN** the ADB grant is absent and the user taps the Bridge quick-settings tile
- **THEN** Bridge momentarily reads the clipboard (the tile tap brings it into a context where reading is allowed) and sends a `bridge.clipboard` packet to the Mac

---

### Requirement: UI indicates degraded state
The Android app clearly communicates the clipboard sync state and provides instructions for the ADB grant.

#### Scenario: No ADB grant, UI shows degraded
- **WHEN** the Bridge app starts and detects the ADB grant is absent
- **THEN** the settings screen shows clipboard sync as "Mac to phone only" with a note explaining the limitation and a link or instructions for running the ADB grant script

#### Scenario: ADB grant active, UI shows full sync
- **WHEN** the Bridge app starts and detects both ADB permissions are granted
- **THEN** the settings screen shows clipboard sync as "Both directions" with no warning

#### Scenario: Grant lost after phone replacement
- **WHEN** the user sets up Bridge on a new phone without running the ADB grant
- **THEN** the app shows the degraded-mode UI and the user can follow the instructions to re-run the grant script
