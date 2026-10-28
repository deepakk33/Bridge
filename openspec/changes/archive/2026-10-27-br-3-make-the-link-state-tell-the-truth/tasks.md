## 1. Health model (Mac)

- [x] 1.1 Add `.verifying` and `.disconnected` cases to `LinkHealth` and extend
      `LinkHealthEvaluator.guidance(for:)` — `.verifying` and `.disconnected` return nil, `.dead`
      keeps its restart copy. Verify by extending `LinkHealthTests` to assert the guidance for
      every case, including the two new ones.
- [x] 1.2 Add a disconnect-reason input to `LinkHealthEvaluator.evaluate` (`lastDisconnect:
      DisconnectReason?` with cases `local`, `supervisionTimeout`, `peerTerminated`, `unknown`)
      and apply the D2 precedence: peripheral connected + silence ≥ dead threshold → `.dead`;
      peripheral disconnected → reason decides. Verify with unit tests covering each reason plus
      the still-connected silent-death case.
- [x] 1.3 Add an `isVerified` input so an open channel with an unverified peer evaluates to
      `.verifying`. Verify with a unit test that `bleConnected: true, isVerified: false` never
      yields `.connected` regardless of `lastSeen`.

## 2. Send result (Mac)

- [x] 2.1 Make `L2CAPChannel.writeToStream` loop until every byte is written and return `Bool`;
      return false on a closed/absent stream, a write error, or a loop that makes no progress.
      Verify by unit-testing the loop's byte accounting against a stubbed writer that accepts a
      fixed chunk size.
- [x] 2.2 Make `L2CAPChannel.send` return that `Bool`, false on a serialisation failure. Verify
      the test from 2.1 covers the serialisation-failure path.
- [x] 2.3 Make `BLEManager.sendPacket` return the channel's result, and return false early when
      the peer is unverified and the packet is not `bridge.identity` or `bridge.pair`. Verify by
      grepping every `sendPacket` call site for a discarded result that now matters, and by
      confirming `NotificationManager`'s reply path reaches `postDeliveryFailure()` on false.

## 3. Wiring (Mac)

- [x] 3.1 Track `peripheralConnected`, `isVerified` and the last `CBError`-derived
      `DisconnectReason` in `BLEManager`, set from the Core Bluetooth delegate callbacks and
      `handleIdentityPacket`. Verify the app builds and the logs show the reason on a real
      disconnect.
- [x] 3.2 Add `@Published private(set) var linkHealth` and a private `refreshHealth()` that feeds
      the evaluator from those inputs plus `pingWatchdog.lastSeen`; call it from every input's
      setter and from the packet-received path. Verify with `xcodebuild … build`.
- [x] 3.3 Transition to `.connected` only on verification: the L2CAP open handler goes to
      `.connecting`, and `handleIdentityPacket` plus the existing `pairingManager` sink promote to
      `.connected` when the trust store agrees. Verify on device that the panel shows "Verifying"
      between channel-open and identity, then "Connected".
- [x] 3.4 Run a 10 s re-evaluation `Timer` for the app's lifetime (simpler than tying it to the
      channel, and it keeps the last-seen line ticking while out of range). Verify by observing the panel change from "Connected" to stale without any user
      action within 70 s of killing packet flow.

## 4. Presentation (Mac)

- [x] 4.1 Switch `StatusPanel.statusSection` and `isConnected` onto `linkHealth`, with a distinct
      line per state and the `guidance(for:)` line rendered under a problem state. Verify by
      opening the panel in each reachable state on device.
- [x] 4.2 Switch `BridgeApp.menuBarIcon` onto `linkHealth` with a distinct symbol for `.dead` vs
      `.outOfRange` vs `.verifying`. Verify visually in the menu bar.
- [x] 4.3 Show the last-seen line for `.stale`, `.dead` and `.outOfRange`, sourced from
      `LinkHealthEvaluator.lastSeenText(lastSeen:)` rather than `LinkStateMachine.lastSeenText`.
      Verify the text updates on the 10 s tick.
- [x] 4.4 Gate the battery row, the menu bar battery, the mirroring summary and `Send File…` on
      `linkHealth == .connected`. Verify the battery disappears when the link goes stale.
- [x] 4.5 Confirm no file under `mac/Bridge/Sources/Bridge/UI/` or `BridgeApp.swift` still
      references `currentLinkState` (`grep -rn currentLinkState mac/Bridge/Sources/Bridge/UI
      mac/Bridge/Sources/Bridge/BridgeApp.swift` returns nothing).

## 5. Verify

- [x] 5.1 `xcodebuild -project mac/Bridge/Bridge.xcodeproj -scheme Bridge build` succeeds — read
      the output.
- [x] 5.2 `xcodebuild -project mac/Bridge/Bridge.xcodeproj -scheme Bridge test` succeeds — read
      the output.
- [x] 5.3 `openspec validate br-3-make-the-link-state-tell-the-truth --strict` passes.

## 6. Device test (MacBook Air M3 + Galaxy S25)

Run 2026-10-26/27 on a MacBook Air M3 (macOS 26.6.2) with a Galaxy S25 (SM-S931B) over adb and
BLE. Health transitions are logged at public visibility, so each result is a log line.

- [x] 6.1 **PASS** — `am force-stop com.bridge.app` with the phone on the desk:
      `Disconnect reason: peerTerminated` → `Link health: dead`, 2.1 s later. `.dead` renders
      "Not responding" with the restart guidance. **This settles the design's one open risk**:
      Core Bluetooth does distinguish a remote-terminated disconnect from a supervision timeout
      on this hardware, so the "Link lost" fallback is not needed.
- [ ] 6.2 **UNVERIFIED** — the `.outOfRange` state itself was observed repeatedly on the
      no-peripheral-found path (`12:26:40 Link health: outOfRange` before discovery). What is
      untested is a **supervision-timeout disconnect** specifically: three attempts to carry the
      phone out of range failed to drop the link (BLE held throughout; pings continued), and adb
      cannot produce one — disabling the phone's Bluetooth is a peer-terminated teardown, which
      the spec maps to `.dead` deliberately. Any reason other than local or peer-terminated falls
      through to `.outOfRange`, so the criterion holds by construction; the residual risk is that
      range loss also reports `peerTerminated`, in which case a departed phone would read
      "Not responding". Carried into `docs/ITERATIONS.md` as a known gap.
- [ ] 6.3 **UNTESTED** — a reply into a dead link posting the delivery-failure notification.
      Needs a mirrored notification on screen and a human clicking Reply. Both halves are covered
      in isolation: `FramedWriterTests` for the send result, and
      `PingWatchdogTests.testTimeoutCallbackFiresAfterSilence`, which fails against the pre-fix
      code, for the watchdog callback that had never run.
- [x] 6.4 **PASS** — a fresh connect never claims "Connected" before verification. Observed on
      three separate launches, e.g. `12:26:40 outOfRange` → `12:26:41 verifying` →
      `12:26:42 connected`, discovery at RSSI −43 in 33 ms.
- [x] 6.5 **PASS** — restarting the phone service recovers: restart `12:16:12`,
      `verifying` → `connected` in ~1.7 s.
- [x] 6.6 **PASS** (not in the original list) — `bluetoothOff` observed on two live Bluetooth
      toggles with the app untouched: `12:20:56 bluetoothOff` → `12:21:02 outOfRange`, and again
      at `12:21:59` / `12:22:23`. Recovery needed no restart.

### Defects found by the device test (all fixed)

1. **Stale reconnect** — the first restart run showed `dead` → `stale(seconds: 66)` →
   `verifying` → `connected`: staleness was measured against the *previous* session's last
   packet, so a fresh link read as 66 seconds silent. `PingWatchdog.resetClock()` is now called
   on `didConnect`.
2. **Unknown Bluetooth state rendered as "off"** — Core Bluetooth reports no state until its TCC
   round-trip completes, measured at **34 s** on this machine, and goes unknown across sleep. The
   10 s health tick read that as `poweredOff` and showed a red "Bluetooth off" with settings
   guidance throughout. An unreported state is now `.disconnected`. This is why the app had been
   flapping `bluetoothOff`/`outOfRange` every ~15 minutes overnight across sleep cycles.

### Not a defect in this change

A long stretch of the session was spent on the Mac failing to discover an advertising phone. The
cause was **two Bridge app instances running against one phone** (a stale build from a second
DerivedData path that the test's `pkill` pattern never matched). With a single instance,
discovery takes 33 ms. Nothing in this change touches scanning or connecting.

It did expose a real gap, filed as **BR-13**: `BLEManager` cannot tell "I asked Core Bluetooth to
scan and nothing arrived" from "nothing is there". When the quitting instance sent `stop scan`
with `updateScanParams:YES` seven seconds after the live instance started its own scan, the live
app kept believing it was scanning while `bluetoothd` was not scanning for it — and it would have
sat on "Out of range" indefinitely with no retry.
