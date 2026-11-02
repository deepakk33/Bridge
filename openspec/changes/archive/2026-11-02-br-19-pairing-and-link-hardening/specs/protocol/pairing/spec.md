## MODIFIED Requirements

### Requirement: Unpaired device rejection

Packets from a peer whose certificate fingerprint is not in the trust store SHALL be dropped.
This rule SHALL apply from the moment the connection opens, not only after an identity packet
has been received: a peer that has sent nothing identifying itself is an unpaired device.

The exceptions are `bridge.identity`, `bridge.pair` and `bridge.ping`. The first two establish
identity and trust. `bridge.ping` carries no data and keeps the link alive across a pair
confirmation that a human may take minutes to answer; both sides already send it to an
unverified peer for that reason, so refusing to answer it protects nothing and strands the
link instead.

#### Scenario: Unknown device attempts communication

- **WHEN** a device that has not been paired sends any packet other than `bridge.identity`,
  `bridge.pair` or `bridge.ping`
- **THEN** the receiving device drops the packet before parsing its body

#### Scenario: Peer that has sent no identity packet

- **WHEN** a peer sends any packet other than `bridge.identity`, `bridge.pair` or `bridge.ping`
  before an identity packet has been received in this session
- **THEN** the packet is dropped, because no identity means no trust decision is possible

#### Scenario: An unverified peer's ping is answered

- **WHEN** a peer that is not yet verified sends `bridge.ping`
- **THEN** the packet is accepted and answered, and no packet type other than
  `bridge.identity`, `bridge.pair` or `bridge.ping` becomes available to that peer

#### Scenario: Paired device sends packets normally

- **WHEN** a paired device sends a packet after its identity has been received and its
  fingerprint matches the pinned value
- **THEN** the packet is accepted and processed

## ADDED Requirements

### Requirement: A pair confirmation does not depend on arrival order

The six-digit confirmation needs this device's own certificate fingerprint as well as the
peer's. Each SHALL be able to arrive first. When a pair request or peer identity arrives before
the local fingerprint is available, the device SHALL hold the request and display the
confirmation as soon as the fingerprint is available, rather than discarding it.

A failure to read the local fingerprint SHALL NOT be cached: a later attempt SHALL re-read it,
so a transient failure does not disable pairing for the lifetime of the process.

Platform constraint (Mac): the certificate lives in the Keychain behind an actor, so the
fingerprint cannot be read from the synchronous path that handles an incoming packet.
Platform constraint (Android): the fingerprint is read from the keystore on first use, which
can fail while the service is still starting.

#### Scenario: Identity arrives before the local fingerprint

- **WHEN** a peer's identity packet arrives before this device's own certificate fingerprint has
  been read
- **THEN** no confirmation is shown yet, and the confirmation appears with the correct six
  digits once the fingerprint becomes available

#### Scenario: Local fingerprint arrives first

- **WHEN** this device's fingerprint is already available when the peer's identity arrives
- **THEN** the confirmation appears immediately, unchanged from the current behaviour

#### Scenario: A transient fingerprint failure is not permanent

- **WHEN** reading the local fingerprint fails once and a later pair request arrives
- **THEN** the fingerprint is read again, and pairing succeeds without restarting the app

#### Scenario: A deferred request is dropped with the connection

- **WHEN** a pair request is being held for a missing fingerprint and the peer disconnects
- **THEN** the held request is discarded, and no confirmation appears for a peer that is gone
