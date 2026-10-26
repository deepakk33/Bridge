## ADDED Requirements

### Requirement: Dismissal travels from the phone to the Mac

A notification cleared on the phone SHALL be withdrawn from the Mac. Dismissal already travels Mac → phone;
this requires the reverse, so that reading a message on the phone does not leave a stale copy on the Mac
indefinitely.

Packet types: `bridge.notification.request`, carrying the dismissed notification's `cancel` identifier. The
same type already carries Mac → phone dismissals; this requirement uses it in the other direction and requires
the Mac to act on it rather than ignore it.

Platform note: the phone is the source of truth for a notification's lifetime. The Mac withdraws its copy on
request and does not send anything back, so the exchange is one-way and needs no acknowledgement.

#### Scenario: Notification cleared on the phone

- **WHEN** the user dismisses or clears a mirrored notification on the phone while the link is up
- **THEN** the phone sends a `bridge.notification.request` naming that notification, and the Mac withdraws the
  corresponding notification from Notification Center

#### Scenario: The app itself withdraws the notification

- **WHEN** the originating app cancels a notification that was mirrored (for example, the message was read on
  another device)
- **THEN** the phone sends the same dismissal and the Mac withdraws its copy

#### Scenario: Dismissal of a notification the Mac never showed

- **WHEN** the Mac receives a dismissal for an identifier it has no notification for
- **THEN** the packet is handled without error and nothing else changes

#### Scenario: Notification cleared while the link is down

- **WHEN** a notification is cleared on the phone while the BLE channel is disconnected
- **THEN** the dismissal is dropped rather than queued, consistent with this capability's existing rule that a
  late notification is noise; the stale copy on the Mac is the accepted cost

#### Scenario: Dismissal does not loop

- **WHEN** the Mac withdraws a notification because the phone asked it to
- **THEN** the Mac does not send a dismissal back to the phone for that notification
