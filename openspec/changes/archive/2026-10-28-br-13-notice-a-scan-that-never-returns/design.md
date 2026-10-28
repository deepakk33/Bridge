## Context

See `proposal.md` — *Why*. What shapes the approach:

`BLEManager.startScanning()` calls `scanForPeripherals` and keeps no record that it did. There is
no `isScanning` flag, no timer, and the one property that looks relevant — `CBCentralManager
.isScanning` — reports this app's *request*, not the daemon's behaviour. It stayed true
throughout the 2026-10-27 incident, which is exactly the failure being fixed.

`LinkHealthEvaluator.evaluate` is already a pure function over `(lastSeen, now, bleConnected,
bluetoothOn, isVerified, lastDisconnect)` with its own unit tests. With `bleConnected == false`
and `lastDisconnect` nil or `.unknown` it returns `.outOfRange` — which is also the state the app
reports from launch, before it has looked at anything. `refreshHealth()` already runs on a 10 s
tick, so a new input is re-evaluated without new plumbing.

`connectToPeripheral` already calls `stopScan()`, sets the delegate and transitions the link, so
a peripheral arriving from `retrieveConnectedPeripherals` can reuse it unchanged.

## Goals / Non-Goals

**Goals**

- A scan is a claim the Mac re-checks on a timer, not a fact it assumes.
- A scan the Mac has not yet checked is reported as *Searching*, not as a conclusion about where
  the phone is.
- Recovery from an externally dropped scan is unattended and bounded.
- A peripheral macOS already holds is found without waiting for an advertisement.

**Non-Goals**

- Single-instance enforcement. That is BR-14. A watchdog must work for scans macOS drops for its
  own reasons, so it is not a substitute and BR-14 is not a substitute for it.
- Backoff or adaptive timing on the watchdog — see decision 2.
- Any Android change, protocol change, or change to the pending-connect reconnect path, which
  already works for a supervision-timeout drop.

## Decisions

### 1. The Mac tracks `isScanning` itself rather than trusting Core Bluetooth

`BLEManager` keeps its own flag, set in `startScanning()` and cleared in `connectToPeripheral`
and `disconnect()`, and treats elapsed time with no `didDiscover` as the signal.

*Alternative rejected:* `centralManager.isScanning`. It is the symptom, not the signal — it
reported `true` for the whole of the observed failure.

### 2. One fixed watchdog period of 20 s, no backoff

Android advertises within 1 s of the foreground service starting and resumes within 2 s of a drop
(`ble-transport/discovery`). 20 s is comfortably longer than any legitimate discovery latency and
short enough that an externally dropped scan is invisible in practice. A restart is `stopScan()` +
`scanForPeripherals()` — cheap enough that repeating it every 20 s while the phone is genuinely
absent costs nothing worth optimising on a Mac.

*Alternatives rejected:* exponential backoff — a long backoff **is** the bug, with the user
sitting in front of a phone 30 cm away; reusing `deadThreshold` (90 s) — that is a
packet-silence threshold on a live link and far too long for discovery.

### 3. `searching` narrows `outOfRange`; it does not replace it

`LinkHealthEvaluator.evaluate` gains one optional input, `searchingSince: Date?` — when the
current scan started, or nil when not scanning. The new branch applies **only** where the
function returns `.outOfRange` today via `lastDisconnect` nil or `.unknown`:

```
if !bleConnected {
    switch lastDisconnect {
    case .local:              return .disconnected
    case .peerTerminated:     return .dead
    case .supervisionTimeout: return .outOfRange        // unchanged
    case .unknown, nil:
        if let since = searchingSince,
           now.timeIntervalSince(since) < scanWatchdogPeriod { return .searching }
        return .outOfRange
    }
}
```

A supervision timeout is *evidence* the phone left, so it stays `.outOfRange` immediately. Launch
and scan-restart are evidence of nothing, so they report `.searching` for one period. The
function stays pure, and every existing test passes with the parameter defaulted to nil.

*Alternative rejected:* a separate `isSearching` published property beside `linkHealth`. Two
sources for one answer is what `link/health`'s first requirement exists to forbid.

### 4. `retrieveConnectedPeripherals` is checked on power-on **and** on every scan restart

Called with `[BridgeConstants.serviceUUID]`; the first result is retained in
`discoveredPeripheral` and handed to the existing `connectToPeripheral`. Checking on restart as
well as on power-on costs one synchronous call per 20 s and covers the case where the system
acquires the peripheral while the app is already scanning.

*Alternative rejected:* calling it once at power-on. That fixes relaunch and nothing else.

### 5. The watchdog is a `Timer` on the existing main-actor pattern

`BLEManager` already owns `healthTimer`, a `Timer.scheduledTimer` hopping back to `@MainActor`
via `Task`. The scan watchdog follows it exactly — same construction, same teardown — rather than
introducing a `DispatchSourceTimer` or an async sleep loop, so Swift 6 strict concurrency has
nothing new to reason about and the file keeps one timer idiom.

## Flow

```
centralManagerDidUpdateState(.poweredOn)
   │
   ├─► retrieveConnectedPeripherals([serviceUUID])
   │        │ non-empty ──► connectToPeripheral(first)  ──► isScanning = false, timer cancelled
   │        └ empty ───────┐
   └──────────────────────►├─► startScanning()
                           │     isScanning = true, searchingSince = now
                           │     schedule scanWatchdog(+20s)
                           │     health: .searching
                           │
          didDiscover ─────┼─► connectToPeripheral  ──► isScanning = false, timer cancelled
                           │
          20s, nothing ────┴─► log "scan produced nothing in 20s — restarting"
                               stopScan()
                               retrieveConnectedPeripherals([serviceUUID])   (decision 4)
                                 │ non-empty ──► connectToPeripheral
                                 └ empty ─────► scanForPeripherals()
                                                searchingSince = now, reschedule
                                                health: .outOfRange from here on
```

## Risks / Trade-offs

- **A 20 s restart loop runs forever while the phone is genuinely away** → Accepted. `stopScan` +
  `scanForPeripherals` is negligible on a Mac on mains power, and the loop is what makes recovery
  unattended. The log line is `info`, one per period, and the health state settles on
  *Out of range* after the first period, so the UI does not flicker between the two.
- **The watchdog can restart a scan that was about to succeed** → A restart reissues the same scan
  immediately and the phone re-advertises within 2 s. Worst case is one extra discovery delay,
  against a current worst case of never.
- **`retrieveConnectedPeripherals` may return a peripheral that is system-connected but has no
  usable Bridge service** → It is filtered by `BridgeConstants.serviceUUID`, and the existing
  `didDiscoverServices` guard already logs and stops on a peripheral without the service. The
  watchdog is still running, so this degrades into the normal retry path rather than a dead end.
- **`searching` is a new case every `switch` over `LinkHealth` must handle** → `LinkHealth` is
  consumed in `StatusPanel`, `LinkHealthEvaluator.guidance` and the menu-bar icon. Swift's
  exhaustiveness check makes each one a compile error until handled, so the failure mode is a
  build failure, never a silent default.
- **The device test needs a state BR-14 has not yet prevented** → Reproducing an externally
  dropped scan means deliberately running a second instance and quitting it. The test task says
  so, and says to confirm one surviving process afterwards, so the result is not read off a
  machine running two apps.

## Migration Plan

None required. No persisted format, protocol field or packet type changes. An updated Mac and an
un-updated phone interoperate exactly as before, and the Mac can be rolled back on its own.
