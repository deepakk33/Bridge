## Purpose

Define the `bridge.pair` packet and the pairing flow that establishes mutual trust between two devices, including the 30-second timeout, user confirmation on both sides, certificate fingerprint pinning on acceptance, and the unpair-and-repair flow for device replacement.

## ADDED Requirements

### Requirement: Pair request flow

A device initiates pairing by sending a `bridge.pair` packet with `pair: true`. The receiving device prompts the user to accept or reject, showing the sender's `deviceName` and `certificateFingerprint` from the preceding identity exchange.

#### Scenario: Pairing initiated after identity exchange

- **WHEN** a device sends a `bridge.pair` request
- **THEN** it has already received the peer's `bridge.identity` packet in this session

#### Scenario: User prompted on receiving device

- **WHEN** a `bridge.pair` request with `pair: true` arrives
- **THEN** the receiving device displays a prompt showing the sender's device name and certificate fingerprint, with accept and reject options

#### Scenario: Acceptance pins certificates mutually

- **WHEN** the user accepts the pair request on the receiving device
- **THEN** the receiving device sends `bridge.pair` with `pair: true` back to the initiator, and both devices persist the other's certificate fingerprint in their trust store

#### Scenario: Rejection notifies the initiator

- **WHEN** the user rejects the pair request
- **THEN** the receiving device sends `bridge.pair` with `pair: false`, and no certificate fingerprints are pinned

### Requirement: 30-second pairing timeout

If no accept or reject response arrives within 30 seconds, the pair request expires.

#### Scenario: Timeout with no response

- **WHEN** 30 seconds elapse after sending a `bridge.pair` request without receiving a response
- **THEN** the initiating device treats the request as rejected and dismisses any pending UI

#### Scenario: Timeout on the receiving device

- **WHEN** 30 seconds elapse after displaying the pair prompt without user action
- **THEN** the receiving device dismisses the prompt and sends `bridge.pair` with `pair: false`

### Requirement: Unpaired device rejection

After pairing is established, packets from devices whose certificate fingerprint is not in the trust store are dropped.

#### Scenario: Unknown device attempts communication

- **WHEN** a device that has not been paired sends any packet other than `bridge.identity`
- **THEN** the receiving device drops the packet before parsing its body

#### Scenario: Paired device sends packets normally

- **WHEN** a paired device sends a packet after mutual TLS is established
- **THEN** the packet is accepted and processed

### Requirement: Unpair and repair

Either device can unpair by sending `bridge.pair` with `pair: false` at any time. The other side removes the certificate fingerprint from its trust store. Re-pairing requires a fresh `bridge.pair` request with user confirmation.

#### Scenario: Unpair clears trust on both sides

- **WHEN** a device sends `bridge.pair` with `pair: false` to a currently paired peer
- **THEN** both devices remove the other's certificate fingerprint from their trust store and close the TLS session

#### Scenario: Repair after device replacement

- **WHEN** a user replaces their phone (new device, new certificate)
- **THEN** the Mac can unpair the old device and pair with the new one through the standard pairing flow
