Mac only. Verify commands used below, run from this worktree.

Device-test status, 2026-10-28, MacBook Air M3 + Galaxy S25, single instance confirmed:

- Confirmed on hardware: `.searching` is reported from launch and holds across discovery and
  the connect attempt (17:03:55.260 → 17:03:58.353, no `outOfRange` in between). This also
  caught a defect the unit tests could not — see commit ad433bb.
- Not reproducible: the phone was in range and advertising for every run, discovered in
  93ms-3.9s, so the watchdog never fired. 2.1's second half ("flips to Out of range after
  20s"), 2.2 (restart logged) and 2.3 (system-held peripheral — macOS released it on each
  quit) are **untested**, not passed. They need the phone out of BLE range, or its Bridge
  service stopped.


```bash
xcodebuild -project mac/Bridge/Bridge.xcodeproj -scheme Bridge build
xcodebuild -project mac/Bridge/Bridge.xcodeproj -scheme Bridge test
openspec validate br-13-notice-a-scan-that-never-returns
```

## 1. Link health gains a Searching state

- [x] 1.1 Add `case searching` to `LinkHealth` and a `scanWatchdogPeriod` constant (20 s) to
      `LinkHealthEvaluator`; verify `xcodebuild … build` fails only with exhaustive-switch errors
      at the known call sites (`StatusPanel`'s status row, its menu-bar icon, and
      `guidance(for:)`), confirming no consumer silently defaults.
- [x] 1.2 Write the failing `LinkHealthTests` cases first, per design decision 3: `searchingSince`
      within the period with `lastDisconnect` nil → `.searching`; beyond the period →
      `.outOfRange`; `.supervisionTimeout` → `.outOfRange` regardless of `searchingSince`;
      `.peerTerminated` → `.dead`; `.local` → `.disconnected`; `searchingSince` nil →
      `.outOfRange`. Verify with `xcodebuild … test` that they fail for the right reason.
- [x] 1.3 Add the `searchingSince: Date? = nil` parameter and the branch to
      `LinkHealthEvaluator.evaluate`; verify `xcodebuild … test` turns 1.2 green and every
      pre-existing `LinkHealthTests` case still passes on the defaulted parameter.
- [x] 1.4 Return `nil` from `guidance(for: .searching)` and render a "Searching…" status distinct
      from "Out of range" in `StatusPanel`, including the menu-bar icon branch; verify
      `xcodebuild … build` succeeds with no exhaustiveness errors left.
- [x] 1.5 Commit (`feat(mac): BR-13 add a Searching link-health state`) with the `xcodebuild …
      test` summary line in the body.

## 2. Scan watchdog and system-held peripheral

- [ ] 2.1 Add `isScanning` and `searchingSince` to `BLEManager`, set in `startScanning()` and
      cleared in `connectToPeripheral` and `disconnect()`, and pass `searchingSince` into
      `refreshHealth()`'s `evaluate` call. Verify on the Mac with no phone present that the panel
      reads "Searching…" on launch and flips to "Out of range" after 20 s.
- [ ] 2.2 Add the scan watchdog `Timer` following `healthTimer`'s existing pattern (design
      decision 5): 20 s, rescheduled on each restart, invalidated on discovery, on connect, in
      `disconnect()` and on `.poweredOff`. On fire, log at `info` and call `stopScan()` then
      `startScanning()`. Verify with `log stream --predicate 'subsystem == "com.bridge.mac"'`
      that exactly one restart line appears per period with no phone present, and none while the
      link is up.
- [ ] 2.3 Add `retrieveConnectedPeripherals(withServices: [BridgeConstants.serviceUUID])` on
      `.poweredOn` and at the top of each watchdog restart, connecting to the first result through
      the existing `connectToPeripheral`. Verify by launching the Mac app while macOS already
      holds the phone's peripheral: the link comes up with no advertisement wait.
- [x] 2.4 Re-run `xcodebuild … build` and `xcodebuild … test`, read both outputs, and commit
      (`feat(mac): BR-13 scan watchdog and system-held peripheral recovery`) with the test summary
      line in the body.

## 3. Verification and device test

- [x] 3.1 Run `openspec validate br-13-notice-a-scan-that-never-returns` and fix whatever it
      reports.
- [x] 3.2 Run `xcodebuild … build` and `xcodebuild … test` from a clean state; record each final
      status line for the review gate.
- [ ] 3.3 **Device test** on the MacBook Air M3 with the Galaxy S25 in range. First confirm a
      single instance with `pgrep -fl Bridge.app` and kill any stray — BR-14 is not done, so this
      is a precondition, not an assumption. Then walk BR-13's four "Done when" criteria:
      (a) a scan that returns nothing for a bounded period is restarted, and the restart is
      logged — check `log stream`;
      (b) the app reconnects after its scan is dropped externally, with no restart and no
      Bluetooth toggle — reproduce by launching a second copy and quitting it, then confirm the
      survivor reconnects within one watchdog period;
      (c) a peripheral already connected at the OS level is found without waiting for an
      advertisement — relaunch while macOS holds the link;
      (d) the panel distinguishes "looking" from "looked and found nothing for a long time".
      Record pass/fail per criterion with what was observed; anything not reproducible is
      reported untested, never passed.
