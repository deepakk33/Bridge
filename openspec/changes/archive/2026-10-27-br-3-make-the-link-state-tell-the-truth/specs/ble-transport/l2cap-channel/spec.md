## ADDED Requirements

### Requirement: A send reports whether it was written

Sending a packet over the L2CAP channel SHALL report success or failure to the caller. Success
means every byte of the serialised line was accepted by the output stream. A serialisation
failure, an absent or closed stream, a stream write error, and a partial write that cannot be
completed SHALL each be reported as a failure.

The channel SHALL complete a partial write by continuing to write the remaining bytes rather than
reporting success for a truncated line, since a truncated line cannot be parsed by the peer.

A send SHALL also be reported as a failure when the peer is not verified, because nothing is
delivered to an unverified peer.

Platform: Mac only — this is the Mac's `CBL2CAPChannel` output stream. Android's socket write
already throws on failure.

Packet types: all, including `bridge.ping`, `bridge.notification.reply` and
`bridge.notification.request`.

#### Scenario: Successful send

- **WHEN** a packet is sent over an open channel to a verified peer and the stream accepts every
  byte
- **THEN** the send reports success

#### Scenario: No channel

- **WHEN** a packet is sent while no L2CAP channel is open
- **THEN** the send reports failure

#### Scenario: Stream refuses or errors

- **WHEN** the output stream has no space available, or the write returns an error
- **THEN** the send reports failure rather than logging and returning success

#### Scenario: Partial write is completed or failed

- **WHEN** the output stream accepts fewer bytes than the serialised line contains
- **THEN** the channel writes the remainder, and reports failure if the remainder cannot be
  written

#### Scenario: Unverified peer

- **WHEN** a packet other than identity or pairing is sent while the peer is not verified
- **THEN** the send reports failure

#### Scenario: A failed reply tells the user

- **WHEN** the user replies to a mirrored notification and the send reports failure
- **THEN** the Mac posts the "Reply could not be delivered" notification
