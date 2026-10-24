# service/persistence Specification

## Purpose
TBD - created by archiving change iter-8-clipboard-resilience. Update Purpose after archive.

## Requirements

### Requirement: Foreground service survives task removal
The Bridge foreground service continues running when the app is swiped away from the
recents screen, keeping the BLE link alive.

#### Scenario: App swiped from recents
- **WHEN** the user removes the Bridge app from the recents screen
- **THEN** the `connectedDevice` foreground service keeps running and the link is not dropped

#### Scenario: Process restart after kill
- **WHEN** the system kills the service under memory pressure
- **THEN** it is restarted (START_STICKY) and re-advertises for the Mac
