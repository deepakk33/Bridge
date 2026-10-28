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
| BR-3 | Make the link state tell the truth | done | `work/br-3-make-the-link-state-tell-the-truth` |
| BR-4 | Transport security: decide, then close the gap | idea | |
| BR-5 | Pairing: six-digit confirmation and a device picker | idea | |
| BR-6 | Notification mirroring: dismissal, replies, app names | done | `work/br-6-notification-mirroring` |
| BR-7 | Add a Disconnect control to both apps | ready | |
| BR-8 | File transfer: sanitise, cap, authenticate, confirm | ready | |
| BR-9 | Clipboard: stop the dead poll, stop the silent send | done | `work/br-9-clipboard` |
| BR-10 | Android: permission rows, resume modal, denied-path recovery | done | `work/br-10-permission-rows-and-denied-path-recovery` |
| BR-11 | Mac: Local Network status, accessibility, label stability | ready | |
| BR-12 | Protocol and process debt | ready | |
| BR-13 | Mac: notice a scan that never returns anything | done | `work/br-13-notice-a-scan-that-never-returns` |
| BR-14 | Mac: refuse to run twice | ready | |
| BR-15 | Clipboard: the ADB grant path is dead, decide what replaces it | done | `work/br-15-adb-grant` |
| BR-16 | Clipboard: decide whether reconnect should push the phone's clip | ready | |
| BR-17 | Media control: drive the phone's player from the Mac | ready | |

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
- [x] clearing a notification on the phone clears it on the Mac
- [~] two consecutive replies to one notification both arrive — **untested on hardware**; needs a
      live reply-capable conversation, which the shell cannot produce. The cache defect behind it
      is fixed and unit-tested, and the cancel-after-reply that would have defeated the fix is gone
- [x] every row in the filter list shows an app name and icon
- [x] both home screens state how many apps are mirroring
- [x] with nothing allowed, the filter screen says so and offers one tap to enable every
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
- [x] no clipboard polling occurs when background access is denied
- [x] opening the app does not send the clipboard without the user asking
- [x] a clip over the cap does not block the control channel
- [x] the clipboard card changes with state and is amber only when something is wrong
**Notes**: `AUD-G`. All four criteria verified on SM-S931B / Android 16 on 2026-10-28; see
`docs/ITERATIONS.md`. The automatic phone-to-Mac path is permanently unavailable (the grant is
role-managed) — filed as BR-15. Two Mac instances found running during the test — filed as BR-14.
The battery cost lands squarely on the PRD's "tool gets uninstalled" risk.

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
- [x] each permission row states its consequence and offers its own action
- [~] the adb command is reachable from the UI — **dropped, not implemented**. BR-15 deleted the
      ADB-grant row, `scripts/adb-grant-clipboard.sh` and the `clipboard/adb-grant` capability
      because Android 16 refuses the grant by role. There is no command left to surface, and
      `ui/permission-panel` now forbids a row that shows one.
- [x] opening the app with a broken link shows no modal
- [x] a user who denies a permission can grant it later without leaving the app
**Notes**: `AUD-I`. All three live criteria verified on SM-S931B / Android 16 on 2026-10-28; see
`docs/ITERATIONS.md`. The runtime permissions were added to the list beyond the Scope line, since
the fourth criterion is unreachable without them.

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

### BR-13 — Mac: notice a scan that never returns anything

**Scope**: detect that `scanForPeripherals` has produced no `didDiscover` for a bounded period
while the link is down, restart the scan, and fall back to
`retrieveConnectedPeripherals(withServices:)` for a peripheral the system already holds.
**Why**: `BLEManager` cannot tell "I asked Core Bluetooth to scan and nothing arrived" from
"nothing is there". Observed live on 2026-10-27: a second app instance quitting sent `stop scan`
to `bluetoothd` with `updateScanParams:YES` seven seconds after the live instance started its own
scan; the live app kept believing it was scanning while the daemon was not scanning for it, and
sat on "Out of range" indefinitely with no retry. Recovery needed a restart or a Bluetooth toggle
— and the phone was 30 cm away, advertising connectably the whole time. `BLEManager` also never
calls `retrieveConnectedPeripherals`, which is how Core Bluetooth expects an app to find a
peripheral the system is already connected to, so a relaunch while the OS holds the link finds
nothing by design.
**Platforms**: mac
**Done when**:
- [x] a scan that returns nothing for a bounded period is restarted, and the restart is logged
- [x] the app reconnects after its scan is dropped externally, with no restart and no Bluetooth toggle
- [~] a peripheral already connected at the OS level is found without waiting for an advertisement
      — **untested**. macOS released the peripheral on every app exit tried, including SIGKILL, so
      `retrieveConnectedPeripherals` returned empty on every launch and the non-empty branch has
      never executed. The call is made on power-on and before each scan restart as specced.
- [x] the panel distinguishes "looking" from "looked and found nothing for a long time"
**Notes**: found while device-testing BR-3; see `docs/ITERATIONS.md` under BR-3 for the daemon
log evidence. Not a BR-3 regression — nothing in that change touches scanning. Two app instances
is not a state the product reaches in normal use, but the missing scan watchdog is reachable any
time macOS drops a scan.

### BR-14 — Mac: refuse to run twice

**Scope**: make the Mac app single-instance — a second launch activates the running instance
and exits instead of starting a second Core Bluetooth central.
**Why**: two copies were running on 2026-10-28 (a `/Applications/Bridge.app` build and an
Xcode `DerivedData/.../Debug/Bridge.app` build). This is the precondition for BR-13's observed
failure: the second instance quitting sends `stop scan` with `updateScanParams:YES` to
`bluetoothd`, which stops scanning for the *surviving* instance, and it then sits on
"Out of range" forever. Reproduced during BR-9's device test — killing the stray instance left
the survivor unable to see a phone 30 cm away until the app was restarted. Two instances is
easy to reach: build from Xcode once while the installed copy is running.
**Platforms**: mac
**Done when**:
- [ ] launching a second copy brings the running one to the front and exits, leaving one process
- [ ] this holds when the two copies are different builds from different paths
- [ ] the surviving instance never loses its scan to another instance quitting
- [ ] the guard does not prevent a relaunch after a genuine quit or crash
**Notes**: found while device-testing BR-9. BR-13 makes the app *recover* from a dropped scan;
this stops the commonest cause. Land BR-13 first — a single-instance guard is not a substitute
for a scan watchdog, since macOS can drop a scan for its own reasons.

### BR-15 — Clipboard: the ADB grant path is dead, decide what replaces it

**Scope**: decide what to do with `openspec/specs/clipboard/adb-grant/`,
`scripts/adb-grant-clipboard.sh`, and `PermissionStatusChecker`'s "ADB Clipboard" row, all of
which promise a grant Android 16 refuses — then make code, script, spec and README agree.
**Why**: verified on SM-S931B / Android 16 on 2026-10-28:
`pm grant com.bridge.app android.permission.READ_CLIPBOARD_IN_BACKGROUND` fails with
`SecurityException: Permission ... is managed by role`. The permission is also absent from the
manifest, so `checkSelfPermission` could never have returned granted either. That makes an
entire spec capability, a committed shell script, and a permission row describe something that
cannot happen — and `PermissionItem.detail` for that row holds the failing command verbatim,
which BR-10 is about to surface in the UI.
**Platforms**: android
**Done when**:
- [x] no shipped script, spec or UI string tells the user to run a command the OS refuses
- [x] the "ADB Clipboard" permission row is either honest about being unobtainable or gone
- [x] README and `clipboard/adb-grant` match what Android 16 actually permits
- [x] BR-10 does not surface a `.detail` that throws
**Notes**: found while device-testing BR-9, which left the gate correct (no watcher runs, so no
battery is burned) but could not fix the surrounding documentation without expanding its scope.
This is also an instance of BR-12's last criterion — a spec requirement with no working code
behind it.

Re-verified on SM-S931B / Android 16 (API 36) on 2026-10-28, and the item's premise was
understated — the script does not fail, it reports a false success:
- `pm grant com.bridge.app android.permission.READ_CLIPBOARD_IN_BACKGROUND` →
  `SecurityException: Permission ... is managed by role`, as BR-9 found.
- But the clipboard half of `scripts/adb-grant-clipboard.sh` does not use `pm grant` at all —
  it runs `appops set com.bridge.app READ_CLIPBOARD allow`, which **succeeds**.
- `READ_CLIPBOARD` has **default mode `allow` for every app** (confirmed against untouched
  packages and after `appops reset com.bridge.app`). The script sets what is already set, then
  prints *"Success! … Phone-to-Mac clipboard sync is now active."*; `--check` prints
  *"Full sync active."* on any install. The appop is not the gate — Android's focused-window
  check is, and no appop or `pm grant` moves it for a sideloaded app.
- `READ_CLIPBOARD_IN_BACKGROUND` is absent from the manifest, so `checkSelfPermission` could
  never return granted regardless.

Decisions taken 2026-10-28:
- **Delete all three surfaces.** `scripts/adb-grant-clipboard.sh` and the
  `openspec/specs/clipboard/adb-grant/` capability are deleted, and the "ADB Clipboard"
  permission row is dropped, leaving two rows that can both actually reach Granted. A row
  permanently `LIMITED` with no action is the never-clearing warning BR-11 and BR-9 both
  name as training the user to ignore the honest ones.
- **The knowledge moves, it is not lost.** `clipboard/sync` absorbs one requirement stating
  that background phone-to-Mac is unobtainable on Android 16 and that the quick-settings tile
  and the in-app send are the two triggers — so no capability survives promising a script.
- **`docs/PRD.md` and `.claude/CLAUDE.md` are corrected in this item**, beyond the Scope line
  as written: the PRD is the document the others defer to, and "Done when" forbids a shipped
  doc that tells the user to run a command the OS refuses.

### BR-16 — Clipboard: decide whether reconnect should push the phone's clip

**Scope**: decide whether `makeConnectPacket()` should keep sending the phone's current clip
on every connection, or be gated the way BR-9 gated the other phone-to-Mac paths — then make
`clipboard/sync` say so without an exception clause.
**Why**: BR-9 removed the send-on-focus because focus is not consent, but
`BridgeService.kt:259` still sends the phone's clipboard in a `bridge.clipboard.connect`
packet on *every* reconnect — a Mac app restart, a Bluetooth blip, leaving and re-entering
range. Observed repeatedly during BR-9's device test. That is the same silent send BR-9 set
out to stop, arriving through a different door, and it means BR-9's own
"user-initiated or grant-backed" requirement needed an exception clause to stay true.
The counter-argument is real: the reconnect exchange exists so the two clipboards converge
after a gap, and its timestamp comparison is what prevents ping-pong overwrites — gating it
may just move the staleness problem.
**Platforms**: android, protocol
**Done when**:
- [ ] a reconnect either does not push the phone's clip, or does so under a rule the user controls
- [ ] clipboard convergence after a gap still works, or the degradation is recorded
- [ ] `clipboard/sync` states the rule without an exception clause pointing at this item
**Notes**: found while device-testing BR-9. Pre-existing since iteration 4, not a BR-9
regression. Related to BR-4: the exposure only matters while the channel is plaintext.

### BR-17 — Media control: drive the phone's player from the Mac

**Scope**: add `bridge.media` and `bridge.media.request`, read the phone's active media session
through the `NotificationListenerService` Bridge already runs, and put the current track plus
previous / play-pause / next in the Mac panel.
**Why**: the phone is the music player and it sits a metre away with its screen off. Every pair
of earbuds on the desk can already pause it — that is AVRCP riding the audio link. Bridge holds
a resident BLE link to the same phone and the one permission the equivalent Android API needs
(`MediaSessionManager.getActiveSessions` requires an **enabled notification listener** or
`MEDIA_CONTENT_CONTROL`), which BR-6 and BR-10 already ship and surface as a permission row. So
the capability is a packet type and a panel row away, not a new grant, a new transport or a new
service. Today pausing means picking the phone up.
**Platforms**: both, protocol
**Done when**:
- [ ] with music playing on the phone (Spotify and YouTube both checked), the Mac panel shows
      title and artist, and a track change on the phone updates it without the Mac asking
- [ ] play/pause from the Mac panel actually pauses and resumes the phone's audio
- [ ] next and previous skip tracks
- [ ] pausing *on the phone* flips the Mac's button state — the panel is not write-only
- [ ] a control the player reports it cannot do is disabled, not dead: with `canGoNext` false the
      next button is greyed, and nothing is sent
- [ ] with nothing playing, the panel shows no stale track — the media row is absent, not a frozen
      last-known title
- [ ] with the notification listener disabled, the row says what is missing and opens the same
      settings screen BR-10's permission row opens; nothing crashes and no session is read
- [ ] no media packet is sent while no session exists — idle costs nothing, verified by log over
      five minutes with no player running
- [ ] killing the player app mid-playback clears the row rather than leaving a controllable ghost

**Notes**:

*Research, 2026-10-28. Nothing below is implemented yet; this is the evidence that the item is
buildable and where its edges are.*

**The Android side is a solved problem and the permission is already ours.**
`MediaSessionManager.getActiveSessions(ComponentName)` returns a `MediaController` per active
session, in priority order with index 0 the one the system routes media keys to — the same
session the earbuds drive. Access requires `MEDIA_CONTENT_CONTROL` (a privileged permission a
sideloaded app will not get, cf. BR-15's `READ_CLIPBOARD_IN_BACKGROUND`) **or** an enabled
notification listener whose `ComponentName` is passed to the call. Bridge has the second:
`BridgeNotificationListener` (`android/Bridge/app/src/main/java/com/bridge/notifications/BridgeNotificationListener.kt`),
already enabled by the user and already status-checked by `PermissionStatusChecker`. The call
does not have to be made from inside the listener, only to name it — so this can live in its own
`com.bridge.media` package and keep the listener file untouched.
A `MediaController` gives: `metadata` (title, artist, album, duration, album-art bitmap),
`playbackState` (state, position, and an `actions` bitmask saying which controls the player
actually supports), `playbackInfo` (volume), and `transportControls`
(`play`/`pause`/`stop`/`skipToNext`/`skipToPrevious`/`seekTo`). Push, not poll: registering a
`MediaController.Callback` gives `onPlaybackStateChanged` / `onMetadataChanged`, and
`addOnActiveSessionsChangedListener` covers players appearing and disappearing. That is what the
"idle costs nothing" criterion is built on.
Reference implementation, read in full: KDE Connect's `MprisReceiverPlugin.kt` and
`MprisReceiverPlayer.kt` (`KDE/kdeconnect-android`, `src/main/java/org/kde/kdeconnect/plugins/mprisreceiver/`).
It gates on exactly `NotificationReceiver.hasReadNotificationsPermission(context)` and its
permission dialog opens `android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS` — the same
screen BR-10's row already opens.

**Wire format.** KDE Connect's `kdeconnect.mpris` / `kdeconnect.mpris.request` pair is the
format to mirror, under Bridge's own `bridge.` prefix, field names kept identical so the two stay
readable against each other: status carries `player`, `title`, `artist`, `album`, `isPlaying`,
`pos`, `length`, `volume`, `canPlay`, `canPause`, `canGoNext`, `canGoPrevious`, `canSeek`,
`albumArtUrl`, and the player list carries `playerList`. Requests carry `player`, `action`
(`Play` | `Pause` | `PlayPause` | `Stop` | `Next` | `Previous`), `requestPlayerList`,
`requestNowPlaying`, `setVolume`, `SetPosition`, `Seek`. Two traps in that spec: the capital `S`
on `Seek`/`SetPosition` is load-bearing, and the spec states `Seek` in **microseconds** while
`pos` and `SetPosition` are **milliseconds** — neither matters if seek stays out of v1.

**Deliberately out of v1, each for a reason:**
- *Album art.* It is a JPEG; the control channel is BLE L2CAP and the convention caps inline
  payloads at 10 KB, so art means the out-of-band path and a cache keyed by art hash. A title and
  an artist are what the feature is for.
- *Seek and a position bar.* A moving progress bar means either a timer on the phone or a packet
  per second. `pos` can ride along in the status packet for free and be ignored by the Mac.
- *Volume.* `MediaController.setVolumeTo()` only does the obvious thing for sessions with a
  *remote* playback type; for local playback it is routed at the OS's discretion and is reported
  to silently do nothing on some devices. Needs its own hardware check on the S25, not a
  criterion in this item.
- *Mac media keys (F7/F8/F9).* Capturing them needs a `CGEventTap`, which needs an Accessibility
  or Input Monitoring TCC grant — a fourth grant, outside the three the PRD lists. Panel buttons
  first; the keys are their own item if the buttons prove useful.
- *A player picker.* With two players alive, drive index 0 — the session the OS itself routes to.
  That is the earbud behaviour the item is modelled on. Send `playerList` anyway so a picker is a
  UI change later, not a protocol change.

**The reverse direction (control the Mac's music from the phone) is a separate, harder item.**
macOS 15.4 added entitlement checks in `mediaremoted`, so `MRMediaRemoteGetNowPlayingInfo` now
fails for unentitled third-party apps — the symbols still resolve, the daemon refuses. The known
workarounds route the call through an already-entitled system binary (the `mediaremote-adapter`
approach) or fall back to AppleScript against Music/Spotify by name. All of it is private API on
a no-Developer-Program build. Do not fold it into this item.

**Relations.** Depends on nothing. Shares BR-10's permission row and its denied-path recovery, so
the "listener disabled" criterion is that work reused, not rebuilt. Touches BR-4: media metadata
is a log of what the user listens to, travelling in plaintext until BR-4 closes that — worth one
line in the item's proposal, not a blocker. The new packet types must be added to the allow-lists
at `android/Bridge/app/src/main/java/com/bridge/ble/L2capServer.kt:253` and
`mac/Bridge/Sources/Bridge/BLE/BLEManager.swift:321`, and to the trust gate at
`L2capServer.kt:172` — BR-12 territory, which is why they are named here rather than discovered
during implementation.

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
