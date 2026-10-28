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

The list SHALL cover every grant whose absence degrades Bridge, including the runtime
permissions requested at first launch. A runtime permission that the user denied SHALL appear
as a row like any other, so that a denial is visible on the screen the user already opens rather
than only in Android's app-info screen.

Platform constraint: Android only. The Mac panel's rules are unchanged and are covered by the
Mac requirements above.

#### Scenario: All Android permissions granted
- **WHEN** the user opens Bridge settings and all grants are in place
- **THEN** each item shows green: Notification Access (enabled), Battery Optimization
  (unrestricted), and the Bluetooth and notification runtime permissions (granted)

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

#### Scenario: A denied runtime permission is listed
- **WHEN** the user denied the Bluetooth or notification runtime permission at first launch
- **THEN** that permission appears in the list as not granted, with its own action

### Requirement: Each Android permission row states its consequence and carries its own action
Each row in the Android permission list SHALL present, as distinct elements: a status indicator,
the permission's name, one line naming what Bridge cannot do while the permission is missing,
and an action control for that row alone. The rows SHALL NOT be rendered as a single block of
concatenated text, and SHALL NOT be rendered in a monospace face.

A row whose permission is granted SHALL show its granted indicator and SHALL NOT offer an
action, since there is nothing left to do.

Platform constraint: Android only. The Mac panel hides granted permissions entirely; the
Android screen is opened deliberately and lists every row, so it states granted status instead
of hiding it.

#### Scenario: A missing permission names what it costs
- **WHEN** Notification Access is disabled
- **THEN** its row shows a not-granted indicator, the name "Notification Access", a line saying
  that phone notifications will not reach the Mac, and a button that opens the system
  Notification Access settings

#### Scenario: Each row's action affects only that row
- **WHEN** two permissions are missing and the user uses the first row's action
- **THEN** only the first permission's grant screen opens, and the second row keeps its own
  separate action

#### Scenario: A granted row offers no action
- **WHEN** Battery Optimization is already excluded
- **THEN** its row shows the granted indicator and no action control

#### Scenario: Rows are not a text dump
- **WHEN** the permission section is shown in any state
- **THEN** each permission occupies its own row with separate indicator, name, consequence and
  action, and none of them is monospace-rendered concatenated text

---

### Requirement: A denied runtime permission is recoverable without leaving the app
A runtime permission the user denied SHALL be re-requestable from its own row. When Android
will no longer show the system prompt for that permission, the row's action SHALL open Bridge's
app-settings screen instead, so there is always a way forward from inside the app.

A denied permission SHALL NOT be reported by overwriting unrelated status text; the connection
status card SHALL continue to describe the link, not the permission.

Platform constraint: Android only. Android stops showing the runtime prompt after a permanent
denial, and `shouldShowRequestPermissionRationale` returning false after a denial is the only
signal that this has happened.

#### Scenario: First denial can be retried in place
- **WHEN** the user denied a runtime permission once and uses that row's action
- **THEN** the system permission prompt is shown again

#### Scenario: Permanent denial falls back to app settings
- **WHEN** Android will no longer present the prompt for that permission and the user uses the
  row's action
- **THEN** Bridge's app-settings screen opens, where the permission can be granted

#### Scenario: Granting it returns the app to a working state
- **WHEN** the user grants a previously denied permission and returns to Bridge
- **THEN** the row shows granted and the foreground service starts, with no app restart

#### Scenario: The status card is not used as an error surface
- **WHEN** a runtime permission is denied
- **THEN** the connection status card still reports the link state and the paired Mac's name,
  and the permission problem is reported only by its own row
