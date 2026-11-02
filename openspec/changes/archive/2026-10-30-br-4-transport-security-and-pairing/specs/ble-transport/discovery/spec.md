## ADDED Requirements

### Requirement: An unpaired Mac presents a device picker

Every Bridge installation advertises the same service UUID, so a scan can return several phones.
While the Mac has no paired device, it SHALL NOT connect to an advertiser automatically. It
SHALL present the discovered devices by `deviceName` and signal strength and wait for the user
to choose one.

Once a device is paired, the Mac SHALL reconnect to that `deviceId` automatically and SHALL
ignore every other advertiser, so the picker is a first-run step rather than a recurring prompt.

#### Scenario: Unpaired Mac lists what it found

- **WHEN** the Mac is unpaired and discovers one or more peripherals advertising the Bridge UUID
- **THEN** it displays each one's device name and signal strength, and connects to none of them
  until the user picks one

#### Scenario: The list reflects what is actually in range

- **WHEN** a listed device stops advertising, or its signal strength changes while the picker is
  open
- **THEN** the displayed list updates, so the user is choosing from devices that are currently
  reachable

#### Scenario: Picking a device starts the pairing flow

- **WHEN** the user picks a device from the list
- **THEN** the Mac connects to that peripheral and the identity exchange and six-digit
  confirmation proceed for that device only

#### Scenario: Paired Mac does not show the picker

- **WHEN** the Mac already has a paired device and discovers that device
- **THEN** it reconnects automatically with no picker and no prompt

#### Scenario: Other advertisers are ignored once paired

- **WHEN** the Mac is paired to one phone and a different phone advertising the Bridge UUID comes
  into range
- **THEN** the Mac does not connect to it and does not prompt the user about it

## MODIFIED Requirements

### Requirement: Mac scans for Bridge service
The Mac app scans for the Bridge service UUID using Core Bluetooth's CBCentralManager. A
discovered peripheral SHALL be connected to automatically only when it is the paired device;
otherwise it SHALL be offered to the user through the device picker.

A scan SHALL be treated as a claim that can fail silently, not as a fact. While the link is down
and the Mac believes a scan is running, a bounded period during which no peripheral is
discovered SHALL cause the Mac to stop and restart the scan, and SHALL log that it did so.

Platform constraint: Mac only. Core Bluetooth gives a central no callback and no queryable state
when the daemon stops scanning on its behalf — observed 2026-10-27, when another process's
`stop scan` with `updateScanParams:YES` ended the surviving app's scan — so elapsed time with no
discovery is the only available signal.

#### Scenario: App launched
- **WHEN** the Bridge Mac app starts and Bluetooth is on
- **THEN** scanning begins for the Bridge service UUID

#### Scenario: Bridge peripheral found
- **WHEN** a peripheral advertising the Bridge UUID is discovered and its `deviceId` is the
  paired device
- **THEN** the Mac initiates a connection to it

#### Scenario: Bridge peripheral found while unpaired
- **WHEN** a peripheral advertising the Bridge UUID is discovered and the Mac has no paired
  device
- **THEN** the Mac adds it to the device picker and waits for the user to choose, rather than
  connecting to it

#### Scenario: Bluetooth off
- **WHEN** Bluetooth is powered off on the Mac
- **THEN** the menu bar shows "Bluetooth off" state and scanning stops gracefully

#### Scenario: A scan that discovers nothing is restarted
- **WHEN** the link is down, the Mac believes it is scanning, and no peripheral has been
  discovered for the watchdog period
- **THEN** the Mac stops and restarts the scan and records the restart in the log

#### Scenario: A scan dropped by another process recovers unaided
- **WHEN** the Mac's scan is ended externally while the phone is in range and advertising
- **THEN** the Mac discovers the phone and reconnects after at most one watchdog period, with no
  app restart and no Bluetooth toggle

#### Scenario: Discovery stops the watchdog
- **WHEN** a peripheral is discovered or the link comes up
- **THEN** the scan watchdog stops, and no restart is logged while the link holds
