## Why

Two copies of `Bridge.app` can run at once — easiest reached by building from Xcode while the
installed `/Applications/Bridge.app` copy is already running — and the two fight over the same
Core Bluetooth central. Observed on 2026-10-28 during BR-9's device test: the second instance
quitting sent `stop scan` with `updateScanParams:YES` to `bluetoothd`, which stopped scanning for
the *surviving* instance. The survivor sat on "Out of range" with the phone 30 cm away and
advertising, and only an app restart recovered it.

BR-13 shipped the recovery — a scan watchdog that restarts a scan that discovers nothing. This
change removes the commonest cause. It is the PRD's *"BLE link drops and does not re-establish"*
risk (`docs/PRD.md`, *Risks and what breaks*) attacked at the source rather than the symptom: a
second central is not a condition the app should tolerate and recover from, it is one it should
refuse to create.

Transport tiers: **Tier 1 only** (BLE GATT + L2CAP) is what the collision damages; no tier's
behaviour changes. No packet type is added, removed, or read or written differently.
Permissions: none added on either platform.

## What Changes

- The Mac app becomes single-instance. At launch, before any Core Bluetooth object exists, it
  looks for another running process with the same bundle identifier. If one is found, it
  activates that instance (its menu bar panel comes forward) and exits without starting a BLE
  central, a payload server or a notification listener.
- The check runs on live process identity (`NSRunningApplication`), not a lock file or a pid
  file, so a crash or a force-quit leaves nothing behind to clear: the next launch sees no
  running process and starts normally.
- Android is untouched.

**No BREAKING changes.** No protocol change, no packet-type change, no persisted-format change.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `app-skeleton/mac-menubar`: the app gains a single-instance requirement — a second launch
  surfaces the running instance and exits instead of starting a second BLE central — and the
  guard must not survive a crash or force-quit.

## Impact

- `mac/Bridge/Sources/Bridge/BridgeApp.swift` — the guard runs first in `init()`, ahead of the
  `BLEManager` construction that starts the central.
- One new file under `mac/Bridge/Sources/Bridge/UI/` (or a sibling) holding the decision logic,
  plus a unit test in `mac/Bridge/Tests/BridgeTests/`.
- No new dependency; `AppKit` is already linked through SwiftUI.
- `docs/BACKLOG.md` BR-14 row.
