## 1. Mac — the root fix

- [ ] 1.1 Add `.menuBarExtraStyle(.window)` to the `MenuBarExtra` scene in `BridgeApp.swift`, and verify with
      `xcodebuild -project mac/Bridge/Bridge.xcodeproj -scheme Bridge build` that it compiles, then by launching
      the app and confirming the panel opens as a popover rather than a menu (the dividers now draw as rules, not
      menu separators).

## 2. Mac — panel layout

- [ ] 2.1 Apply `.frame(width: 260)` and `.padding(12)` to `StatusPanel`'s root `VStack` and cut the four
      dividers to two (above `transferSection`, above `actionSection`); verify by opening the panel in connected
      and disconnected states and confirming the width does not change between them.
- [ ] 2.2 Give `Send File…` `.buttonStyle(.borderedProminent)` and change the drag copy from "onto this menu" to
      name the panel, rendering it only when a send is possible; verify visually that the button is distinguishable
      from the labels around it and that the copy matches what accepts a drop.
- [ ] 2.3 Add a line limit with truncation to the device-name row so a long name cannot stretch the panel; verify
      by temporarily rendering a 40-character name in an Xcode preview and confirming the frame holds at 260 pt.

## 3. Mac — permission rows

- [ ] 3.1 Add `var needsAttention: [PermissionItem]` to `PermissionChecker`, filtering `items` to
      `status != .granted`; verify the project still builds.
- [ ] 3.2 Add a unit test in `PermissionCheckerTests` covering `needsAttention`: all-granted returns empty, a
      mixed set returns only the non-granted items, and a revoked item reappears after the status changes; verify
      with `xcodebuild -project mac/Bridge/Bridge.xcodeproj -scheme Bridge test`.
- [ ] 3.3 Render `permissionSection` from `needsAttention` and wrap the whole section — heading included — in
      `if !permissions.needsAttention.isEmpty`; verify in the running app that each listed permission is one row
      (dot, name, status, action) and that the section is absent when nothing needs attention.

## 4. Mac — inline pair confirmation

- [ ] 4.1 Reshape `PairPromptView` for inline use: drop `.frame(width: 340)` and `.padding(24)`, shrink the 40 pt
      icon to an inline symbol, line-limit the fingerprint block, and keep the timer, `cleanup()`,
      `formatFingerprint()`, `.defaultAction` and `.cancelAction` unchanged; verify the project builds.
- [ ] 4.2 Replace the `.sheet` in `StatusPanel` with a `@ViewBuilder` `pairSection` rendered above
      `headerSection` when `incomingPairRequest != nil`; verify that with no request pending the panel's layout
      is byte-identical to task 2.1's result.
- [ ] 4.3 Make the drop closure return `false` when `currentLinkState != .connected` so a drop while disconnected
      is rejected by the system rather than silently queued; verify by dragging a file onto the panel with the
      phone disconnected and observing the drag snap back.

## 5. Mac — automated verification

- [ ] 5.1 Run `xcodebuild -project mac/Bridge/Bridge.xcodeproj -scheme Bridge build` and read the output; no new
      warnings under Swift 6 strict concurrency, no force unwraps introduced.
- [ ] 5.2 Run `xcodebuild -project mac/Bridge/Bridge.xcodeproj -scheme Bridge test` and read the output; the full
      suite including the new `needsAttention` tests passes.
- [ ] 5.3 Run `openspec validate --change br-2-render-the-mac-panel-as-a-window` and read the output.

## 6. Android

- [ ] 6.1 No Android work in this change. Verify that claim by confirming `git diff main...HEAD -- android/` is
      empty before the review gate.

## 7. Shared — manual device test (MacBook Air M3 + Galaxy S25)

- [ ] 7.1 With the phone paired and connected, open the panel and confirm each listed permission renders as one
      row — dot, name, status, action on a single line — and that granted permissions are absent.
- [ ] 7.2 Drag a file from Finder onto the open panel and confirm it arrives on the phone, and that the panel
      shows preparing → in-progress → sent.
- [ ] 7.3 Trigger a pair request from the phone against an unpaired Mac, open the panel, and confirm the
      confirmation is visible immediately with the device name and fingerprint, that Reject leaves both trust
      stores unchanged, and that a second request accepted inline pairs both sides.
- [ ] 7.4 Confirm `Send File…` is visually distinct from the labels around it, and that the panel's width does not
      change across connected, disconnected and Bluetooth-off states.
- [ ] 7.5 Re-run the accessibility enumeration that produced AUD-A's 22-item dump and confirm the panel no longer
      reports flattened disabled menu items.
