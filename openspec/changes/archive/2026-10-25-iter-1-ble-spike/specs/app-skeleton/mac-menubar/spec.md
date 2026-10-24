## Purpose
Minimal macOS menu bar app that hosts the BLE central and shows connection state.

## ADDED Requirements

### Requirement: Menu bar presence
The app runs as a menu bar-only application (no dock icon, no main window).

#### Scenario: App launches
- **WHEN** the Mac app is launched
- **THEN** a menu bar icon appears showing disconnected state

#### Scenario: Connection state changes
- **WHEN** BLE connection state changes (disconnected → connected → out of range)
- **THEN** the menu bar icon updates to reflect current state

---

### Requirement: Info.plist declarations
The app's Info.plist includes `NSBluetoothAlwaysUsageDescription` for Core Bluetooth access.

#### Scenario: First launch
- **WHEN** the app runs for the first time
- **THEN** macOS prompts for Bluetooth permission with the declared usage string

---

### Requirement: Self-signed code signature
The app is signed with a self-signed certificate from Keychain Access so TCC grants survive rebuilds.

#### Scenario: Rebuild and relaunch
- **WHEN** the app is rebuilt and relaunched
- **THEN** previously granted Bluetooth permission remains valid (no re-prompt)
