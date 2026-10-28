# Tasks — BR-15 Clipboard: the ADB grant path is dead

Each task is well under 4 hours. Groups are ordered so the tree builds after every one of them.

## Android

- [ ] 1. **Move the permission constant to its last consumer.** Declare
  `READ_CLIPBOARD_IN_BACKGROUND` as a private constant inside `ClipboardBridge` and point
  `hasBackgroundClipboardAccess` at it, so the gate no longer imports `PermissionStatusChecker`.
  Keep the `checkSelfPermission` query and the existing KDoc explaining why it is always false on
  Android 16. Build must stay green with the old constant still present.

- [ ] 2. **Strip the clipboard row from `PermissionStatusChecker`.** Remove `adbClipboardGrant()`,
  `ADB_GRANT_COMMAND`, the `READ_CLIPBOARD_IN_BACKGROUND` constant, the now-empty `companion
  object` if nothing else is in it, the `PackageManager` import if unused, and the third
  `PermissionItem` from `all()`. `all()` returns Notification Access and Battery Optimization.

- [ ] 3. **Remove `PermissionStatus.LIMITED`.** Its only producer went with task 2.

- [ ] 4. **Update `PermissionSummaryTest`.** Retarget the `LIMITED` cases onto `DENIED` so the
  test still covers "a non-granted row is the first problem" and the status label assertion.

- [ ] 5. **Add the regression test that keeps the row gone.** Assert over
  `PermissionStatusChecker.all()` that no row's `name` or `detail` matches `adb`, `pm grant`,
  `clipboard` or `READ_CLIPBOARD`, and that the list holds exactly the two obtainable rows. Use
  whatever the Android test source set already supports for obtaining a `Context`; if it supports
  none, assert the same over a `PermissionItem` list built from the real strings and add a source
  grep assertion instead. This is what makes Done-when 1 and 4 checkable off-device.

- [ ] 6. **Verify Android.** Run `(cd android/Bridge && ./gradlew test assembleDebug)` and read
  the output. Do not mark this task until it is green.

## Repo and scripts

- [ ] 7. **Delete `scripts/adb-grant-clipboard.sh`** and grep the tree for anything that invokes
  or references it (`README.md`, `docs/`, other scripts, CI). BR-9 already corrected the README
  prose; confirm no live reference to the path survives.

## Specs and docs

- [ ] 8. **Fix the `clipboard/sync` Purpose line** in `openspec/specs/clipboard/sync/spec.md`
  directly — it currently says phone-to-Mac "requires an ADB grant (see `clipboard/adb-grant`)",
  pointing at a capability that is about to not exist. Purpose lines are edited in the main spec,
  not through a delta.

- [ ] 9. **Correct `docs/PRD.md` "Android constraints".** In the three-ways-out list, record that
  option 1 was attempted and is closed — `READ_CLIPBOARD_IN_BACKGROUND` is role-managed on
  Android 16, `pm grant` fails with `SecurityException`, and the `READ_CLIPBOARD` app op is not a
  substitute because its default is already `allow` — and that option 3 is what ships. Keep
  option 2 and its rejection; it is still live reasoning.

- [ ] 10. **Correct `.claude/CLAUDE.md`.** The Permissions reference lists "Clipboard: ADB grant
  for background read (`pm grant` READ_LOGS + READ_CLIPBOARD_IN_BACKGROUND)". Replace with the
  manual-trigger reality.

- [ ] 11. **Validate.** Run `openspec validate br-15-adb-grant` and read the output.

## Device test (SM-S931B / Android 16, MacBook Air M3)

Walked at the review gate, one line of observed evidence per criterion.

- [ ] 12. **Permission list is two honest rows.** Install the debug APK, open Bridge, read the
  permission block on screen. Expect Notification Access and Battery Optimization only, no
  clipboard row, no adb command anywhere on the screen. Covers Done-when 2 and 4.

- [ ] 13. **Both remaining rows still report correctly after the appops reset.** The fact-finding
  ran `appops reset com.bridge.app` on this device. Confirm Notification Access and Battery
  Optimization each still read Granted, and that toggling Notification Access off in system
  settings flips its row and back. Guards the design's stated risk.

- [ ] 14. **Clipboard behaviour is unchanged.** Copy text on the phone, tap the Quick Settings
  tile, paste on the Mac. Then copy on the Mac and paste on the phone. Then confirm the clipboard
  card still reads as manual sync in the normal colour, and that the link-down state still turns
  it amber. Confirms the change removed claims, not function.

- [ ] 15. **No background read is scheduled.** With the app backgrounded, confirm logcat shows the
  single "no background clipboard grant" line and no repeating clipboard poll entries over at
  least a minute.

- [ ] 16. **Nothing shipped tells the user to run a refused command.** Grep the built APK's
  strings and the repo for `pm grant`, `adb shell`, `READ_CLIPBOARD_IN_BACKGROUND` outside
  `openspec/changes/` and `docs/` history. Covers Done-when 1.

## Archive-time

- [ ] 17. **Remove the emptied capability directory.** After `openspec archive br-15-adb-grant`,
  check `openspec/specs/clipboard/adb-grant/`; if the archive left an empty or requirement-less
  spec, delete the directory and commit it with the archive.
