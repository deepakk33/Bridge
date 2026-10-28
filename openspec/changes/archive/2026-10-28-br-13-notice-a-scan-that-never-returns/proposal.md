## Why

`BLEManager` cannot tell *"I asked Core Bluetooth to scan and nothing arrived"* from *"nothing is
there"*. It calls `scanForPeripherals` and keeps no record that it did — no flag, no timer, and
Core Bluetooth gives a central no callback and no trustworthy property when the daemon stops
scanning on its behalf.

Observed live on 2026-10-27: a second app instance quitting sent `stop scan` with
`updateScanParams:YES` to `bluetoothd` seven seconds after the live instance started its own
scan. The live app kept believing it was scanning while the daemon was not scanning for it, and
sat on "Out of range" indefinitely with no retry. Recovery needed an app restart or a Bluetooth
toggle — with the phone 30 cm away, advertising connectably the whole time.

`BLEManager` also never calls `retrieveConnectedPeripherals(withServices:)`, which is how Core
Bluetooth expects an app to find a peripheral the system already holds, so a relaunch while macOS
has the link finds nothing by design.

This is the PRD's "failure mode that matters most" (`docs/PRD.md`, *Risks and what breaks*): the
tool stops working and the screen reports a confident, wrong cause. It is also the PRD's
*"BLE link drops and does not re-establish"* risk, whose stated mitigation — pending connect plus
an out-of-range state — does not cover a scan that was never running.

Transport tiers: **Tier 1 only** (BLE GATT + L2CAP). Tiers 2–4 untouched. No packet type is
added, removed, read or written differently.

## What Changes

- Add a scan watchdog to `BLEManager`: while the link is down and a scan is believed active, no
  `didDiscover` within a bounded period stops and restarts the scan, and logs the restart.
- Track `isScanning` in `BLEManager` rather than inferring it from Core Bluetooth, which reports
  this app's *request* and stayed true throughout the 2026-10-27 incident.
- Call `retrieveConnectedPeripherals(withServices:)` on power-on and on each scan restart, and
  connect to a peripheral the system already holds without waiting for an advertisement.
- Add a `searching(…)` link-health state so the panel distinguishes *looking* from *looked and
  found nothing for a long time*. "Out of range" stops being what a never-started scan reports.

**No BREAKING changes.** No protocol change, no packet-type change, no persisted-format change.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `ble-transport/discovery`: the Mac's scan gains a liveness requirement — a scan that discovers
  nothing within a bounded period is restarted and the restart is logged — plus a requirement to
  find an already-connected peripheral via `retrieveConnectedPeripherals`.
- `link/health`: a new **Searching** state, and the Out-of-range rule narrows — "no peripheral
  found at all" is no longer reported as Out of range for an unbounded time.

## Impact

**Permissions.** None new. Mac needs Bluetooth (Core Bluetooth) only;
`retrieveConnectedPeripherals` requires no additional TCC grant. Android is untouched by this
change.

**Mac code.** `BLE/BLEManager.swift` (scan watchdog timer, `isScanning`/`searchingSince`,
`retrieveConnectedPeripherals`), `BLE/LinkHealth.swift` (`searching` case, evaluator input,
`guidance(for:)`), `UI/StatusPanel.swift` (searching row and menu-bar icon),
`Tests/BridgeTests/LinkHealthTests.swift`.

**Not touched.** Android entirely. Protocol, `protocol/` vectors, file transfer, clipboard,
notifications, pairing, trust store, and every tier above 1.

**Related items.** BR-14 (single-instance guard) removes the commonest *cause* of a dropped scan;
this item makes the app *recover* from one, whatever the cause, and the backlog states BR-13
lands first. BR-14 is explicitly not attempted here, so BR-13's device test must be run with one
Mac instance confirmed by hand.
