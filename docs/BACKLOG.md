# Bridge — Backlog

The work queue. This file replaces a ticket tracker: `/work` reads it, and it is the only
place a work item is born. One row per item, one detail block per row.

## Rules

- **ID**: `BR-<n>`, next free number, never reused.
- **Status**: `idea` → `ready` → `in-progress` → `review` → `done`. Only `ready` items can start.
- **Branch**: `work/<id-lowercase>-<kebab-title>`, filled in when work starts.
- An item is `ready` only when its detail block has a one-line **Scope**, a **Why**, and
  **Done when** criteria that can be checked on real hardware.
- `/work` keeps Status and Branch current. Edit Scope/Why/Done-when by hand, any time.
- `done` rows stay for ~10 items, then move to `docs/ITERATIONS.md` and drop out of here.

## Queue

| ID | Title | Status | Branch |
|----|-------|--------|--------|
| BR-1 | Grey out the app list while mirroring is paused | done | `work/br-1-mute-greys-app-list` |
| BR-2 | Render the Mac panel as a window, not an NSMenu | done | `work/br-2-render-the-mac-panel-as-a-window` |
| BR-3 | Make the link state tell the truth | ready | |
| BR-4 | Transport security: decide, then close the gap | idea | |
| BR-5 | Pairing: six-digit confirmation and a device picker | idea | |
| BR-6 | Notification mirroring: dismissal, replies, app names | in-progress | `work/br-6-notification-mirroring` |
| BR-7 | Add a Disconnect control to both apps | ready | |
| BR-8 | File transfer: sanitise, cap, authenticate, confirm | ready | |
| BR-9 | Clipboard: stop the dead poll, stop the silent send | ready | |
| BR-10 | Android: permission rows, resume modal, denied-path recovery | ready | |
| BR-11 | Mac: Local Network status, accessibility, label stability | ready | |
| BR-12 | Protocol and process debt | ready | |

All of BR-2…BR-12 come from [AUDIT-2026-10-26.md](AUDIT-2026-10-26.md), which holds the
evidence, file:line references and reproduction for each. The audit's own ordering
rationale is in its "Suggested order" section; the short version is BR-2 → BR-3 →
BR-4+BR-5 together → the rest.

## Items

### BR-1 — Grey out the app list while mirroring is paused

**Scope**: when the master "Pause mirroring to the Mac" switch is on, dim the per-app list to
40% and disable its switches, since mute overrides every per-app choice.
**Why**: the per-app switches stay bright and tappable while paused, so the screen reads as if
those apps are still being mirrored. The work already exists, hardware-verified, on the stale
branch `worktree-fix+mute-greys-applist` (`2a9119a`) — but that commit also inverts the switch
polarity to ON = block, which contradicts the allow-list model and the copy shipped in
`0c31a23`. Port the greying only.
**Platforms**: android
**Done when**:
- [x] with the master switch ON, every app row is dimmed and its switch cannot be toggled
- [x] turning the master switch OFF restores full opacity and tappable switches, same screen,
      no reload
- [x] opening the screen while already paused shows the dimmed state immediately
- [x] per-app switch polarity is unchanged — ON still means "mirror this app"
**Notes**: `adapter.setMuted()` must not read `store.isMuted()` at adapter construction — the
store lazily touches `applicationContext` before the Context is attached and the activity
crashes. Default the field false and apply the real value in `loadApps()`. The polarity
inversion and the empty-hint rewrite from `2a9119a` are deliberately dropped.

### BR-2 — Render the Mac panel as a window, not an NSMenu

**Scope**: add `.menuBarExtraStyle(.window)` to the `MenuBarExtra` scene and re-lay out
`StatusPanel` for a real popover (fixed width, padding, fewer dividers, granted permissions
hidden).
**Why**: without it SwiftUI renders the panel as an `NSMenu`, which flattens every row into
separate disabled menu items — the permission dot, name and status land on three lines, all
non-Button text greys out, `.dropDestination` silently does nothing so the "drag a file onto
this menu" copy is false, and `.sheet` cannot present, which is why `PairPromptView` is
unreachable and pairing was made automatic.
**Platforms**: mac
**Done when**:
- [x] each permission renders as one row: dot, name, status, action
- [~] dragging a file onto the open panel sends it to the phone — **not achievable**; the
      panel is dismissed as soon as another app takes key focus and does not spring open
      during a drag, so the affordance and its copy were removed rather than faked
- [~] a pending pair confirmation is visible without reopening the panel — the banner is
      built and its rendering verified, but `PairingManager` auto-accepts while the trust
      store is empty, so it cannot be reached until BR-5 removes that
- [x] `Send File…` is visually distinct from the labels around it
**Notes**: `AUD-A`. Accessibility dump of the live app is in the audit. Unblocks BR-5's
confirmation UI and part of BR-11. Shipped 2026-10-26 — see `docs/ITERATIONS.md` for the two
criteria that could not be met as written and why. Also carried a Mac packet-serialisation
fix that properly belongs to BR-12, and a battery-glyph fix.

### BR-3 — Make the link state tell the truth

**Scope**: wire the existing `LinkHealth` into `StatusPanel` and `menuBarIcon`, make
`sendPacket` report real send failure, and gate the Mac's `.connected` on peer verification
the way Android already does.
**Why**: `LinkHealth` and its `guidance(for:)` copy are written and have zero callers, so a
killed phone service renders as "Out of range" for a phone on the desk. On a silent drop the
Mac shows "Connected" with a live battery reading for up to 120 s (90 s watchdog on a 30 s
tick), and `sendPacket` returns `true` whenever the channel object exists — so every reply in
that window reports success and `postDeliveryFailure()` never fires.
**Platforms**: mac
**Done when**:
- [ ] force-stopping the phone app shows "Not responding" plus the restart guidance, not "Out of range"
- [ ] carrying the phone out of BLE range shows "Out of range"
- [ ] a reply sent into a dead link posts the delivery-failure notification
- [ ] the Mac does not say "Connected" before the peer is verified
**Notes**: `AUD-B`. Do this early — it makes every later fix observable.

### BR-4 — Transport security: decide, then close the gap

**Scope**: first decide whether to implement the specced mutual TLS or amend the docs to
describe what ships; then, either way, drop packets by default until identity is known,
remove the fail-open catch around the trust check, and cap `readLine()` length.
**Why**: `TLSWrapper` and `createSSLContext()` both have zero callers, so the control channel
and file payloads are plaintext while README, PRD, CLAUDE.md and
`openspec/specs/security/certificate-pinning/spec.md` all claim mutual TLS. Separately the
trust guard in `L2capServer` is skipped entirely before an identity packet arrives, so any
packet type is processed from an unidentified peer — the spec says the opposite.
**Platforms**: both, protocol
**Done when**:
- [ ] a peer that has sent no identity packet cannot get any packet type processed
- [ ] an uninitialised trust store drops packets rather than passing them
- [ ] a line over the cap closes the connection
- [ ] docs and specs match the shipped transport, whichever way the decision lands
**Notes**: `AUD-C`. Status is `idea`, not `ready`, because the TLS-or-amend-docs call is
yours. Land with BR-5 as one change — they compound.

### BR-5 — Pairing: six-digit confirmation and a device picker

**Scope**: derive and display the PRD's six-digit code on both screens before anything pins,
remove both auto-accept paths, close the connection on a fingerprint that does not match the
pin, and show an unpaired Mac a list of discovered phones by name and RSSI.
**Why**: the code was never built on either side (`grep` for it returns nothing). Android
auto-accepts every request unconditionally; the Mac shows its sheet and then accepts anyway.
A changed fingerprint triggers a re-pair that the other end auto-accepts. The Mac connects to
the first advertiser it sees. Together with BR-4 this means anything in BLE range can pair
itself and inject packets with no human touching either screen.
**Platforms**: both, protocol
**Done when**:
- [ ] both screens show the same six digits before anything is pinned
- [ ] declining on either side leaves both trust stores unchanged
- [ ] a fingerprint that does not match the pin closes the connection and needs a deliberate re-pair
- [ ] an unpaired Mac lists discovered phones by name and RSSI and waits for a choice
**Notes**: `AUD-D`. Needs BR-2 first for a presentable confirmation UI. Status is `idea`
pending the BR-4 decision, since the two land together.

### BR-6 — Notification mirroring: dismissal, replies, app names

**Scope**: send `bridge.notification.request` from `onNotificationRemoved`; change
`getCachedAction` from `remove` to `get` and evict on removal instead; add `<queries>` to the
Android manifest; surface the enabled-app count on both home screens; give the filter screen
a loud empty state with a one-tap "Enable all".
**Why**: `BridgePacket.notificationRequest()` has zero callers on Android, so clearing a
notification on the phone leaves a ghost on the Mac forever. `getCachedAction` consumes the
cache entry, so a second reply to the same notification is silently dropped. Without
`<queries>`, `getApplicationLabel()` fails for most apps and the filter list shows raw package
names — observed live as `com.axis.mobile`, `com.bt.bms`, `com.dreamplug.androidapp`. And the
allow-list is deny-by-default and seeded only by apps that have already posted, so a fresh
install mirrors nothing and neither screen says so.
**Platforms**: both, android
**Done when**:
- [ ] clearing a notification on the phone clears it on the Mac
- [ ] two consecutive replies to one notification both arrive
- [ ] every row in the filter list shows an app name and icon
- [ ] both home screens state how many apps are mirroring
- [ ] with nothing allowed, the filter screen says so and offers one tap to enable every
      seen app; after that tap a notification from any of them reaches the Mac
**Notes**: `AUD-F`. The `<queries>` change is two manifest lines and makes the deny-by-default
problem fixable by the user, so it is worth pulling forward even if the rest waits.

Decisions taken 2026-10-26, after a live reproduction on SM-S931B
(`BridgeNotifListener: Filtered out com.android.shell (muted=false, allowed=false)` — the
allow-list was empty, which is why nothing had been mirroring):
- **Deny-by-default stays.** Nothing leaves the phone until the user says so. The fix is to
  make the empty state loud and give one tap to a working setup, not to flip the default.
  Allow-by-default was rejected: it would mirror OTP and banking notifications before the
  user chose anything, over a wire that is still plaintext until BR-4.
- **No migration for existing installs.** An upgrade does not seed `allowed` from `seen`;
  the same empty state and the same one tap apply, so the privacy promise reads the same
  for a new install and an upgraded one.

### BR-7 — Add a Disconnect control to both apps

**Scope**: add Disconnect to both apps (drop the socket, stop advertising, keep the pin, stay
down until the user reconnects), add Unpair to Android, wire the Mac's existing
`disconnect()`, and replace the dead "CONNECTED" toolbar item.
**Why**: `BLEManager.disconnect()` has zero callers and `stopSelf()` is never called, so the
only options are destroy the trust relationship or kill the process — the README's answer to
"unpair on Android" is *uninstall*. There is no way to say "not right now" while the phone
streams notifications and clipboard to whatever Mac is in range. The Android toolbar's only
action renders as "CONNECTED" next to a pill saying the same word, and does nothing.
**Platforms**: both
**Done when**:
- [ ] Disconnect on either side drops the link on both, and the pin survives
- [ ] Reconnect restores the link with no re-pairing
- [ ] Android can forget the Mac without uninstalling
- [ ] no control in either app is labelled with a status
**Notes**: `AUD-H`.

### BR-8 — File transfer: sanitise, cap, authenticate, confirm

**Scope**: reduce incoming filenames to a basename and reject separators; cap `payloadSize`;
restrict the payload port to the paired peer or a one-time token from the control channel;
announce an incoming file before writing it, with a Decline; surface receive failures.
**Why**: `FileReceiveController` passes the peer's filename unsanitised to
`appendingPathComponent`, so `../../.zshenv` escapes `~/Downloads`. Nothing bounds
`payloadSize`. `PayloadServer`'s own comment says it streams to *"the first connection that
arrives"* with no auth and no TLS, so on Tier 3 any LAN host that reaches the port inside the
window gets the file. Both sides write silently; receive failures on the Mac are logged only.
**Platforms**: both
**Done when**:
- [ ] a `../` filename lands inside Downloads with a flattened name, or is refused
- [ ] a payload declaring an absurd size is refused before any bytes are written
- [ ] a connection to the payload port from an unrelated LAN host gets nothing
- [ ] an incoming file is announced before it is written, with a way to decline
- [ ] a failed receive tells the user
**Notes**: `AUD-E`. The authentication criterion depends on BR-4/BR-5 making peer trust real;
the sanitise, cap and confirm criteria do not and can land first.

### BR-9 — Clipboard: stop the dead poll, stop the silent send

**Scope**: gate `ClipboardBridge.start()` on `hasBackgroundClipboardAccess`; make the
on-focus send explicit rather than automatic; cap clipboard payload per the 10 KB rule; make
the status card reflect state and move its strings to `strings.xml`.
**Why**: the 500 ms poll runs forever on the main thread while Android 16 guarantees it reads
nothing — `hasBackgroundClipboardAccess` is computed, logged and never used. Opening the app
fires `sendClipboardFromActivity()` from `onWindowFocusChanged` and ships the clipboard to the
Mac, announcing it afterwards with a toast reading "Copied." (nothing was copied; something
was sent). Clipboard ignores the PRD's 10 KB out-of-band rule, so a large clip blocks the BLE
control channel for minutes. The status card is hard-coded permanently orange.
**Platforms**: android
**Done when**:
- [ ] no clipboard polling occurs when background access is denied
- [ ] opening the app does not send the clipboard without the user asking
- [ ] a clip over the cap does not block the control channel
- [ ] the clipboard card changes with state and is amber only when something is wrong
**Notes**: `AUD-G`. The battery cost lands squarely on the PRD's "tool gets uninstalled" risk.

### BR-10 — Android: permission rows, resume modal, denied-path recovery

**Scope**: replace the monospace permission dump with three tappable rows (dot, name,
consequence, action), surface `PermissionItem.detail` for the adb grant, delete the
`onResume` link modal and move its copy inline, make the denied-permission path recoverable,
and drop `app_glyph`.
**Why**: permissions render as `joinToString("\n") { "${it.name}: ${it.status.label}" }` into
a monospace TextView — log output, not UI. `.detail` has zero references and for the adb grant
it holds the actual command the user must run. The resume modal repeats for the fourth time on
one screen what the pill, device line and toolbar action already say. Denying a runtime
permission overwrites the device-name line with a sentence and offers no retry anywhere in the
app. `app_glyph` is a 56 dp launcher icon shown to someone already inside the app.
**Platforms**: android
**Done when**:
- [ ] each permission row states its consequence and offers its own action
- [ ] the adb command is reachable from the UI
- [ ] opening the app with a broken link shows no modal
- [ ] a user who denies a permission can grant it later without leaving the app
**Notes**: `AUD-I`.

### BR-11 — Mac: Local Network status, accessibility, label stability

**Scope**: report a real Local Network status or delete the row; give the menu bar item an
explicit accessibility label; fix the label to a constant composition; show the device name
once; disambiguate the phone battery.
**Why**: `setLocalNetwork(_:)` has zero callers, so the row that gates Wi-Fi file transfer
reads "Not Requested" with a Request button forever — and a permanent yellow dot trains the
user to ignore the honest rows. The menu bar item's accessibility title is `"25%"` when
connected and resolved to a neighbouring item's `"Mobile Data"` when disconnected. The label
is a conditional `HStack` of up to five elements, so it changes width all day. The panel shows
`SM-S931B` and then `Paired with SM-S931B`.
**Platforms**: mac
**Done when**:
- [ ] the Local Network row reports a real status or is gone
- [ ] VoiceOver announces something meaningful for the menu bar item
- [ ] the menu bar item does not change width as state changes
- [ ] the device name appears once
**Notes**: `AUD-J`. There is no TCC query API for Local Network, so observing a failed
`NWBrowser`/`NWListener` is the only honest signal.

### BR-12 — Protocol and process debt

**Scope**: read `protocolVersion` and the capability arrays and surface a mismatch; complete
`protocol/constants.json` and have both sides read it; fix the corrupted UUID in the discovery
spec; delete `isListenerEnabled()`; record PRD goal 5's signal and DND as built or cut; add a
validation step that stops a spec requirement reaching `openspec/specs/` without matching code.
**Why**: capabilities and `protocolVersion` are sent in every identity packet and never read,
while distribution is `brew upgrade` plus a manual APK sideload — version skew is the normal
state and nothing detects it. `constants.json` omits the RFCOMM UUID both sides hardcode
separately. `isListenerEnabled()` passes `null` as the ContentResolver and has zero callers.
Signal and DND are neither built nor cut, so goal 5 silently reads as done. And
`openspec/specs/` currently contains a mutual-TLS requirement and a two-sided-confirmation
requirement that were never implemented — the same gap can reopen next iteration.
**Platforms**: both, protocol
**Done when**:
- [ ] a version- or capability-mismatched peer is detected and surfaced
- [ ] `constants.json` holds every shared constant and both sides read it
- [ ] the discovery spec's UUID matches `constants.json`
- [ ] goal 5 is either built or recorded as cut
- [ ] a spec requirement cannot reach `openspec/specs/` without matching code
**Notes**: `AUD-K`. The last criterion is a process fix, not a code fix — it is what keeps
BR-4 and BR-5 from recurring.

<!-- Template — copy for each new item:

### BR-<n> — <title>

**Scope**: one line, what changes.
**Why**: one line, the problem it solves.
**Platforms**: mac | android | both | protocol
**Done when**:
- [ ] criterion checkable on a MacBook Air M3 + Galaxy S25
- [ ] criterion
**Notes**: links to PRD sections, prior iterations, known constraints.

-->
