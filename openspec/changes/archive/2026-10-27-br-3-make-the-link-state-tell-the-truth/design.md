## Context

See `proposal.md` — Why. The pieces needed are already on disk and unwired:

- `LinkHealth` / `LinkHealthEvaluator` (`mac/.../BLE/LinkHealth.swift`) classify from
  `lastSeen`, `bleConnected`, `bluetoothOn` and already hold the right guidance copy. Zero callers.
- `LinkStateMachine` (`.../BLE/LinkStateMachine.swift`) owns the four-case `LinkState`, posts
  `.bridgeDisconnected`, and drives the reconnect path. It is wired and works.
- `PingWatchdog` tracks `lastSeen` and fires `onTimeout` on a 30 s tick against a 90 s window.
- `BLEManager` publishes `currentLinkState`; `StatusPanel` and `BridgeApp.menuBarIcon` switch on it.
- Android's `LinkState.resolve(isBluetoothOn, hasTrustedMac, isSocketConnected, isVerified)` is
  the shape this change brings the Mac to.

Mac only. Tier 1 (BLE L2CAP) only. No wire-format change.

## Goals / Non-Goals

**Goals:**
- One state value drives every link indicator on the Mac.
- Each of the four BR-3 "Done when" conditions is distinguishable on the screen.
- A send's return value means what callers already assume it means.

**Non-Goals:**
- Replacing or retiring `LinkStateMachine`. It keeps driving reconnect; it is simply no longer
  what the UI reads.
- Changing heartbeat, stale or dead thresholds, or adding packets. (BR-12 owns protocol work.)
- Fixing `PairingManager`'s auto-accept while the trust store is empty — that is BR-5. This
  change only consumes whatever verdict the trust store gives.
- Any Android change.

## Decisions

### D1 — `LinkHealth` becomes the published presentation state; `LinkState` stays internal

`BLEManager` gains `@Published private(set) var linkHealth: LinkHealth`, recomputed by one
private `refreshHealth()` from four inputs it already has or can hold:
`bluetoothOn`, `peripheralConnected`, `isVerified`, `lastSeen` (from `pingWatchdog`), plus the
last disconnect reason (D2). `StatusPanel` and `menuBarIcon` switch on `linkHealth`.

*Alternative rejected:* fold the health cases into `LinkState` and delete `LinkHealth`. That
makes one enum serve two jobs — reconnect control flow and presentation — and would touch
`LinkStateMachine`'s transition rules, its `.bridgeDisconnected` posts and its tests for no
user-visible gain.

`LinkHealth` gains two cases: `.verifying` and `.disconnected`. `.stale(seconds:)`, `.dead`,
`.outOfRange`, `.bluetoothOff`, `.connected` are unchanged. `.dead` is rendered as
"Not responding"; the case name stays as written.

### D2 — Distinguishing a dead service from a departed phone

Two inputs, checked in this order:

1. **The peripheral is still connected and packets stopped** → `.dead`. This is the One UI
   silent-kill path and needs no new signal: the `CBPeripheral` connection outlives the app's
   GATT server in this case, and `LinkHealthEvaluator` already classifies it correctly once it
   is given a truthful `bleConnected`.
2. **The peripheral disconnected** → the reason decides. `BLEManager` records the `CBError` from
   `didDisconnectPeripheral`:
   - `.connectionTimeout` (supervision timeout, link simply faded) → `.outOfRange`
   - `.peripheralDisconnected` (the remote stack tore the connection down — app force-stopped,
     phone Bluetooth off) → `.dead`
   - `nil` (we initiated it) → `.disconnected`
   - anything else → `.outOfRange`, which is the existing behaviour and the safer default for an
     unrecognised code.

*Alternatives rejected:* scanning for the phone's advertisement after the drop (a force-stopped
app stops advertising exactly as a departed phone does, so it cannot discriminate); RSSI at the
moment of disconnect (not delivered with the disconnect callback, and a cabled phone 30 cm away
is the exact case that must work).

### D3 — `.connected` is gated on verification

`isVerified` = an identity packet has been received **and**
`TrustStore.shared.isTrusted(deviceId:fingerprint:)` returns true for it. Two places set it:
`handleIdentityPacket`, and the existing `pairingManager.objectWillChange` sink (so accepting a
pair while the channel is open promotes the state without a reconnect). The L2CAP open handler
transitions to `.connecting`, not `.connected`.

```
Mac                                   Phone
 |-- L2CAP open ---------------------->|      health: .verifying   (was: .connected)
 |-- bridge.identity ----------------->|
 |<-- bridge.identity -----------------|
 |   TrustStore.isTrusted? no          |      health: .verifying
 |<-- bridge.pair (accept) ------------|
 |   TrustStore.isTrusted? yes         |      health: .connected
 |<-- bridge.battery ------------------|      battery rendered
```

### D4 — Re-evaluate on a 10 s tick rather than lowering the thresholds

A `Timer` in `BLEManager`, running only while a channel is open, calls `refreshHealth()` every
10 s. Lowering the dead threshold would mean a faster heartbeat, which costs phone battery and
belongs to a protocol decision this item does not own. The lie window shrinks from 120 s to
≤70 s with no packets added.

### D5 — `L2CAPChannel.send` returns `Bool`

`writeToStream` becomes `@discardableResult func writeToStream(_:) -> Bool`, looping while bytes
remain (`hasSpaceAvailable` + `write`), returning false on a write error, a closed stream, or a
loop that makes no progress. `send` returns false on a serialisation failure too.
`BLEManager.sendPacket` returns `channel.send(packet)`, and returns false early when the peer is
unverified and the packet is not `bridge.identity` or `bridge.pair`.

`NotificationManager`'s reply path already branches on the sender's result, so
`postDeliveryFailure()` starts firing with no change there. The reply-send call site is checked
for the existing `_ =` discards.

### D6 — Unvouched data is hidden, not greyed

`StatusPanel` and the menu bar label show the battery and the mirroring summary only when
`linkHealth == .connected`. The existing `isConnected` helper changes its definition from
`currentLinkState == .connected` to `linkHealth == .connected`, which also correctly disables
`Send File…` on a stale or unverified link.

## Risks / Trade-offs

- **The `CBError` codes may not discriminate on this hardware** → the device-test task checks
  both paths explicitly. If a force-stop arrives as `.connectionTimeout`, the fallback is to drop
  the blame entirely and render an ambiguous disconnect as "Link lost" with both hints, rather
  than keep asserting distance. The criterion is reported as it actually behaves, not as written.
- **Gating on verification can leave a first-run Mac on "Verifying"** if the trust store never
  agrees → that is the honest reading of that state, and it is exactly what Android already
  shows. It also makes BR-5's work visible rather than hidden.
- **Hiding the battery on a stale link loses a last-known reading** → the last-seen line keeps
  the "how long ago" information, which is the part the user can act on. A number with no time
  attached is the lie this item exists to remove.
- **A 10 s timer while connected** → one timer, no I/O, no packets; negligible next to the
  existing 30 s ping.
- **`LinkState` and `LinkHealth` both exist** → two state types invite drift. Mitigated by making
  `LinkHealth` the only thing any view reads, and by a unit test asserting no view-layer file
  references `currentLinkState`.

## Migration Plan

No data, no wire format, no settings. The change is visible wording and timing only; rollback is
the revert of the branch.
