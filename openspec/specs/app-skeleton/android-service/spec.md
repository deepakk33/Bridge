# app-skeleton/android-service Specification

## Purpose
Minimal Android app with a foreground service that hosts the BLE peripheral and stays alive.

## Requirements

### Requirement: Foreground service of type connectedDevice
The app runs a foreground service declared with type `connectedDevice` that keeps the BLE peripheral alive.

#### Scenario: User opens app
- **WHEN** the user opens the Bridge app (foreground)
- **THEN** the foreground service starts with a persistent notification showing "Bridge active"

#### Scenario: App backgrounded
- **WHEN** the user switches away from Bridge
- **THEN** the foreground service continues running and BLE advertising continues

#### Scenario: Service killed by system
- **WHEN** the system kills the service
- **THEN** the service restarts via START_STICKY and resumes advertising

---

### Requirement: Manifest permissions
The app declares: `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`.

#### Scenario: First launch
- **WHEN** the app runs for the first time
- **THEN** runtime permission prompts appear for Bluetooth

---

### Requirement: Developer options sideload
The app installs via `adb install` with developer options enabled. No Play Store.

#### Scenario: Install via ADB
- **WHEN** `adb install bridge.apk` is run
- **THEN** the app installs and launches successfully on the S25
