## ADDED Requirements

### Requirement: Link probe on app open
When the Bridge app is opened and a socket is believed open, the phone probes the link by
sending a ping and closing the socket if no packet returns within a short timeout, so a
stale "Connected" state cannot persist for a dead link.

#### Scenario: Dead link detected on open
- **WHEN** the app is opened with an open socket and no packet is received within 3 seconds of the probe ping
- **THEN** the socket is closed, the status drops to Advertising, and BLE reconnects

#### Scenario: Live link confirmed on open
- **WHEN** the app is opened with an open socket and a packet is received within the timeout
- **THEN** the link stays Connected and no reconnect occurs

#### Scenario: No socket open
- **WHEN** the app is opened and no socket is open
- **THEN** no probe is sent
