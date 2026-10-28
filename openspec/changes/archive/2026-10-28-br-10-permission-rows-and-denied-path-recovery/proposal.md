## Why

The Android permission section renders as
`joinToString("\n") { "${it.name}: ${it.status.label}" }` into a single monospace `TextView` —
log output, not UI. It names two grants but offers one button, states no consequence for either,
and `PermissionItem.detail` has zero readers although for one row it holds the only text that
explains what the grant is for.

Denying a runtime permission at first launch overwrites the device-name line with "Permissions
required to run Bridge" and offers no retry anywhere in the app, so the only route back is
Android's app-info screen — which nothing in Bridge points at. The status card, whose job is to
describe the link, is being used as an error surface for something else.

On top of that, `onResume` throws an `AlertDialog` that repeats, for the fourth time on one
screen, what the state pill, the device line and the toolbar action already say — and repeats it
on every single resume. `app_glyph` is a 56 dp launcher icon shown to someone already inside the
app.

This is the PRD's "failure mode that matters most" (`docs/PRD.md`, *Risks and what breaks*): a
permission silently missing presents as nothing happening, and the screen that exists to prevent
that is unreadable.

Transport tiers: **none**. This change is UI and permission state only; it reads no transport and
sends no packet.

## What Changes

- Replace the monospace permission dump with one view per permission: status dot, name, a
  one-line consequence of not having it, and that row's own action button.
- Give `PermissionItem.detail` its first reader as the row's consequence line.
- Add the runtime permissions (`BLUETOOTH_ADVERTISE` / `BLUETOOTH_CONNECT`, and
  `POST_NOTIFICATIONS` on API 33+) to the permission list as rows, so a denial is visible and
  retryable on the screen the user already opens.
- Make the denied-runtime-permission path recoverable: the row's action re-requests, and once
  Android will no longer show the prompt it opens Bridge's app-settings screen instead.
- Stop reporting permission problems by overwriting the status card's device-name line.
- Delete the `onResume` link modal (`maybePromptLinkIssue`, `syncLinkPrompt`, `applyPromptText`);
  its copy moves inline onto the status card, dismissible, from the existing
  `LinkState.promptBody` / `actionLabel`.
- Delete `app_glyph` from the layout and from `MainActivity`.
- **Dropped, not implemented:** BR-10's criterion *"the adb command is reachable from the UI"*.
  BR-15 deleted the ADB-grant row, `scripts/adb-grant-clipboard.sh` and the
  `clipboard/adb-grant` capability, because Android 16 refuses that grant by role. There is no
  adb command left to surface, and `ui/permission-panel` now forbids a row that shows one.
  Recorded here rather than faked.

**No BREAKING changes.** No protocol change, no packet-type change. One new persisted value, a
`has_asked_<permission>` boolean, absent on existing installs and safe when absent.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `ui/permission-panel`: the Android permission list gains per-row requirements — a consequence
  line and a per-row action — the runtime permissions join the list, and a denied runtime
  permission must be recoverable from inside the app.
- `ui/android-shell`: the main screen SHALL NOT present a modal on resume; link guidance is
  inline. The status card SHALL NOT be used to report permission errors, and SHALL NOT carry a
  launcher glyph.

## Impact

**Permissions.** No new permission. Android's existing set is unchanged —
`BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`, `POST_NOTIFICATIONS` on API 33+, Notification Access
via `NotificationListenerService`, and the battery-optimisation exclusion. This change makes
their state visible and their denial recoverable. Mac permissions are untouched.

**Android code.** `app/MainActivity.kt` (row rendering, runtime-permission recovery, modal
removal, `app_glyph`), `permissions/PermissionStatusChecker.kt` (`PermissionItem`,
`PermissionRows`, a new `PermissionAction` and `RuntimeRecovery`), `app/LinkState.kt`
(`promptTitle` loses its last caller), `res/layout/activity_main.xml`, a new
`res/layout/view_permission_row.xml`, `res/values/strings.xml`, and the unit tests under
`test/java/com/bridge/permissions/` and `test/java/com/bridge/app/`.

**Not touched.** The Mac entirely. Protocol, `protocol/` vectors, file transfer, clipboard
behaviour, notification mirroring, pairing and trust store.

**Out of scope, noted.** `openspec/config.yaml`'s `context` block still reads "clipboard blocked
in background (ADB grant workaround)", which BR-15 disproved. It is prompt context, not a shipped
doc, so it falls outside this item's "Done when" — flagged for BR-12.
