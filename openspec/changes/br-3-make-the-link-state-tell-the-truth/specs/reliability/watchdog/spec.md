## MODIFIED Requirements

### Requirement: Dead connection detection
If no packet (including heartbeat pings) is received within a timeout, the connection is declared
dead. A BLE disconnect event on its own SHALL NOT be taken as proof that the phone moved away:
the Mac SHALL report "Out of range" only for a supervision-timeout disconnect or a peripheral it
cannot find, and SHALL report a dead service for a peer-terminated disconnect or for silence
while the peripheral connection is still open.

Platform constraint (Mac): a force-stopped Android app stops advertising exactly as a departed
phone does, so the disconnect reason reported by Core Bluetooth is the only available signal.

#### Scenario: Dead link detection
- **WHEN** no packet is received from the phone for 90 seconds (3x the heartbeat interval)
- **THEN** the Mac marks the connection as dead and reports "Not responding"

#### Scenario: Dead link differs from out-of-range
- **WHEN** the BLE link drops on a supervision timeout (phone walked away)
- **THEN** the Mac shows "Out of range", not "Not responding"

#### Scenario: Peer-terminated disconnect is not distance
- **WHEN** the BLE link drops because the peer's stack tore the connection down — the phone's
  Bridge service was force-stopped or its Bluetooth was turned off
- **THEN** the Mac shows "Not responding" with the restart guidance, not "Out of range"

#### Scenario: Silent death detection
- **WHEN** the BLE peripheral is still connected but no packets arrive for 90 seconds (Android
  service killed by One UI)
- **THEN** the Mac detects this as a silent death and shows "Not responding" with the restart
  guidance

## ADDED Requirements

### Requirement: Bounded time to report a degraded link
The Mac SHALL re-evaluate the link's health at an interval no longer than 10 seconds while a
channel is open, so that the delay between a threshold being crossed and the user seeing it is
bounded by that interval and not by the heartbeat poll.

Before this requirement the health was recomputed only on the 30-second watchdog tick against a
90-second timeout, so a silent drop could be displayed as "Connected" — with a live-looking
battery percentage — for up to 120 seconds.

#### Scenario: Stale is reported promptly
- **WHEN** 60 seconds pass with no packet received
- **THEN** the Mac stops reporting "Connected" within a further 10 seconds

#### Scenario: Dead is reported promptly
- **WHEN** 90 seconds pass with no packet received while the peripheral is still connected
- **THEN** the Mac reports "Not responding" within a further 10 seconds

#### Scenario: Thresholds are unchanged
- **WHEN** the re-evaluation cadence is applied
- **THEN** the heartbeat interval (30 s), stale threshold (60 s) and dead threshold (90 s) are
  unchanged, so no additional packets are sent and battery behaviour is unaffected
