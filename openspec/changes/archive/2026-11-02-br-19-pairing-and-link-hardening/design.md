## Context

See `proposal.md` — Why. The state that matters for the approach:

- Mac `PingHandler.handle(_:) -> Bool` logs and returns; nothing is sent. Android
  `PingHandler.handle(packet) -> BridgePacket` already returns a pong with `replyTo`, and
  `L2capServer` sends it. The asymmetry is the whole of defect 1.
- `PacketGate.alwaysAllowed` is `["bridge.identity", "bridge.pair"]` on both sides, but
  `BLEManager.preVerificationPacketTypes` is `["bridge.identity", "bridge.pair", "bridge.ping"]`.
  The Mac already sends pings to a peer it has not verified; it just refuses to receive them.
- `PayloadTLS.pairedPeerParameters()` and `PayloadTls.forPairedPeer()` take no argument. Both
  reach into the trust store for "the" paired device. Every caller
  (`FileTransferManager`, `FileReceiveController`, `FileTransferCoordinator`, `FileReceiver`)
  is constructed or invoked from a site that *does* know the live peer — `BLEManager` holds
  `peerDeviceId`, `L2capServer` holds `peerDeviceId` — the value is simply not passed down.
- Mac `PairingManager.presentPairConfirmation` returns early when `localFingerprint` is nil and
  nothing re-drives it. Android `L2capServer.localFingerprint` is `by lazy`, so a thrown
  `CertificateManager` read is memoised as `null` for the process lifetime, and
  `PairingManager` is constructed with that `null` once per connection.

## Goals / Non-Goals

**Goals**
- A ping is answered by both platforms, within one round trip, including before verification.
- The payload handshake is pinned to the peer on the control connection.
- A pair confirmation appears regardless of which of the two fingerprints lands first.

**Non-Goals**
- No change to the heartbeat interval (30 s), stale (60 s) or dead (90 s) thresholds, or to the
  probe timeout (3 s). This change makes the existing timings work, it does not retune them.
- No support for more than one concurrently *connected* peer. Multiple *pinned* peers already
  occur and are what defect 2 is about; one live control connection remains the model.
- No new packet type and no change to the `bridge.ping` wire shape beyond using the `replyTo`
  field Android already emits.

## Decisions

### 1. The Mac answers, and `bridge.ping` is ungated

`PingHandler.handle` returns `BridgePacket?`; `BLEManager`'s `case "bridge.ping"` sends a
non-nil result. `PacketGate.alwaysAllowed` gains `bridge.ping` on both platforms.

*Why over the alternatives.* Answering only when trusted (alternative A) leaves the probe killing
the link during pairing, which is the failure actually being reported, so it would need
`probeLink` suppressed while a confirmation is outstanding — a pairing-state flag plumbed from
`PairingManager` through `BlePeripheral` into `L2capServer` for no gain. Dropping the probe's
reply expectation (alternative B) is the smallest diff but gives back the `link/handshake`
guarantee: a stale "Connected" would then persist up to the 90 s watchdog.

*Why ungating is not a new exposure.* `PacketGate`'s comment argues that answering a ping
"confirms a live Bridge endpoint to a peer that has not identified itself". That is true and
already the case: the Mac broadcasts the same fact unprompted every 30 s to any unverified peer
holding a channel open, by deliberate decision recorded at `BLEManager.swift:238`. A ping carries
no body. The gate's own second paragraph assumes unanswered pings are harmless because watchdogs
feed on arrival — correct for the 90 s watchdog, wrong for the 3 s probe, which is what this
change fixes.

### 2. A reply is never answered — this is the one real hazard

Today only one side answers, so no loop is possible. The moment both answer, an unguarded
handler ping-pongs at line rate for the life of the connection: Mac pongs Android's pong, Android
pongs that, forever.

The guard is the `replyTo` key, which Android's pong already carries:

```
handle(packet):
    if packet.body has "replyTo" -> return nil      # terminal, nothing sent
    return ping(replyTo: packet.id)
```

Both `PingHandler`s get this check, and Android's existing one needs it added — it currently
answers everything. A unit test on each side asserts a reply produces no outbound packet; this is
the single most important test in the change.

```
phone                                   Mac
  | --- ping(id=N) ---------------------> |   gate: alwaysAllowed, no verification needed
  |                                       |   watchdog.packetReceived()
  | <-- ping(id=M, replyTo=N) ----------- |
  | watchdog.packetReceived()             |
  | probe sees lastPacketAt >= sentAt     |
  | (replyTo present -> nothing sent)     |
```

### 3. The live peer id is injected, not looked up

`PayloadTLS.pairedPeerParameters(for deviceId: String)` and
`PayloadTls.forPairedPeer(deviceId: String)`; `pairedDeviceId()` and `pairedFingerprint()` are
deleted so nothing can reach for the ambiguous value again.

Callers receive a `() -> String?` supplier rather than a stored id, injected at construction the
way `sendPacket` already is on both platforms (`FileTransferManager`'s header states the reason:
"the control-channel send is injected so this stays decoupled from BLEManager"). A supplier, not
a value, because the connection can change between construction and transfer; `L2capServer`
clears `peerDeviceId` in its `finally`, and reading it late is what makes a transfer across a
reconnect fail closed instead of pinning a stale id.

A nil supplier result is `Error.notPaired` / `NotPairedException` — the existing refusal path,
reported to the user, never an unencrypted fallback.

*Alternative rejected*: keeping the no-argument form and making it throw when more than one
device is pinned. It turns a wrong-peer bug into an outage the moment a second phone is paired,
and the correct value was available at every call site all along.

### 4. The pair confirmation is deferred, not dropped

**Mac.** `PairingManager` stores the peer triple when `localFingerprint` is nil instead of
returning:

```
presentPairConfirmation(peer):
    guard localDecision == .pending
    if localFingerprint == nil: deferredConfirmation = peer; return
    ...present...

setLocalFingerprint(fp):
    localFingerprint = fp
    if let peer = deferredConfirmation { deferredConfirmation = nil; presentPairConfirmation(peer) }
```

`reset()` clears `deferredConfirmation`, so a peer that disconnects while deferred produces no
late prompt for a connection that is gone.

**Android.** `L2capServer.localFingerprint` stops being `by lazy` and becomes a function that
caches only a successful read, so a transient keystore failure is retried on the next pair
request. `PairingManager` takes `localFingerprint: () -> String?` instead of a captured `String?`,
and applies the same defer-and-replay when it returns null. Android reads the fingerprint
synchronously on the IO thread, so the deferral there covers the transient-failure case rather
than an ordering race.

## Risks / Trade-offs

- **Ping loop between the two platforms** → the `replyTo` guard above, with a unit test on each
  side, plus a device check that an idle paired link stays quiet (one ping per 30 s, one reply,
  nothing more).
- **An unverified peer can elicit a reply** → accepted, and argued in decision 1: the Mac already
  volunteers the same signal unprompted. The gate still admits no other packet type, so nothing
  reaches a handler that touches notifications, clipboard or files.
- **Deleting `pairedDeviceId()` / `pairedFingerprint()` breaks every caller at once** → that is
  the point; the compiler enumerates the call sites, and all four are in this change's scope.
- **A deferred confirmation could linger across a reconnect** → cleared in `reset()` (Mac) and in
  `L2capServer`'s `finally` block (Android), which already clears `PairConfirmation` for exactly
  this reason.
- **The probe becomes strict rather than always-failing** → it now genuinely closes a link whose
  peer does not answer in 3 s. A peer stalled by a slow Keychain or keystore read could be cut
  off where previously it was cut off anyway; the behaviour is no worse and the reconnect path is
  unchanged.

## Migration Plan

No data migration: no persisted format changes, and trust store entries are untouched. The two
apps are deployed independently, so both mixed states are exercised in the device test:

- **New Mac, old phone**: the Mac answers; the old phone answers everything including replies, so
  the Mac's reply draws a reply. The Mac's guard terminates it after one extra round trip. Safe,
  and the reason the guard must land on the Mac in the same build that starts answering.
- **Old Mac, new phone**: unchanged from today — the Mac stays silent and the probe fails as it
  does now. No regression.

Rollback is reverting the branch; nothing on disk needs undoing.

## Open Questions

None. The one open decision — whether the Mac answers or the probe stops expecting a reply — was
put to the user and resolved as decision 1.
