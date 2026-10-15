# protocol/identity Specification

## Purpose
Define the `bridge.identity` packet exchanged on every BLE connection, carrying device metadata, protocol version, certificate fingerprint, and capability arrays so each side knows what the other supports without a coordinated version bump.

## Requirements

### Requirement: Identity packet structure

The `bridge.identity` packet is a single-line JSON object containing `deviceId` (stable UUID generated on first launch), `deviceName` (user-visible label), `deviceType` (`"phone"` or `"desktop"`), `protocolVersion` (integer), `certificateFingerprint` (SHA-256 hex of the device's self-signed certificate), and two capability arrays: `incomingCapabilities` (packet types the device can receive) and `outgoingCapabilities` (packet types the device can send).

#### Scenario: Both devices exchange identity on connection

- **WHEN** a BLE L2CAP channel is established between the Mac and the phone
- **THEN** both devices send a `bridge.identity` packet as the first message on the channel, before any other packet type

#### Scenario: Identity includes certificate fingerprint

- **WHEN** a device constructs its `bridge.identity` packet
- **THEN** the `certificateFingerprint` field contains the SHA-256 hex digest of its self-signed certificate's public key

#### Scenario: Capability arrays determine allowed packets

- **WHEN** a device receives a `bridge.identity` from a peer
- **THEN** it records the peer's `outgoingCapabilities` and only accepts packet types listed in that array (intersected with its own `incomingCapabilities`)

#### Scenario: Unknown fields are ignored

- **WHEN** a device receives a `bridge.identity` containing fields not in its schema
- **THEN** it ignores the unknown fields without error, preserving forward compatibility

### Requirement: Stable device identity

Each device generates a `deviceId` (UUID v4) on first launch and persists it permanently. The Mac stores it in Keychain; the Android stores it in Android Keystore-backed SharedPreferences.

#### Scenario: Device ID survives app restart

- **WHEN** the app is killed and restarted on either platform
- **THEN** the `deviceId` in the identity packet is the same as before

#### Scenario: Device ID survives OS update

- **WHEN** the device receives an OS update and reboots
- **THEN** the `deviceId` remains unchanged

### Requirement: Protocol version is informational only

The `protocolVersion` integer is included for diagnostics and future use. Capability arrays, not the version number, determine what a peer may send.

#### Scenario: Mismatched protocol versions do not block connection

- **WHEN** two devices connect with different `protocolVersion` values
- **THEN** the connection proceeds normally; capabilities alone govern which packets are exchanged
