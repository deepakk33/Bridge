## Why

Two faults live in the same few lines of `BridgeService`'s connect/restart wiring, which is why
they are one item: both are the Android service doing the wrong thing on a reconnection nobody
asked for, and neither can be worked in parallel with the other without colliding.

**Handlers are lost on a rebuild.** `restartBlePeripheral()` builds a new `BlePeripheral` and
re-registers one of the nine handlers `onCreate()` registered. Because each dispatch site in
`L2capServer` invokes an optional (`onShareRequest?.invoke(packet)`), a missing handler is a no-op
with no log: the phone receives the packet, parses it, passes the trust gate, and discards it in
silence. Observed live during BR-19 device testing (PSM 157 → 158) — the Mac sent
`bridge.share.request` three times, the phone logged nothing, and the Mac's payload server timed
out each time. Most of an afternoon went into finding it. Mac→phone file transfer, notification
replies, notification cancels and both clipboard types all fail this way, and all fail quietly.

**The clip is pushed unasked.** BR-9 removed send-on-focus because focus is not consent, but the
phone still puts its clipboard text into the `bridge.clipboard.connect` packet it sends on *every*
reconnect — a Mac app restart, a Bluetooth blip, walking out of range and back. That is the same
silent send BR-9 set out to stop, arriving through a different door, and `clipboard/sync` says so
in an exception clause that points here.

PRD: Tier 1 (BLE L2CAP control channel) — the resident link every other tier is steered over.

## What Changes

- **Handler registration survives a peripheral rebuild.** The handlers become state the *service*
  owns for its own lifetime rather than something re-applied by hand at each construction site, so
  there is no registration step left for a restart path to forget. `restartBlePeripheral()` carries
  the same set across without re-registering anything.
- **A known packet type with no handler is logged.** Each dispatch site that currently calls
  `?.invoke(...)` on a null handler warns with the packet type instead of returning silently.
  Unknown types already log; this closes the gap for *known* ones.
- **The reconnect packet carries a timestamp and no clipboard text.** The phone still sends
  `bridge.clipboard.connect` on reconnect, but omits the `content` field. Mac→phone convergence is
  untouched. Phone→Mac stays manual — the Quick Settings tile and the in-app send control — which
  is what BR-9 intended. **Degradation**: after a gap, the phone's newer clip needs one tap to
  reach the Mac; it no longer arrives on its own.
- **The exception clause in `clipboard/sync` goes away**, replaced by the rule stated outright.
- No new packet type. The `bridge.clipboard.connect` body loses an optional field; the Mac already
  guards on `content` being present and ignores a packet without it, so no Mac behaviour changes.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `ble-transport/l2cap-channel`: added — handler registration is a property of the service's
  lifetime rather than of a construction site, and a received packet of a known type that reaches
  no handler is logged rather than silently dropped.
- `clipboard/sync`: modified — the reconnect exchange carries a timestamp without clipboard text,
  and the phone-to-Mac rule states that outright instead of deferring the question to this item.

## Impact

**Code (Android):**
- `ble/BlePeripheral.kt` — handler storage and the wiring in `startL2capServer()`
- `ble/L2capServer.kt` — where the handler fields live, and the dispatch `when` that invokes them
- `app/BridgeService.kt` — `onCreate()` wiring and `restartBlePeripheral()`
- `clipboard/ClipboardBridge.kt` — `makeConnectPacket()`
- `protocol/BridgePacket.kt` — the `clipboardConnect` factory

**Code (Mac):** no behaviour change. `ClipboardMonitor.handleClipboardConnectPacket` already
returns early when `content` is absent; a test is added to hold that, per the rule that a protocol
change is checked on both sides.

**Protocol:** `protocol/test-vectors/clipboard-connect.json` gains a timestamp-only vector.

**Transport tiers:** Tier 1 (BLE L2CAP) only. Tier 3 (Wi-Fi LAN payload) is the visible casualty
of the handler bug — the share request that starts a Tier 3 transfer is the packet that goes
missing — but no Tier 3 code changes.

**Permissions:** none added or changed, on either platform.

**Dependencies:** none added. Ships before BR-8 — the lost handler is what makes a Mac→phone
transfer untestable. BR-23's detail block records that it depends on this.
