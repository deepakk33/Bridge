# protocol/pairing Specification

## Purpose
Define the `bridge.pair` packet and the pairing flow that establishes mutual trust between two devices, including the 30-second timeout, user confirmation on both sides, certificate fingerprint pinning on acceptance, and the unpair-and-repair flow for device replacement.

## Requirements

### Requirement: Pair request flow

A device initiates pairing by sending a `bridge.pair` packet with `pair: true`. Both devices then
display the six-digit verification code derived from the two certificate fingerprints, together
with the peer's `deviceName`, and each waits for its own user to accept or reject.

Acceptance SHALL NOT be automatic under any condition. In particular, an empty trust store SHALL
NOT be treated as consent, and displaying a confirmation SHALL NOT be followed by accepting on
the user's behalf. Nothing is pinned until a user has accepted on that device.

#### Scenario: Pairing initiated after identity exchange

- **WHEN** a device sends a `bridge.pair` request
- **THEN** it has already received the peer's `bridge.identity` packet in this session

#### Scenario: User prompted on receiving device

- **WHEN** a `bridge.pair` request with `pair: true` arrives
- **THEN** both the initiating and the receiving device display the same six-digit code with the
  peer's device name, and accept and reject options, and neither has pinned anything yet

#### Scenario: Acceptance pins certificates mutually

- **WHEN** the user accepts on the receiving device after confirming the digits match
- **THEN** the receiving device sends `bridge.pair` with `pair: true` back to the initiator, and
  both devices persist the other's certificate fingerprint in their trust store

#### Scenario: Rejection notifies the initiator

- **WHEN** the user rejects the pair request on either device
- **THEN** a `bridge.pair` with `pair: false` is sent, and no certificate fingerprints are pinned
  on either side

#### Scenario: An empty trust store is not consent

- **WHEN** a pair request arrives and the receiving device's trust store is empty
- **THEN** the confirmation is displayed and the device waits for the user, exactly as it would
  with a populated trust store

### Requirement: 30-second pairing timeout

If no accept or reject response arrives within 30 seconds, the pair request expires.

#### Scenario: Timeout with no response

- **WHEN** 30 seconds elapse after sending a `bridge.pair` request without receiving a response
- **THEN** the initiating device treats the request as rejected and dismisses any pending UI

#### Scenario: Timeout on the receiving device

- **WHEN** 30 seconds elapse after displaying the pair prompt without user action
- **THEN** the receiving device dismisses the prompt and sends `bridge.pair` with `pair: false`

### Requirement: Unpaired device rejection

Packets from a peer whose certificate fingerprint is not in the trust store SHALL be dropped.
This rule SHALL apply from the moment the connection opens, not only after an identity packet
has been received: a peer that has sent nothing identifying itself is an unpaired device.

#### Scenario: Unknown device attempts communication

- **WHEN** a device that has not been paired sends any packet other than `bridge.identity` or
  `bridge.pair`
- **THEN** the receiving device drops the packet before parsing its body

#### Scenario: Peer that has sent no identity packet

- **WHEN** a peer sends any packet other than `bridge.identity` or `bridge.pair` before an
  identity packet has been received in this session
- **THEN** the packet is dropped, because no identity means no trust decision is possible

#### Scenario: Paired device sends packets normally

- **WHEN** a paired device sends a packet after its identity has been received and its
  fingerprint matches the pinned value
- **THEN** the packet is accepted and processed

### Requirement: Unpair and repair

Either device can unpair by sending `bridge.pair` with `pair: false` at any time. The other side removes the certificate fingerprint from its trust store. Re-pairing requires a fresh `bridge.pair` request with user confirmation.

#### Scenario: Unpair clears trust on both sides

- **WHEN** a device sends `bridge.pair` with `pair: false` to a currently paired peer
- **THEN** both devices remove the other's certificate fingerprint from their trust store and close the TLS session

#### Scenario: Repair after device replacement

- **WHEN** a user replaces their phone (new device, new certificate)
- **THEN** the Mac can unpair the old device and pair with the new one through the standard pairing flow

### Requirement: Six-digit verification code

Both devices SHALL independently derive a six-digit verification code from the two certificate
fingerprints and display it before either side pins anything. The derivation SHALL depend only
on the two fingerprints, so that two devices that are genuinely talking to each other compute
the same digits without exchanging them, and a man-in-the-middle — which necessarily presents a
different certificate to at least one side — produces a mismatch.

The derivation SHALL be: lowercase the two hex fingerprints, sort the two strings
lexicographically, concatenate them with no separator, take the SHA-256 digest of the UTF-8
bytes, read the first four bytes as a big-endian unsigned integer, reduce modulo 1,000,000, and
render as six decimal digits zero-padded on the left.

The code SHALL NOT be transmitted in any packet. Each side computes it locally from the
fingerprints it already holds.

#### Scenario: Both sides derive the same code

- **WHEN** a Mac and a phone have exchanged identity packets carrying their certificate
  fingerprints
- **THEN** both compute the same six digits from those two fingerprints alone, with no packet
  carrying the code

#### Scenario: Order of the fingerprints does not matter

- **WHEN** the same two fingerprints are supplied in either order
- **THEN** the derived six digits are identical, because the inputs are sorted before hashing

#### Scenario: A different peer produces different digits

- **WHEN** one side is talking to a device presenting a different certificate than the other side
  believes
- **THEN** the two displayed codes differ, and the user declining on either screen leaves both
  trust stores unchanged

#### Scenario: Derivation agrees across platforms

- **WHEN** the shared test vectors in `protocol/` are evaluated by the Mac and Android
  implementations
- **THEN** both produce the expected six digits for every vector

### Requirement: A fingerprint that does not match the pin closes the connection

A device whose `deviceId` is already pinned but which presents a different certificate
fingerprint SHALL have its connection closed. The system SHALL NOT offer, initiate, or accept an
automatic re-pair in response. Returning such a device to service SHALL require a deliberate
unpair followed by the normal pairing flow with code confirmation.

#### Scenario: Changed fingerprint on a pinned device

- **WHEN** a device whose `deviceId` is pinned connects presenting a fingerprint other than the
  pinned one
- **THEN** the connection is closed, the trust store is left unchanged, and the event is logged
  with both the pinned and presented fingerprints

#### Scenario: A reinstalled peer needs a deliberate re-pair

- **WHEN** the peer app is reinstalled, generating a new certificate, and reconnects
- **THEN** the connection is closed on fingerprint mismatch, and the user must explicitly unpair
  and pair again, confirming a freshly derived six-digit code

#### Scenario: Re-pair is never granted to satisfy a peer that lost its trust store

- **WHEN** a peer sends a `bridge.pair` request while this device considers it already paired
- **THEN** the request is surfaced for confirmation like any other, and is never accepted
  automatically on the grounds that the peer may have lost its trust store
