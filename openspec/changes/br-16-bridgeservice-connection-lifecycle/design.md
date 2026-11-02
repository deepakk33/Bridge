## Context

See proposal.md — Why. The structural facts that shape the fix:

**Handlers.** `BridgeService.onCreate()` registers nine handlers through
`BlePeripheral.setXHandler(...)`. `restartBlePeripheral()` builds a **new** `BlePeripheral` and
re-registers one. `BlePeripheral` already mirrors five of the nine into private fields and
re-applies them in `startL2capServer()`; the other four (`notificationReply`,
`notificationRequest`, `clipboard`, `clipboardConnect`) write straight through to
`l2capServer?.onX` with no field behind them — which is also why `onCreate()` must call those four
*after* `start()` and the rest *before*, an ordering constraint nothing states and nothing
enforces. The owner of the handlers is therefore an object that is itself thrown away on restart;
any fix that keeps re-registration as a step leaves the same bug one restart path away.

**Clipboard.** `ClipboardBridge.makeConnectPacket()` calls `BridgePacket.clipboardConnect(text,
ts)` with the current clip. `BridgeService`'s connect handler sends it on every link-ready.
On the receiving side, `ClipboardMonitor.handleClipboardConnectPacket` (Mac) opens with
`guard let content = packet.body["content"] as? String else { return }`, and Android's equivalent
with `?: return` — so a packet with no `content` key is already ignored by both. An *empty string*
is not: it would be a well-formed newer clip and could clear the peer's clipboard.

Android-only behaviour change. One optional field leaves a packet body; no new packet type.

## Goals / Non-Goals

**Goals:**

- Remove the re-registration step rather than duplicate it, so there is no call site left that can
  forget.
- Remove the unstated before-`start()` / after-`start()` ordering constraint in `onCreate()`.
- Make a dropped packet visible in logcat.
- Make a reconnect move no clip phone-ward, and leave `clipboard/sync` saying so plainly.

**Non-Goals:**

- No change to `L2capServer`'s framing or trust gate beyond the null-handler log.
- No change to which packet types the phone accepts, and no Mac behaviour change.
- Not a general event-bus or listener-registry abstraction. One plain holder, constructor-passed.
- No setting, no new UI, no new packet type for the clipboard half.

## Decisions

### Handlers move into a holder the service owns for its own lifetime

Introduce `com.bridge.ble.BleHandlers`: a plain class of nullable function properties, one per
handler `BridgeService` supplies. `BridgeService` creates **one** instance in `onCreate()`, wires
it once, and passes the same instance to every `BlePeripheral` it constructs — including the one
`restartBlePeripheral()` builds. `BlePeripheral` passes it on to each `L2capServer`, which reads
`handlers.onShareRequest` where it reads `onShareRequest` today.

Consequence: there is no re-registration anywhere. A rebuilt peripheral is wired by construction,
because the wiring is the same object. The nine `setXHandler` functions on `BlePeripheral` and the
five mirror fields it keeps go away — a net deletion.

*Alternative considered — extract `wireBleHandlers()` and call it from both sites.* Smaller diff,
but it keeps re-registration as a step a third call site can omit, and it keeps the
before/after-`start()` ordering trap. It fixes today's instance of the bug, not the bug.

*Alternative considered — register handlers inside `BlePeripheral.start()`* (the backlog note's
suggestion). Same objection: `start()` can only apply what was registered on *that* instance, and
the instance is what restart replaces.

`onLinkReady` and `onPeerVerified` stay as `L2capServer`'s own properties: `BlePeripheral` wires
them to itself at construction, so rebuilding re-wires them correctly by definition. Only handlers
that come from outside `BlePeripheral` move into the holder.

### A null handler on a known type logs at warn

At each dispatch site that invokes an optional handler, log the packet type when the handler is
absent, via one small private helper in `L2capServer` rather than nine copies of an `if`. The
existing `else -> Log.w(TAG, "Unknown packet type: ...")` arm is unchanged.

### The phone's reconnect packet omits `content`, rather than sending it empty

`BridgePacket.clipboardConnect` gains a form that puts only `timestamp` in the body. The key is
**absent**, not empty: both receivers already guard on its presence and ignore such a packet,
whereas `content: ""` would read as a newer empty clip and could clear the peer's pasteboard.
This is why the delta spec states "ignore, not treat as empty" as its own scenario — it is the
failure mode a careless implementation lands on.

*Alternative considered — stop sending the packet from the phone at all.* The timestamp is the
only thing the Mac could later use to tell the user "the phone has a newer clip"; dropping the
packet throws that away for no gain, and widens the diff into the Mac's connect bookkeeping.

*Alternative considered — an opt-in setting.* Rejected by the user on 2026-11-02: it costs a
settings row and persistence, and anyone who enables it is back to unasked sends on every
Bluetooth blip.

The Mac keeps sending its pasteboard on connect. Mac→phone convergence after a gap is unchanged.

### Where the tests sit

`BleHandlers` is plain Kotlin with no Android types, so it unit-tests on the JVM: wire a holder,
hand the same instance to a second "generation", assert every handler still fires. That is the
regression guard for the structural claim.

`BlePeripheral` and `L2capServer` need a `BluetoothAdapter` and a live socket, so the end-to-end
claim — a Mac→phone transfer after a real adapter cycle — is a device test, and the null-handler
log line is read from logcat. Stated plainly: the JVM test proves the holder is shared, the device
test proves the behaviour.

The clipboard half is unit-testable on both sides: a packet-shape test on Android, and a Mac test
that a connect packet with a newer timestamp and no `content` leaves the pasteboard alone. A
shared vector in `protocol/test-vectors/clipboard-connect.json` keeps the two honest.

## Risks / Trade-offs

- **The holder is mutable shared state reachable from the L2CAP read loop.** → It is written once
  on the main thread during `onCreate()` before any peripheral starts, and only read afterwards.
  The handlers already cross threads today (`L2capServer`'s read loop invokes them directly); this
  changes where the reference is stored, not which thread runs the body.
- **Touching `L2capServer`'s dispatch risks the packet path pairing depends on.** → The change at
  each site is the receiver of `?.invoke`, not the condition or the trust gate. Full Android unit
  suite plus a pairing check on the device.
- **Phone→Mac clipboard convergence after a gap is lost.** → Accepted and recorded as a
  degradation: it is the point of the change, not a side effect. The tile and the in-app control
  remain, and this is the behaviour BR-9 asked for. Recorded in `docs/ITERATIONS.md` at wrap.
- **An older Mac build paired with this phone** sees a connect packet with no `content` and
  ignores it — the same outcome as today when the phone's clip is older. No version gate needed.
- **A device test needs a real adapter cycle**, and Bluetooth off/on also tears down the Mac's
  scan. → Test in the order: pair, confirm transfer works, cycle Bluetooth, re-link, transfer
  again. Only one window may hold the devices.
- **Deleting `BlePeripheral`'s setters is a wider diff than a one-line patch** for a bug already
  understood. → Accepted: the narrow patch leaves the trap in place, and the setters have exactly
  one caller each.

## Migration Plan

None — no persisted state, no new packet type, and a dropped optional field that both sides
already tolerate. Rollback is reverting the branch; a phone on either build talks to a Mac on
either build.
