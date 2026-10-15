## Why

The raw BLE channel from iter-1 has no authentication or encryption. Any nearby device could connect to the Android peripheral and read packets, or a rogue peripheral could impersonate the phone. Before sending private data (notifications, clipboard, files), the link must be trusted. The PRD specifies mutual TLS with certificate pinning and a pairing flow as the security model (PRD: "Identity and trust"), and this iteration implements it.

This iteration also closes the "kitchen test" gate from the PRD (Phase 2): walk away until the Mac says out-of-range, come back, watch it reconnect without touching anything, repeat ten times.

## What Changes

**Protocol packets implemented:**
- `bridge.identity` -- exchanged on every connection. Carries `deviceId`, `deviceName`, `deviceType`, `protocolVersion`, and capability arrays. Each side generates a self-signed certificate on first launch and includes its fingerprint.
- `bridge.pair` -- request/accept/reject with 30-second timeout. On acceptance, each side pins the other's certificate fingerprint persistently. Packets from unpaired devices are dropped before parsing.
- `bridge.ping` -- liveness check. Sent on a configurable interval. Missed pings trigger the watchdog.

**Mac changes:**
- Self-signed certificate generation on first launch (stored in Keychain).
- TLS wrapper over the L2CAP stream using the pinned certificate.
- Pairing UI: prompt to accept/reject when a `bridge.pair` request arrives, showing the device name and certificate fingerprint.
- Menu bar status panel showing three states: connected, out-of-range, Bluetooth off (PRD: "Losing range and getting it back").
- Ping watchdog: if no packet received within the timeout window, mark link as dead and show last-seen time.

**Android changes:**
- Self-signed certificate generation on first launch (stored in Android Keystore).
- TLS wrapper over the L2CAP stream using the pinned certificate.
- Pairing UI: notification or dialog to accept/reject incoming pair requests.
- Certificate and paired-device storage persisted across app restarts.

**Testing:**
- Kitchen test protocol: 10 consecutive walk-away-and-back cycles with no manual intervention required to restore the link. Reconnect must succeed via pending connect + mutual TLS re-handshake.
- Unpaired device rejection test: a third BLE device attempting connection must be dropped.

## Capabilities

### New Capabilities

- **`protocol/identity`** -- `bridge.identity` packet exchanged on every connection. Carries device metadata, protocol version, and capability arrays. Capability arrays (not version numbers) determine what a peer may send, so adding packet types never requires a coordinated version bump (PRD: "Versioning").
- **`protocol/pairing`** -- `bridge.pair` request/accept/reject flow with 30-second timeout. User confirms on both devices. Acceptance pins certificates mutually. Unpair-and-repair flow available for when a device is replaced.
- **`security/certificate-pinning`** -- Each device generates a self-signed certificate once, stored permanently. Mutual TLS on every connection after pairing. Packets from unknown certificates are rejected before parsing.
- **`link/reconnect`** -- Automatic reconnection via BLE pending connect. Combined with TLS re-handshake on reconnect. Validated by the kitchen test (10 cycles).
- **`ui/mac-status-panel`** -- Menu bar shows connected / out-of-range / Bluetooth off. Displays last-seen time when disconnected. Foundation for all future status display (battery, permissions).

### Modified Capabilities

- **`ble-transport/l2cap-channel`** -- Now wrapped in mutual TLS. The raw channel from iter-1 is no longer used directly; all reads and writes go through the TLS stream. The JSON-line framing is unchanged.

## Impact

- All future data flows through a trusted, encrypted channel. This is the security foundation for iter-3 through iter-5.
- The pairing flow is a one-time user action per device pair. If the phone is replaced, the user must re-pair (PRD: "device identity is never hard-coded").
- Certificate pinning means a reinstall on either side breaks the trust -- the unpair-and-repair flow handles this (PRD risk: "Certificate pinning breaks after a reinstall").
- The kitchen test is the Phase 2 gate. Nothing else gets built until reconnect is boring.
- Battery drain measurement at end of this iteration: BLE with long connection interval when idle should be negligible, but this is verified here (PRD risk: "Bluetooth battery drain on the phone").
