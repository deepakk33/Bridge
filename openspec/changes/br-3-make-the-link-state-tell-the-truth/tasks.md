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

Run 2026-10-26 on a MacBook Air M3 (macOS 26.6.2) with the Galaxy S25 BLE-connected. The phone
was **not reachable over adb** and could not be driven, so every criterion that needs the phone
force-stopped, moved, or restarted is reported **untested**, not passed.

- [ ] 6.1 **UNTESTED** — force-stop `com.bridge.app` with the phone on the desk: expect
      "Not responding" plus the restart guidance. Needs adb or hands on the phone. The
      `CBError` code this hinges on is therefore also unobserved; `Disconnect reason:` is
      logged at public visibility so one run will settle it.
- [ ] 6.2 **UNTESTED** — carry the phone out of BLE range: expect "Out of range" with a
      last-seen line. Needs someone to walk away with the phone.
- [ ] 6.3 **UNTESTED** — reply into a dead link and expect the delivery-failure notification.
      Depends on 6.1. The send path's failure reporting is covered by `FramedWriterTests`, and
      the watchdog callback that was silently dead is covered by `PingWatchdogTests`
      (`testTimeoutCallbackFiresAfterSilence` fails against the pre-fix code).
- [x] 6.4 **PASS** — a fresh connect does not claim "Connected" before verification. Observed:
      `15:23:00.603 Link health: outOfRange` → `15:23:21.408 Link health: verifying` →
      `15:23:22.011 Link health: connected`, with four `Dropping packet from untrusted device`
      lines in the 603 ms the peer was unverified.
- [ ] 6.5 **UNTESTED** — restart the phone service and expect "Connected" plus the battery
      reading within 10 s. Depends on 6.1. The connect half was observed in 6.4; the
      recover-from-dead half was not.
