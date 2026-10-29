# app-skeleton/mac-menubar Specification

## Purpose
Minimal macOS menu bar app that hosts the BLE central and shows connection state.

## Requirements

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

### Requirement: Single running instance
The Mac app SHALL run as at most one process. A launch that finds another Bridge process already
running SHALL surface the running one and exit, and SHALL NOT start a Bluetooth central, a
payload server or any other link machinery before exiting.

The check SHALL be made against live process identity, so that it holds between copies of the app
installed at different paths, and so that no state persists to block a later launch.

#### Scenario: Second launch while one is running
- **WHEN** a copy of Bridge is launched while another Bridge process is running
- **THEN** the already-running instance is brought to the front
- **AND** the newly launched copy exits
- **AND** exactly one Bridge process remains

#### Scenario: Two different builds from different paths
- **WHEN** the running instance was launched from one location (for example
  `/Applications/Bridge.app`) and the second from another (for example an Xcode build directory)
- **THEN** the second copy still detects the first and exits

#### Scenario: The surviving instance keeps its Bluetooth scan
- **WHEN** a second copy is launched and exits under this rule
- **THEN** the running instance's Bluetooth scan is unaffected, because the exiting copy never
  started a central of its own and so never tore one down

#### Scenario: Relaunch after a genuine quit
- **WHEN** the only Bridge instance is quit normally, force-quit, or crashes, and the app is
  launched again
- **THEN** it starts normally, with no stale lock or marker to clear by hand
