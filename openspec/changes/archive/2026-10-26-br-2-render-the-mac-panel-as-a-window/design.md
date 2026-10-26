## Context

See `proposal.md` — Why. The design-relevant facts:

- `BridgeApp.body` declares a bare `MenuBarExtra { StatusPanel(...) } label: { ... }`. SwiftUI's default
  `menuBarExtraStyle` is `.menu`, which hosts the content view inside a real `NSMenu`.
- `StatusPanel` is already written as a `VStack(alignment: .leading)` of five sections with four dividers. Its
  structure is popover-shaped; only its container is wrong. Almost nothing in it needs rewriting.
- `PairPromptView` exists, is complete, and has one caller — a `.sheet` that cannot fire. Its 30-second timer is
  started in `onAppear`, which under the sheet never happens.
- `PermissionChecker.items` is built eagerly in `refresh()`, with the Notifications row filled in asynchronously
  from a `UNUserNotificationCenter` callback. Order is therefore Bluetooth, Local Network, then Notifications
  appended late.
- Swift 6 strict concurrency is on; `PermissionChecker`, `BLEManager` and `FileTransferManager` are all
  `@MainActor` already, so no new isolation boundaries appear in this change.

This is a Mac-only change. Android is untouched: no packet crosses the link differently, and the phone cannot
observe that the Mac's panel re-rendered. The "address both platforms" rule is satisfied by that statement —
there is no Android work and no Android degradation path, because the wire protocol is unchanged.

## Goals / Non-Goals

**Goals:**

- One scene modifier is the root fix. Every layout change in this design exists only because that modifier makes
  it possible — none of them are independent redesigns.
- Keep `PairPromptView`'s accept/reject/timeout logic byte-for-byte. Only its chrome changes.
- Leave the permission *model* alone. The filter is a view concern and a derived property, not a change to how
  permissions are checked or stored.

**Non-Goals:**

- Not fixing what the panel reports, only how it renders. The link states are still BR-3's lie, Local Network is
  still BR-11's dead row, and the fingerprint hex is still not BR-5's six-digit code. This change makes those
  three visible as themselves.
- Not adding a device picker, not touching `BLEManager`, not touching the pairing state machine.
- No new window, no `Settings` scene, no `NSPanel` subclass. The panel stays a `MenuBarExtra`.

## Decisions

### D1 — `.menuBarExtraStyle(.window)` on the scene, not a hand-rolled `NSPopover`

The fix is one modifier on the `MenuBarExtra` scene. Alternatives considered:

- *Hand-rolled `NSStatusItem` + `NSPopover`.* Gives total control and sidesteps every SwiftUI `MenuBarExtra`
  quirk, at the cost of owning status-item lifecycle, popover dismissal, and the `@StateObject` bridging that
  the scene currently gets free. Rejected: the bug is a missing modifier, not a missing framework.
- *Keep `.menu` and flatten the panel into genuine menu items.* Would mean giving up the file drop target and
  the inline pair confirmation outright — two of the four acceptance criteria are unreachable under `.menu`.
  Rejected on the criteria.

Consequence to design around: under `.window` the panel no longer dismisses itself when a button is tapped.
"Quit Bridge" terminates, so it does not matter; "Unpair", "Pair", "Request"/"Fix" and "Send File…" all leave the
panel open, which is the correct behaviour for each (the user sees the resulting state change in place). No
explicit dismissal is added.

### D2 — Inline pair banner, replacing the `.sheet`

`MenuBarExtra(.window)` hosts its content in a non-activating panel. A `.sheet` attached to that content is not
reliably presentable or interactive, and a failure there fails an acceptance criterion on device with no
fallback. The confirmation therefore becomes the panel's first section, rendered when
`bleManager.pairingManager.incomingPairRequest != nil`.

`PairPromptView` keeps its body structure, timer, `cleanup()` and `formatFingerprint()`. What changes: the
`.frame(width: 340)` and `.padding(24)` come off (the parent now sets width), the 40 pt icon shrinks to an
inline symbol, and the fingerprint block gets a line limit so a long hex string cannot stretch the panel. Accept
and Reject keep `.defaultAction` / `.cancelAction`.

A real benefit falls out: the 30-second countdown becomes honest. Under the sheet, `onAppear` never fired. Inline,
the view is on screen exactly when the panel is open — which is still *not* always, so the timer measures
"30 seconds of the user looking at it", not "30 seconds since the request". That is a smaller lie than the
current one but still a lie; it is called out under Risks and belongs to BR-5.

Flow:

```
phone                Mac BLE                PairingManager            StatusPanel
  │                      │                        │                        │
  │ bridge.pair(true) ──▶│                        │                        │
  │                      │── incomingPairRequest ▶│                        │
  │                      │                        │─ @Published set ──────▶│
  │                      │                        │                        │ banner renders
  │                      │                        │                        │ at top of panel
  │                      │                        │                        │
  │                      │                        │◀── acceptIncomingPair()┤ user taps Accept
  │◀─ bridge.pair(resp) ─┤◀───────────────────────┤                        │
  │                      │                        │─ request = nil ───────▶│ banner disappears,
  │                      │                        │                        │ panel stays open
```

Degradation: if no pair request is pending the section is simply absent — `@ViewBuilder` returns nothing, the
layout above it is unaffected. If the user never opens the panel, behaviour is exactly as today: the request sits
pending and nothing auto-accepts. That is a deliberate non-fix; BR-5 owns making a missed request safe.

### D3 — Permission filter as a derived property on `PermissionChecker`

Add a computed `var needsAttention: [PermissionItem] { items.filter { $0.status != .granted } }` and have
`StatusPanel` render that, with the whole section — heading included — wrapped in `if !needsAttention.isEmpty`.

Alternative: filter inline in the view with `ForEach(permissions.items.filter { ... })`. Rejected because the
"is the section shown at all" test then has nowhere to live except a UI test; as a property on an already-tested
`@MainActor` class it gets a unit test next to the existing `PermissionCheckerTests`.

Note the interaction with the async Notifications row: `items` is briefly missing Notifications right after
`refresh()`. Filtering does not make this worse — an absent row and a granted row look identical under the new
rule, and when the callback lands, a non-granted Notifications row appears. The section can therefore appear a
beat after the panel opens. Accepted: it is the existing behaviour of that row, not a regression, and BR-11 owns
the permission model's honesty.

### D4 — Fixed 260 pt frame, two dividers

`.frame(width: 260)` and `.padding(12)` on the root `VStack`. 260 pt fits `Paired with SM-S931B` and the
formatted fingerprint at `.caption` monospace without wrapping, and is the common width for a menu-bar popover.

Dividers drop from four to two: one above the transfer section and one above the action section. The status block
(device, pairing, battery) is one concern and needs no internal rule; the permission section, when present,
separates itself by being a titled group.

`Send File…` takes `.buttonStyle(.borderedProminent)`. Under `.menu` every button rendered identically; under
`.window` the prominent style is what separates the panel's action from its labels, which is the fourth
acceptance criterion.

### D5 — Drop target and its copy

`.dropDestination(for: URL.self)` already sits on the root `VStack` and its closure is correct. Under `.window`
it starts working with no code change. The copy changes from *"Drag a file onto this menu to send it to your
phone."* to name the panel, and only renders when a send is possible.

Error handling is unchanged and already present: `fileTransfer.sendFile` drives `FileTransferManager.state`, and
`transferSection` already renders `.preparing`, `.inProgress`, `.slowFallbackWarning`, `.completed` and
`.failed`. A drop while disconnected takes the same path as the disabled Send button — the design keeps the drop
closure returning `false` when `currentLinkState != .connected`, so the system shows a rejected drag rather than
silently queuing a file that cannot leave.

## Risks / Trade-offs

- **`.sheet` was the spec'd mechanism; inline is a spec change.** → `ui/mac-status-panel`'s "Pairing prompt UI"
  requirement is carried as MODIFIED in this change's delta, so `openspec/specs/` and the code agree after
  archive. This is precisely the drift BR-12's last criterion exists to stop.
- **The 30-second auto-reject is still not wall-clock.** → Called out here and left to BR-5, which replaces the
  fingerprint confirmation with the six-digit code flow and owns the timeout's correctness. Not silently fixed
  here, because a correct timer needs to live in `PairingManager`, not in a view.
- **Hiding granted permissions removes the only positive confirmation.** → Accepted deliberately (see spec
  rationale). The user's check becomes "is there a section?" rather than "are three dots green". The menu bar
  badge for denied permissions is unchanged and remains the at-a-glance signal.
- **A permanently non-granted Local Network row means the section is effectively always visible today.** → True
  until BR-11 lands, and it is honest: `setLocalNetwork(_:)` has no callers, so the status genuinely is unknown.
  This change makes that row the *only* thing in the section, which is a much louder pointer at the bug than
  burying it between two green rows.
- **Fixed 260 pt may clip a long device name.** → Device names come from the phone and are short in practice
  (`SM-S931B`). Line limit plus truncation on the name row rather than a growing panel; width stability is a
  stated requirement.

## Migration Plan

None required. No persisted state, no protocol version, no stored format changes. The change is a UI re-render
within one app; rollback is `git revert` of the branch and a rebuild. No coordinated Android release — an old APK
and a new Mac build interoperate exactly as before, because no packet changed.
