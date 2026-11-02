## MODIFIED Requirements

### Requirement: Link probe on app open
When the Bridge app is opened and a socket is believed open, the phone probes the link by
sending a ping and closing the socket if no packet returns within a short timeout, so a
stale "Connected" state cannot persist for a dead link.

A live peer SHALL answer the probe ping, so the probe SHALL NOT close a link merely because the
peer had nothing of its own to say. In particular the probe SHALL survive an open pair
confirmation, which can stay unanswered for minutes while a human reads the digits.

Platform: the probe is sent by the phone (Android); the answer is required of both ends.

#### Scenario: Dead link detected on open
- **WHEN** the app is opened with an open socket and no packet is received within 3 seconds of the probe ping
- **THEN** the socket is closed, the status drops to Advertising, and BLE reconnects

#### Scenario: Live link confirmed on open
- **WHEN** the app is opened with an open socket and a packet is received within the timeout
- **THEN** the link stays Connected and no reconnect occurs

#### Scenario: Probe during a pending pair confirmation
- **WHEN** the app is opened while a pair confirmation is displayed and unanswered on either
  device, and the probe ping is sent
- **THEN** the peer's ping reply arrives within the timeout, the socket stays open, and the
  confirmation remains answerable

#### Scenario: No socket open
- **WHEN** the app is opened and no socket is open
- **THEN** no probe is sent
