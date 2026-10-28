## ADDED Requirements

### Requirement: Android permission rows show only grants the user can obtain
The Android Bridge settings screen shows the state of the permissions and grants Bridge needs.
Every row it shows SHALL be one the user can actually reach Granted, through an action the row
itself offers — a row that is permanently limited, with no action that can change it, trains
the user to ignore the rows that still mean something. No row SHALL display an adb command or
any other instruction the OS refuses.

#### Scenario: All Android permissions granted
- **WHEN** the user opens Bridge settings and all grants are in place
- **THEN** each item shows green: Notification Access (enabled), Battery Optimization (unrestricted)

#### Scenario: Notification access revoked
- **WHEN** Notification Access has been disabled for Bridge
- **THEN** the settings screen shows it as "Disabled" with a red indicator and a button that opens the system Notification Access settings

#### Scenario: Battery optimization enabled
- **WHEN** Bridge is not excluded from battery optimization
- **THEN** the settings screen shows Battery Optimization as "Not Excluded" with a warning and a button to open Battery settings

#### Scenario: No clipboard grant row is offered
- **WHEN** the user opens Bridge settings on Android 16
- **THEN** the permission list contains no clipboard row, because background clipboard access
  is role-managed and unobtainable, and the clipboard card — not the permission list — is where
  the manual sync state is reported

#### Scenario: Every listed row carries an action that can succeed
- **WHEN** any row in the permission list is shown as not granted
- **THEN** the action it offers opens a system screen where the user can grant it, and no row
  is left displaying a command for the user to type

## REMOVED Requirements

### Requirement: Android permission status in settings
**Reason**: Two of its scenarios describe a clipboard row that cannot work. "All Android
permissions granted" requires an all-green state that includes "ADB Clipboard (granted)", which
no Android 16 build can reach — `READ_CLIPBOARD_IN_BACKGROUND` is role-managed and is not in the
manifest, so the row is pinned at Limited and the all-green scenario is untestable. "ADB
clipboard grant missing" goes further and requires the app to *display the adb command needed to
restore it*; verified on SM-S931B / API 36 on 2026-10-28, that command fails with
`SecurityException: Permission ... is managed by role`. BR-10 is about to render
`PermissionItem.detail` in the UI, which would have put that failing command on screen.
**Migration**: Replaced by "Android permission rows show only grants the user can obtain" above,
which keeps the Notification Access and Battery Optimization scenarios unchanged, states the
all-green condition over the two rows that remain, and replaces the clipboard scenarios with the
rule that produced the defect — every row must offer an action that can succeed. The manual
clipboard state is reported by the clipboard card, per "Clipboard card reflects the current sync
state" in `clipboard/sync`.
