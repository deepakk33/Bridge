## MODIFIED Requirements

### Requirement: Mac scans for Bridge service
The Mac app scans for the Bridge service UUID using Core Bluetooth's CBCentralManager.

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
- **WHEN** a peripheral advertising the Bridge UUID is discovered
- **THEN** the Mac initiates a connection to it

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

## ADDED Requirements

### Requirement: The Mac finds a peripheral the system already holds
Before and alongside scanning, the Mac SHALL ask Core Bluetooth for peripherals already
connected at the system level for the Bridge service UUID, and SHALL connect to such a
peripheral without waiting for an advertisement.

Platform constraint: Mac only. A peripheral macOS already holds does not advertise to this
central, so an app that only scans cannot find it — this is Core Bluetooth's documented way for
an app to recover a link the system is already maintaining.

#### Scenario: Relaunch while macOS holds the link
- **WHEN** the Mac app starts while macOS is already connected to the phone's Bridge peripheral
- **THEN** the Mac retrieves that peripheral and connects to it without waiting for an
  advertisement

#### Scenario: Checked again when the scan is restarted
- **WHEN** the scan watchdog restarts the scan
- **THEN** the Mac also re-checks for an already-connected peripheral before relying on the new
  scan

#### Scenario: Nothing held means an ordinary scan
- **WHEN** no peripheral for the Bridge service is connected at the system level
- **THEN** the Mac scans as normal and no connection is attempted
