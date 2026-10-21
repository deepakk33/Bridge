# ui/permission-panel Specification

## Purpose
Mac menu bar panel showing the current state of all TCC (Transparency, Consent, and Control) permission grants: Bluetooth, Local Network, Notifications, and Accessibility. Android settings screen showing Notification Access, Battery Optimization exclusion, and ADB clipboard grant status. Both check on launch and surface revoked permissions clearly, addressing the PRD's #1 risk: "Every one of these presents to the user as nothing happening."

## Requirements

### Requirement: Mac permission panel in menu bar
The Mac menu bar dropdown includes a permission status section showing all required grants.

#### Scenario: All permissions granted
- **WHEN** the user opens the menu bar dropdown and all TCC permissions are held
- **THEN** each permission (Bluetooth, Local Network, Notifications) shows a green "Granted" indicator

#### Scenario: Permission revoked
- **WHEN** the Local Network permission has been revoked (e.g., by a macOS update)
- **THEN** the permission panel shows Local Network as "Denied" with a red indicator and a "Fix" button that opens System Settings to the relevant pane

#### Scenario: Permission not yet requested
- **WHEN** the app has not yet triggered the Notifications permission prompt
- **THEN** the panel shows Notifications as "Not Requested" with a "Request" button that triggers the system prompt

---

### Requirement: Mac permission check at launch
The Mac app checks all permission states when it starts.

#### Scenario: Launch with all permissions
- **WHEN** the app launches and all permissions are granted
- **THEN** no alert is shown; the permission panel in the dropdown shows all green

#### Scenario: Launch with missing permission
- **WHEN** the app launches and Bluetooth permission is denied
- **THEN** the menu bar icon shows a warning badge and the dropdown highlights the missing permission

#### Scenario: Launch after macOS update
- **WHEN** the app launches after a macOS point release that reset TCC grants
- **THEN** any newly denied permissions are detected and surfaced in the permission panel

---

### Requirement: Mac permission states tracked
The app monitors each TCC permission relevant to Bridge functionality.

#### Scenario: Bluetooth permission state
- **WHEN** the permission panel is displayed
- **THEN** it reflects the current CBManager authorization status (authorized, denied, restricted, notDetermined)

#### Scenario: Local Network permission state
- **WHEN** the permission panel is displayed
- **THEN** it reflects whether the app can make local network connections (probed on launch)

#### Scenario: Notification permission state
- **WHEN** the permission panel is displayed
- **THEN** it reflects the current UNNotificationCenter authorization status

---

### Requirement: Android permission status in settings
The Android Bridge settings screen shows the state of all required permissions and grants.

#### Scenario: All Android permissions granted
- **WHEN** the user opens Bridge settings and all grants are in place
- **THEN** each item shows green: Notification Access (enabled), Battery Optimization (unrestricted), ADB Clipboard (granted)

#### Scenario: Notification access revoked
- **WHEN** Notification Access has been disabled for Bridge
- **THEN** the settings screen shows it as "Disabled" with a red indicator and a button that opens the system Notification Access settings

#### Scenario: Battery optimization enabled
- **WHEN** Bridge is not excluded from battery optimization
- **THEN** the settings screen shows Battery Optimization as "Not Excluded" with a warning and a button to open Battery settings

#### Scenario: ADB clipboard grant missing
- **WHEN** the READ_CLIPBOARD_IN_BACKGROUND ADB grant is not present
- **THEN** the settings screen shows Clipboard Access as "Limited (phone-to-Mac clipboard disabled)" and displays the ADB command needed to restore it

---

### Requirement: Android permission check at launch
The Android app checks all permission and grant states when the foreground service starts.

#### Scenario: Service starts with all grants
- **WHEN** the foreground service starts and all permissions are in place
- **THEN** no notification beyond the standard foreground service notification

#### Scenario: Service starts with missing grant
- **WHEN** the foreground service starts and Notification Access is disabled
- **THEN** a separate notification is posted: "Bridge cannot mirror notifications. Tap to fix." Opening it goes to the Notification Access settings
