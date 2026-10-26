## Why

`MenuBarExtra` in [`BridgeApp.swift:20`](../../../mac/Bridge/Sources/Bridge/BridgeApp.swift#L20) carries no
`.menuBarExtraStyle(.window)`, so SwiftUI renders `StatusPanel` as a real `NSMenu`. An accessibility dump of the
live app (AUD-A in `docs/AUDIT-2026-10-26.md`) returned 22 flat menu items, 18 of them disabled: the permission
rows are shredded into three items each, every non-Button view reads as disabled grey text, `.dropDestination`
is inert while the panel tells the user to drag files onto it, and `.sheet` cannot present at all — which is why
`PairPromptView` is unreachable and pairing was made automatic.

The PRD's "Risks and what breaks" section names observability as the thing to build in Phase 1, not as polish:
*"the Mac menu bar must always show connection state, last packet time, and which permissions are currently held."*
Today that panel is unreadable, so the observability the PRD paid for is not being delivered.

## What Changes

- Add `.menuBarExtraStyle(.window)` to the `MenuBarExtra` scene so the panel renders as a popover rather than an
  `NSMenu`. This is the single root cause; everything below is the re-layout it enables.
- Give `StatusPanel` a fixed `.frame(width: 260)` and `.padding(12)`, and cut the divider between every section
  down to the two that separate genuinely different concerns.
- Render each permission as one row — dot, name, status, action — and **show only the rows that need attention**.
  When every permission is granted the Permissions section is absent entirely. **BREAKING** for the
  `ui/permission-panel` spec, whose "All permissions granted" scenario currently requires three green rows.
- Replace the unreachable `.sheet` presentation of `PairPromptView` with an inline confirmation banner at the top
  of the panel. A `MenuBarExtra(.window)` panel is a non-activating `NSPanel`; an attached sheet is unreliable on
  one, and an inline banner is visible the moment the panel opens without a second click.
- Give `Send File…` a `.borderedProminent` style so it reads as the panel's action rather than as another label.
- **Remove the drop target and its copy.** The audit assumed `.dropDestination` was inert only because of the
  `NSMenu` host. It is unreachable under the window presentation too: the panel is dismissed the moment another
  app takes key focus, so pressing the mouse on a file in Finder closes it before a drag can start, and dragging
  over the menu bar icon does not spring it open (checked twice on the MacBook Air M3). The line *"Drag a file
  onto this menu…"* was false before this change and would have stayed false after it, so it goes, along with
  the handler behind it. Accepting a drop would mean owning an `NSStatusItem` directly — see design.md D1 —
  which is its own piece of work, not this one.

Not in scope, and deliberately left to their own items: the link states this panel renders are still the ones
BR-3 calls dishonest, the Local Network row still reports a status nothing sets (BR-11), and the six-digit code
the banner should eventually show does not exist yet (BR-5). This change makes those three observable and
fixable; it does not fix them.

## Capabilities

### New Capabilities

None. This change re-renders surfaces that `ui/mac-status-panel` and `ui/permission-panel` already specify.

### Modified Capabilities

- `ui/mac-status-panel`: the panel is specified as a window-style popover rather than a dropdown menu; the
  pairing prompt is specified as an inline confirmation inside that panel rather than "a system dialog or panel";
  drag-and-drop onto the open panel becomes a stated requirement rather than unenforced copy.
- `ui/permission-panel`: the Mac panel lists only permissions that are not granted, and omits the section
  entirely when all are held — replacing the current "each permission shows a green Granted indicator" scenario.

## Impact

**Code** — Mac only, three files:
- `mac/Bridge/Sources/Bridge/BridgeApp.swift` — one scene modifier.
- `mac/Bridge/Sources/Bridge/UI/StatusPanel.swift` — frame, padding, dividers, permission filter, prominent
  action button, inline pair banner replacing `.sheet`, corrected drag copy.
- `mac/Bridge/Sources/Bridge/UI/PairPromptView.swift` — reshaped from a 340 pt sheet body to a 260 pt inline
  banner. Its 30-second auto-reject timer survives the move and becomes real for the first time, since the view
  is now on screen whenever the panel is open.

**Transport tiers** — none. No transport change; `bridge.pair` handling is untouched and the file-drop path
reuses the existing `FileTransferManager.sendFile(at:sharedNetworkAvailable:)` across the same tier ladder it
uses today.

**Serialization, added during review at the user's direction** — `BridgePacket.serialize()` emitted keys
alphabetically (`.sortedKeys`) while Android's encoder and `protocol/test-vectors` both use declaration order, so
the two platforms produced different bytes for the same packet and `testSerializedMatchesVector` failed on Mac
only. The Mac encoder now emits `id`, `type`, `body` in that order. The wire is unaffected — both sides already
parsed either ordering — and the vectors and the Android encoder are unchanged. This is BR-12's territory and was
taken here by an explicit decision, recorded so the scope of this change is not misread later.

**Permissions** — no new grant on either side. Mac TCC (Bluetooth, Notifications, Local Network) is unchanged;
this change only alters how those three are displayed. No Android permission is touched.

**APIs and dependencies** — none added. `.menuBarExtraStyle(.window)` is macOS 13+; the project's deployment
target is macOS 15.0.

**Verification** — the four acceptance criteria are visual and interactive, so they are checked on the MacBook
Air M3 against the Galaxy S25, not by unit test. `PermissionChecker` gains a pure, testable filter so the
"hidden when granted" rule has a unit test that does not need a window.
