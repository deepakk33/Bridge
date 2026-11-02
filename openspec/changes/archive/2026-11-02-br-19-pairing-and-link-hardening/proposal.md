## Why

Three defects found while reviewing `work/br-4-transport-security-and-pairing` were deliberately
left out of that branch so it would not widen further. Each is real, and the first is now visible
in daily use: because BR-5 replaced automatic pairing with a confirmation a human has to answer,
a link can sit idle for minutes while a prompt is open — and that is exactly when the phone's
link probe tears it down.

1. **The Mac never answers a ping.** `PingHandler.handle()` logs and returns `true`; it sends
   nothing. Android's `probeLink()` (PRD §Reliability, `link/handshake`) sends a ping and closes
   the socket unless *some* packet arrives within 3 s. The Mac's own heartbeat runs at 30 s, so
   the probe fails whether or not the peer is healthy. It was invisible while pairing was
   automatic; now the link drops repeatedly during pairing and reconnects.
2. **`TrustStore.pairedDeviceId()` returns an arbitrary key.** Mac `TrustStore.swift:55` and
   Android `pairedFingerprint()` both take the first entry of an unordered map. With two pins —
   reachable, because a non-empty trust store connects to any advertiser — `PayloadTLS` pins the
   payload connection to the wrong peer. The spec already says the pin is "for that `deviceId`";
   the implementation does not honour it.
3. **The Mac's local fingerprint arrives asynchronously.** `setLocalFingerprint` is delivered by a
   detached `Task` in `BLEManager`; an identity packet that beats it makes `presentPairConfirmation`
   return with no prompt and no retry, so pairing silently cannot start. Android's `by lazy`
   `localFingerprint` caches a `null` from a transient `CertificateManager` failure, failing the
   same way until the process restarts.

## What Changes

- **The Mac answers `bridge.ping` with a pong.** `PingHandler.handle` returns an optional
  `BridgePacket` carrying `replyTo: <ping id>`, mirroring Android's `PingHandler`, and
  `BLEManager` sends it.
- **`bridge.ping` joins the pre-verification packet set.** `PacketGate.alwaysAllowed` gains
  `bridge.ping`, so a ping is answered while a pair confirmation is still outstanding. This
  removes an asymmetry rather than adding exposure: `BLEManager.preVerificationPacketTypes`
  already *sends* pings to unverified peers for precisely this reason, so a stranger already
  learns a Bridge endpoint is live.
- **The payload connection pins to the peer actually connected.** The live peer's `deviceId` is
  plumbed into `PayloadTLS` / `PayloadTls` instead of being guessed from the trust store.
  `pairedDeviceId()` and `pairedFingerprint()` are removed, not left as a trap.
- **The local fingerprint is awaited, not raced.** `presentPairConfirmation` is re-driven when
  the fingerprint lands, so an identity packet that arrives first still produces a prompt.
  Android's `localFingerprint` stops caching a failed read.

No new packet type, no new permission, no transport-tier change. Tier 1 (BLE L2CAP) carries the
ping; Tiers 2–4 carry the payload connection whose pin is corrected.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `reliability/watchdog`: a received `bridge.ping` is now answered with a pong. The current
  "Ping response" scenario states the opposite ("no explicit pong required"), which is what left
  the Mac silent.
- `protocol/pairing`: `bridge.ping` is added to the packets an unidentified peer may exchange,
  and a pair confirmation is required to appear regardless of the order in which the identity
  packet and the local fingerprint become available.
- `file-transfer/payload-channel`: the pinned fingerprint used for the payload handshake is the
  one for the connected peer's `deviceId`, stated as a scenario that bites only when more than
  one device is pinned.
- `link/handshake`: a probe issued while a pair confirmation is outstanding leaves the link up.

## Impact

**Mac** (`mac/Bridge/Sources/Bridge/`)
- `Protocol/PingHandler.swift` — returns a pong packet
- `BLE/BLEManager.swift` — sends the pong; re-drives a pending confirmation when the fingerprint lands
- `Security/PacketGate.swift` — `bridge.ping` in `alwaysAllowed`
- `Security/PairingManager.swift` — `setLocalFingerprint` retries a deferred confirmation
- `Security/TrustStore.swift` — `pairedDeviceId()` removed
- `FileTransfer/PayloadTLS.swift` — takes the connected peer's `deviceId`
- Tests: `PingPacketTests`, `PacketGateTests`, `PairingManagerTests`, `PayloadChannelTests`

**Android** (`android/Bridge/app/src/main/java/com/bridge/`)
- `ble/L2capServer.kt` — `localFingerprint` no longer caches a failure
- `security/TrustStore.kt` — `pairedFingerprint()` removed
- `security/PairingManager.kt` — confirmation survives a late fingerprint
- `filetransfer/PayloadTls.kt` — takes the connected peer's `deviceId`
- Tests: `PairingManagerTest`, payload TLS tests

**Protocol** (`protocol/`)
- A ping/pong vector pair, checked on both sides.

**Permissions**: none added or changed on either platform.
