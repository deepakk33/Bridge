## 1. Mac — the root fix

- [x] 1.1 Add `.menuBarExtraStyle(.window)` to the `MenuBarExtra` scene in `BridgeApp.swift`, and verify with
      `xcodebuild -project mac/Bridge/Bridge.xcodeproj -scheme Bridge build` that it compiles, then by launching
      the app and confirming the panel opens as a popover rather than a menu (the dividers now draw as rules, not
      menu separators).

## 2. Mac — panel layout

- [x] 2.1 Apply `.frame(width: 260)` and `.padding(12)` to `StatusPanel`'s root `VStack` and cut the four
      dividers to two (above `transferSection`, above `actionSection`); verify by opening the panel in connected
      and disconnected states and confirming the width does not change between them.
- [x] 2.2 Give `Send File…` `.buttonStyle(.borderedProminent)` and change the drag copy from "onto this menu" to
      name the panel, rendering it only when a send is possible; verify visually that the button is distinguishable
      from the labels around it and that the copy matches what accepts a drop.
- [x] 2.3 Add a line limit with truncation to the device-name row so a long name cannot stretch the panel; verify
      by temporarily rendering a 40-character name in an Xcode preview and confirming the frame holds at 260 pt.

## 3. Mac — permission rows

- [x] 3.1 Add `var needsAttention: [PermissionItem]` to `PermissionChecker`, filtering `items` to
      `status != .granted`; verify the project still builds.
- [x] 3.2 Add a unit test in `PermissionCheckerTests` covering `needsAttention`: all-granted returns empty, a
      mixed set returns only the non-granted items, and a revoked item reappears after the status changes; verify
      with `xcodebuild -project mac/Bridge/Bridge.xcodeproj -scheme Bridge test`.
- [x] 3.3 Render `permissionSection` from `needsAttention` and wrap the whole section — heading included — in
      `if !permissions.needsAttention.isEmpty`; verify in the running app that each listed permission is one row
      (dot, name, status, action) and that the section is absent when nothing needs attention.

## 4. Mac — inline pair confirmation

- [x] 4.1 Reshape `PairPromptView` for inline use: drop `.frame(width: 340)` and `.padding(24)`, shrink the 40 pt
      icon to an inline symbol, line-limit the fingerprint block, and keep the timer, `cleanup()`,
      `formatFingerprint()`, `.defaultAction` and `.cancelAction` unchanged; verify the project builds.
- [x] 4.2 Replace the `.sheet` in `StatusPanel` with a `@ViewBuilder` `pairSection` rendered above
      `headerSection` when `incomingPairRequest != nil`; verify that with no request pending the panel's layout
      is byte-identical to task 2.1's result.
- [ ] 4.3 Make the drop closure return `false` when `currentLinkState != .connected` so a drop while disconnected
      is rejected by the system rather than silently queued; verify by dragging a file onto the panel with the
      phone disconnected and observing the drag snap back.

## 5. Mac — automated verification

- [x] 5.1 Run `xcodebuild -project mac/Bridge/Bridge.xcodeproj -scheme Bridge build` and read the output; no new
      warnings under Swift 6 strict concurrency, no force unwraps introduced.
- [x] 5.2 Run `xcodebuild -project mac/Bridge/Bridge.xcodeproj -scheme Bridge test` and read the output; the full
      suite including the new `needsAttention` tests passes. (84/84 green once the pre-existing
      `testSerializedMatchesVector` failure was fixed — see 8.1.)
- [x] 5.3 Run `openspec validate --change br-2-render-the-mac-panel-as-a-window` and read the output.

## 6. Android

- [x] 6.1 No Android source change in this change. Verified `git diff main...HEAD -- android/` is empty.
      Android's suite was still re-run because the packet encoder changed on the Mac side: 190/190 green via
      `./gradlew test --rerun-tasks`.

## 7. Shared — manual device test (MacBook Air M3 + Galaxy S25)

- [x] 7.1 With the phone paired and connected, open the panel and confirm each listed permission renders as one
      row — dot, name, status, action on a single line — and that granted permissions are absent.
- [ ] 7.2 Drag a file from Finder onto the open panel and confirm it arrives on the phone, and that the panel
      shows preparing → in-progress → sent.
- [ ] 7.3 Trigger a pair request from the phone against an unpaired Mac, open the panel, and confirm the
      confirmation is visible immediately with the device name and fingerprint, that Reject leaves both trust
      stores unchanged, and that a second request accepted inline pairs both sides.
- [x] 7.4 Confirm `Send File…` is visually distinct from the labels around it, and that the panel's width does not
      change across connected, disconnected and Bluetooth-off states.
- [x] 7.5 Re-run the accessibility enumeration that produced AUD-A's 22-item dump and confirm the panel no longer
      reports flattened disabled menu items.

## 8. Added during review (user-directed, outside the original plan)

- [x] 8.1 Emit packet keys as `id`, `type`, `body` on the Mac to match `protocol/test-vectors` and the Android
      encoder, which disagreed with the Mac's `.sortedKeys` output; verified by `testSerializedMatchesVector`
      passing on Mac and Android's 190 tests still green.
- [x] 8.2 Return a battery symbol that resolves while charging — SF Symbols has no partial-level `.bolt` variant,
      so the glyph was blank below 76%; verified by `PhoneBatteryStateTests` resolving every reachable symbol
      against `NSImage`, and by eye on the panel at 60% charging.
- [x] 8.3 Align the permission row as info-left, action-right; verified on the live panel.
- [x] 8.4 Remove the pair banner's keyboard shortcuts and key it on the request id; verified by build. The
      Escape/Return hazard is reasoned from the inline placement, not reproduced on device — see 7.3.
- [x] 8.5 Confirm the permission section is not stale under `.window`: four panel opens produced four
      `Permission refresh` log entries, so `onAppear` fires per open and the reviewed concern does not hold.
