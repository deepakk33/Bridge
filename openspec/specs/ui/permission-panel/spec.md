# ui/permission-panel Specification

## Purpose
Mac menu bar panel showing the current state of all TCC (Transparency, Consent, and Control) permission grants: Bluetooth, Local Network, Notifications, and Accessibility. Android settings screen showing Notification Access, Battery Optimization exclusion, and ADB clipboard grant status. Both check on launch and surface revoked permissions clearly, addressing the PRD's #1 risk: "Every one of these presents to the user as nothing happening."

## Requirements

### Requirement: Mac permission panel in menu bar
The Mac menu bar panel includes a permission status section listing every required grant that is **not** currently
held. A permission that is granted SHALL NOT be listed, and when every required permission is granted the section
SHALL be absent from the panel entirely.

Rationale: a panel that always carries three green rows trains the user to skip the section, which is the exact
failure the PRD's permission panel exists to prevent. The section's presence is itself the signal.

Platform constraint: Mac only. The Android grant list is covered by its own requirements below and is unchanged —
Android surfaces all three grants, granted or not, on a settings screen the user opens deliberately rather than in
a transient panel.

#### Scenario: All permissions granted
- **WHEN** the user opens the menu bar panel and all TCC permissions are held
- **THEN** no permission section is shown in the panel

#### Scenario: Permission revoked
- **WHEN** the Local Network permission has been revoked (e.g., by a macOS update)
- **THEN** the permission section appears, showing Local Network as "Denied" on a single row with a red indicator
  and a "Fix" button that opens System Settings to the relevant pane

#### Scenario: Permission not yet requested
- **WHEN** the app has not yet triggered the Notifications permission prompt
- **THEN** the section shows Notifications as "Not Requested" on a single row with a "Request" button that
  triggers the system prompt

#### Scenario: Only the permissions needing attention are listed
- **WHEN** Bluetooth and Notifications are granted but Local Network is not
- **THEN** the section lists Local Network alone, and neither Bluetooth nor Notifications appears

---

### Requirement: Mac permission check at launch
The Mac app SHALL check all permission states when it starts, so that a grant revoked while the app was not
running is detected on the next launch rather than at first use.

#### Scenario: Launch with all permissions
- **WHEN** the app launches and all permissions are granted
- **THEN** no alert is shown, and the menu bar panel shows no permission section

#### Scenario: Launch with missing permission
- **WHEN** the app launches and Bluetooth permission is denied
- **THEN** the menu bar icon shows a warning badge and the panel lists the missing permission

#### Scenario: Launch after macOS update
- **WHEN** the app launches after a macOS point release that reset TCC grants
- **THEN** any newly denied permissions are detected and listed in the panel

---

### Requirement: Mac permission states tracked
The app monitors each TCC permission relevant to Bridge functionality. Tracking is independent of display: a
permission that is granted, and therefore not listed, SHALL still be tracked so that a later revocation makes it
appear.

#### Scenario: Bluetooth permission state
- **WHEN** the permission state is evaluated
- **THEN** it reflects the current CBManager authorization status (authorized, denied, restricted, notDetermined)

#### Scenario: Local Network permission state
- **WHEN** the permission state is evaluated
- **THEN** it reflects whether the app can make local network connections (probed on launch)

#### Scenario: Notification permission state
- **WHEN** the permission state is evaluated
- **THEN** it reflects the current UNNotificationCenter authorization status

#### Scenario: A revoked permission reappears in the panel
- **WHEN** a permission that was granted — and so was absent from the panel — is revoked, and the permission
  state is re-evaluated
- **THEN** that permission appears in the panel with its new status and its own action

### Requirement: Android permission check at launch
The Android app checks all permission and grant states when the foreground service starts.

#### Scenario: Service starts with all grants
- **WHEN** the foreground service starts and all permissions are in place
- **THEN** no notification beyond the standard foreground service notification

#### Scenario: Service starts with missing grant
- **WHEN** the foreground service starts and Notification Access is disabled
- **THEN** a separate notification is posted: "Bridge cannot mirror notifications. Tap to fix." Opening it goes to the Notification Access settings

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
