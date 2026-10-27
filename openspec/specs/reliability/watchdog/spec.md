# reliability/watchdog Specification

## Purpose
Enhanced watchdog with heartbeat ping, last-seen time display, and dead connection detection. Addresses the PRD's #1 risk: silent failures where "a notification that does not arrive looks identical to no notification having been sent." The Mac menu bar always shows last packet time. If no packet is received within the timeout, the link is marked dead and the user gets actionable guidance.

## Requirements

### Requirement: Heartbeat ping at regular interval
Both sides send `bridge.ping` packets at a regular interval to prove the link is alive.

#### Scenario: Idle link heartbeat
- **WHEN** no application packet has been exchanged for 30 seconds
- **THEN** the Mac sends a `bridge.ping` to the phone

#### Scenario: Ping response
- **WHEN** one side receives a `bridge.ping`
- **THEN** it updates its last-seen timestamp for the peer (no explicit pong required; the ping itself is the proof of life)

#### Scenario: Active link suppresses heartbeat
- **WHEN** application packets (notifications, clipboard, etc.) are flowing
- **THEN** no heartbeat ping is sent (any received packet resets the heartbeat timer)

---

### Requirement: Last-seen time in Mac menu bar
The Mac menu bar shows how recently a packet was received from the phone.

#### Scenario: Recent activity
- **WHEN** a packet was received within the last 60 seconds
- **THEN** the menu bar shows "Last seen: just now" or "Last seen: Xs ago"

#### Scenario: Stale connection
- **WHEN** no packet has been received for over 60 seconds but under the dead threshold
- **THEN** the menu bar shows "Last seen: Xm ago" with a yellow warning indicator

#### Scenario: Menu bar dropdown detail
- **WHEN** the user opens the menu bar dropdown
- **THEN** it shows the exact last-seen timestamp (e.g., "Last packet: 2:34:12 PM")

---

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

### Requirement: Actionable guidance on failure
When the watchdog detects a problem, it tells the user what to do, not just what went wrong.

#### Scenario: One UI battery kill suspected
- **WHEN** the connection is lost and the BLE link is still technically connected (silent death)
- **THEN** the menu bar dropdown shows: "The phone's Bridge service may have been stopped by battery management. Open Bridge on the phone to restart."

#### Scenario: Bluetooth off
- **WHEN** the Mac's Bluetooth is turned off
- **THEN** the menu bar shows "Bluetooth off" with a direct link to Bluetooth settings

#### Scenario: Permission revoked
- **WHEN** a required permission is detected as revoked during a watchdog check
- **THEN** the menu bar dropdown references the permission panel for details

---

### Requirement: Recovery after dead connection
When the link recovers, the UI updates immediately.

#### Scenario: Service restarted on phone
- **WHEN** the Android Bridge service restarts after being killed
- **THEN** the BLE connection re-establishes via pending connect, the watchdog receives a packet, and the menu bar returns to "Connected" within 10 seconds

#### Scenario: Phone returns from out of range
- **WHEN** the phone returns to BLE range after being away
- **THEN** the connection re-establishes, the last-seen time updates, and the menu bar shows "Connected"

---

### Requirement: Android-side watchdog
The Android side also tracks last-seen time to detect Mac-side failures.

#### Scenario: Mac connection lost
- **WHEN** Android receives no packet from the Mac for 90 seconds
- **THEN** the foreground service notification updates to "Mac connection lost"

#### Scenario: Mac reconnects
- **WHEN** the Mac reconnects after a failure
- **THEN** the foreground service notification returns to "Bridge active — connected to [Mac name]"

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
