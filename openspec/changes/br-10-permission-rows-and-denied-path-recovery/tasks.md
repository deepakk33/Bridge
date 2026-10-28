Android only. Verify commands used below.

2.2-2.4 and 3.2 stay unchecked until the device test in 4.3 confirms them on hardware —
their stated verification is on-device, and code landing is not the same as the check passing.


```bash
(cd android/Bridge && ./gradlew test assembleDebug)
openspec validate br-10-permission-rows-and-denied-path-recovery
```

## 1. The permission model becomes rows with actions

- [x] 1.1 Write the failing `PermissionRowsTest` cases first: five rows in a fixed order
      (Notification Access, Battery Optimization, Bluetooth, Notifications) and nothing else;
      every row has a non-empty `detail`; a granted row resolves to `PermissionAction.None`; a
      non-granted row resolves to its own non-`None` action; no row's text contains `adb`,
      `pm grant` or `appops`. Verify `./gradlew test` fails on them.
- [x] 1.2 Add the `PermissionAction` sealed type and `RuntimeRecovery.resolve(hasAsked,
      showRationale)` (design decisions 2 and 3) in `com.bridge.permissions`, importing nothing
      Android beyond `Manifest`; make `PermissionItem.detail` non-null, add its `action`, and
      extend `PermissionRows.of` to build the five rows. Verify `./gradlew test` turns 1.1 green.
- [x] 1.3 Write and pass `RuntimeRecoveryTest` over all four `(hasAsked, showRationale)`
      combinations — only `(true, false)` yields `OPEN_APP_SETTINGS`. Verify with
      `./gradlew test`.
- [x] 1.4 Add the two runtime-permission checks to `PermissionStatusChecker` so `all()` returns
      the four real statuses; verify `./gradlew test` keeps `PermissionSummaryTest` green — a denied
      runtime permission must make `allGranted` false and be `firstProblem`.
- [x] 1.5 Commit (`feat(android): BR-10 permission rows carry a consequence and an action`) with
      the Gradle result line in the body.

## 2. The permission section is rendered as rows

- [x] 2.1 Add `res/layout/view_permission_row.xml` (status dot, name, consequence line, action
      button), replace the `permission_status` monospace `TextView` in `activity_main.xml` with
      an empty `LinearLayout` container, and move every new string to `strings.xml`. Verify
      `./gradlew assembleDebug` succeeds.
- [ ] 2.2 Rewrite `MainActivity.updatePermissionStatus()` to inflate one row per `PermissionItem`
      — binding dot colour, name, `detail` and the per-row button, hiding the button on
      `PermissionAction.None` — and delete the `batteryOptButton` field and its layout entry.
      Verify on the Galaxy S25 that each row shows its own consequence and its own button and
      that no monospace text remains.
- [ ] 2.3 Wire each `PermissionAction` to its intent in `MainActivity`, the `Runtime` case
      consulting `RuntimeRecovery.resolve` and writing `has_asked_<permission>` to
      `SharedPreferences` whenever it launches a request. Verify on device: deny a runtime
      permission once → the row's button re-prompts; deny permanently → the same button opens
      Bridge's app settings.
- [ ] 2.4 Remove the `macName.text` assignment from `permissionLauncher`'s failure branch and
      call `updatePermissionStatus()` instead (design decision 5). Verify on device that denying
      a permission leaves the status card describing the link, with the problem shown only in its
      own row.
- [x] 2.5 Commit (`feat(android): BR-10 render permissions as rows with per-row actions`) after
      `./gradlew test assembleDebug` passes, with the result line in the body.

## 3. Inline guidance replaces the resume modal

- [x] 3.1 Add a guidance `TextView`, an action `Button` and a dismiss control to the status card
      in `activity_main.xml`, bound to `LinkState.promptBody` / `actionLabel` and `GONE` when
      `!state.needsAttention`; verify `./gradlew assembleDebug` succeeds.
- [ ] 3.2 Delete `maybePromptLinkIssue`, `syncLinkPrompt`, `applyPromptText`, the `linkPrompt`
      field and their `onResume`/`onPause` calls, rendering the guidance from `refreshLinkUi()`
      and clearing the dismissal on any state change. Verify on device that opening the app with
      Bluetooth off, while unpaired, and with the Mac app killed each shows no dialog and an
      inline line with a working button.
- [x] 3.3 Delete `LinkState.promptTitle` and its `LinkStateTest` cases now that it has no caller;
      verify `./gradlew test` passes and `grep -rn promptTitle android/` returns nothing.
- [x] 3.4 Delete the `app_glyph` `ImageView` from `activity_main.xml` and its `MainActivity`
      field; verify `grep -rn app_glyph android/` returns nothing and `./gradlew assembleDebug`
      succeeds.
- [x] 3.5 Commit (`feat(android): BR-10 inline link guidance, no resume modal`) after
      `./gradlew test assembleDebug` passes, with the result line in the body.

## 4. Verification and device test

- [ ] 4.1 Run `openspec validate br-10-permission-rows-and-denied-path-recovery` and fix whatever
      it reports.
- [ ] 4.2 Run `(cd android/Bridge && ./gradlew test assembleDebug)` from a clean state; record
      the final status line for the review gate.
- [ ] 4.3 **Device test** on the Galaxy S25 (Android 16), walking BR-10's "Done when" list:
      (a) each permission row states its consequence and offers its own action;
      (b) opening the app with a broken link shows no modal — check Bluetooth off, unpaired, and
      Mac app killed;
      (c) a user who denies a permission can grant it later without leaving the app — test both
      routes, the re-prompt and the app-settings fallback.
      Also confirm no monospace dump and no launcher glyph remain. Record pass/fail per criterion
      with what was observed; anything not reproducible is reported untested, never passed.
- [ ] 4.4 Record BR-10's criterion *"the adb command is reachable from the UI"* as **dropped, not
      implemented** — BR-15 deleted the grant, the script and the `clipboard/adb-grant`
      capability, and `ui/permission-panel` now forbids a row that shows a command. Mark it `[~]`
      in `docs/BACKLOG.md` with that reason at wrap-up, as BR-2 and BR-6 did for theirs.
