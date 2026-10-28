## Why

`scripts/adb-grant-clipboard.sh`, the `clipboard/adb-grant` spec capability and the
"ADB Clipboard" permission row all describe a grant Android 16 refuses. Re-verified on
SM-S931B / API 36 on 2026-10-28, the problem is worse than BR-15 was filed as: the script
does not fail, it **reports a false success**.

- `pm grant com.bridge.app android.permission.READ_CLIPBOARD_IN_BACKGROUND` →
  `SecurityException: Permission ... is managed by role`.
- The script's clipboard half does not use `pm grant`. It runs
  `appops set com.bridge.app READ_CLIPBOARD allow`, which **succeeds** — because
  `READ_CLIPBOARD` already has default mode `allow` for every app. Confirmed against
  untouched packages and after `appops reset com.bridge.app`. The appop is not the gate;
  Android's focused-window rule is, and no appop or `pm grant` moves it for a sideloaded
  build. The script sets what is already set, then prints *"Success! … Phone-to-Mac
  clipboard sync is now active."*, and `--check` prints *"Full sync active."* on any install.
- `READ_CLIPBOARD_IN_BACKGROUND` is absent from the Android manifest, so
  `checkSelfPermission` could never have returned granted either.

So the repo ships a green light for a thing that never happens, a spec capability with no
working code behind it, and a permission row permanently `LIMITED` whose
`PermissionItem.detail` holds the failing command verbatim — which BR-10 is about to render
in the UI. PRD "Android constraints" still names option 1 (the ADB grant) as the chosen way
out; it is option 3 that shipped.

## What Changes

- **BREAKING (repo surface)**: delete `scripts/adb-grant-clipboard.sh`. Nothing in the app
  invokes it; it is a user-facing instruction that cannot work.
- **BREAKING (spec)**: delete the `clipboard/adb-grant` capability. Every one of its
  remaining requirements describes a grant that cannot be obtained. The one statement worth
  keeping — background phone-to-Mac is unavailable, the quick-settings tile and the in-app
  send are the two triggers — moves into `clipboard/sync`, so no capability survives
  promising a script.
- Drop the "ADB Clipboard" row from `PermissionStatusChecker.all()`, along with
  `ADB_GRANT_COMMAND`, `READ_CLIPBOARD_IN_BACKGROUND` and `adbClipboardGrant()`. Two rows
  remain, and both can actually reach Granted. A row stuck at `LIMITED` with no action is
  the never-clearing warning BR-11 and BR-9 both name as training the user to ignore the
  honest ones.
- Re-gate `ClipboardBridge.start()` on the platform fact rather than on a permission the
  manifest does not request: the background watcher is unavailable on this build, full stop.
  BR-9's observable behaviour (no recurring read, no battery burned) is unchanged.
- Correct `docs/PRD.md` "Android constraints" to record that option 1 was tried and is
  closed on Android 16, and that option 3 is what ships. Correct the clipboard line in
  `.claude/CLAUDE.md`. README was already fixed by BR-9 and needs no change.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `clipboard/adb-grant`: every remaining requirement is REMOVED — the capability is deleted
  outright, because an ADB grant for background clipboard is not something Android 16 lets
  this app have.
- `clipboard/sync`: the requirement "Android 16 background clipboard access is gated on the
  ADB grant" is replaced by one that states the access is unobtainable, not merely ungranted,
  and names the two manual triggers. Its "Watcher runs with the grant" scenario goes, since
  no grant can turn it on. The capability's Purpose line stops pointing at
  `clipboard/adb-grant`.
- `ui/permission-panel`: the Android permission list drops its third item. The
  "All Android permissions granted" scenario stops naming ADB Clipboard, and the
  "ADB clipboard grant missing" scenario — which requires the app to display the failing
  command — is REMOVED.

## Impact

**Code (Android only)**
- `android/Bridge/app/src/main/java/com/bridge/permissions/PermissionStatusChecker.kt` —
  remove `adbClipboardGrant()`, `ADB_GRANT_COMMAND`, the `READ_CLIPBOARD_IN_BACKGROUND`
  constant and the third `PermissionItem`.
- `android/Bridge/app/src/main/java/com/bridge/clipboard/ClipboardBridge.kt` —
  `hasBackgroundClipboardAccess` loses its `checkSelfPermission` call on a constant that no
  longer exists; the gate becomes the platform fact it always was.
- `android/Bridge/app/src/test/java/com/bridge/permissions/PermissionSummaryTest.kt` — its
  fixture names "ADB Clipboard"; retarget it to a row that still exists.
- Any `MainActivity` / settings-screen call site that reads the removed row.

**Repo**
- `scripts/adb-grant-clipboard.sh` deleted.
- `openspec/specs/clipboard/adb-grant/` deleted at archive.

**Docs**
- `docs/PRD.md` "Android constraints" (the three-ways-out list).
- `.claude/CLAUDE.md` Permissions reference.

**Not affected**
- No Mac code, no Swift, no UI on the Mac side.
- No protocol change, no packet type added or altered, no test vector in `protocol/`.
- No transport tier is involved — this touches no transport. The clipboard path it
  documents rides tier 1 (BLE L2CAP control channel) exactly as before.
- No permission is added on either platform. Android **loses** a tracked grant
  (`READ_CLIPBOARD_IN_BACKGROUND`, which was never in the manifest); Notification Access and
  the battery-optimisation exclusion are untouched. Mac TCC grants are untouched.
- Phone-to-Mac clipboard behaviour does not change for the user. It was manual before this
  change and is manual after; what changes is that the repo stops claiming otherwise.
