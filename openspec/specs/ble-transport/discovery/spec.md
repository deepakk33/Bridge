# ble-transport/discovery Specification

## Purpose
BLE service discovery between Mac (central) and Android (peripheral) using a Bridge-specific service UUID.

## Requirements

### Requirement: Android advertises Bridge service
The Android foreground service advertises a BLE service with UUID `a]76e1a0-b]5d-4]3c-8]2b-d]0e9f8a7b6c5` (placeholder, generate once and hardcode) whenever it is running and not connected to a paired peer.

#### Scenario: App launched and foreground service started
- **WHEN** the user opens the Bridge app and the foreground service starts
- **THEN** BLE advertising begins within 1 second using the Bridge service UUID

#### Scenario: Disconnected from peer
- **WHEN** the BLE connection to the Mac drops
- **THEN** advertising resumes within 2 seconds

#### Scenario: Connected to peer
- **WHEN** a BLE central connects and is recognized
- **THEN** advertising stops to save battery

---

### Requirement: Mac scans for Bridge service
The Mac app scans for the Bridge service UUID using Core Bluetooth's CBCentralManager.

#### Scenario: App launched
- **WHEN** the Bridge Mac app starts and Bluetooth is on
- **THEN** scanning begins for the Bridge service UUID

#### Scenario: Bridge peripheral found
- **WHEN** a peripheral advertising the Bridge UUID is discovered
- **THEN** the Mac initiates a connection to it

#### Scenario: Bluetooth off
- **WHEN** Bluetooth is powered off on the Mac
- **THEN** the menu bar shows "Bluetooth off" state and scanning stops gracefully

---

### Requirement: Identity characteristic readable
The Android peripheral exposes a GATT characteristic containing a JSON identity payload: `{"deviceId": "<uuid>", "deviceName": "<string>", "deviceType": "phone"}`.

#### Scenario: Mac reads identity after connect
- **WHEN** the Mac connects to the peripheral
- **THEN** it reads the identity characteristic and logs the device name and ID
