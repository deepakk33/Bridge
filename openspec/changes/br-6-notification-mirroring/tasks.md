## 1. Shared — protocol

- [x] 1.1 Add vectors for `bridge.notification.filter` (four cases: mirroring, nothing allowed, nothing seen,
      paused) and extend `notification-request.json` with phone-cleared cases; both parse under
      `python3 -m json.tool`.
      Deviation: the task said to add the type to `protocol/constants.json` "alongside the existing packet
      types", but that file holds only three BLE UUIDs and lists no packet types at all. Introducing a
      packet-type list is BR-12's criterion ("constants.json holds every shared constant and both sides read
      it"); adding a lone entry here would prejudge its shape. Left to BR-12.
- [x] 1.2 No test change needed: both sweeps already enumerate every `.json` in `protocol/test-vectors` that
      has a `valid` array, so the new vectors are picked up automatically. Confirmed by the parsed-count rising
      in both suites.

## 2. Android — dismissal and replies

- [x] 2.1 Send `BridgePacket.notificationRequest(cancel = sbn.key)` from
      `BridgeNotificationListener.onNotificationRemoved`, keeping the reply-cache eviction, skipping Bridge's
      own notifications, and sending only for allowed apps so never-mirrored churn (foreground services) stays
      off the channel. Deliberately not gated on master mute: pausing stops new notifications, but the Mac
      should still be told about ones it already shows.
      The packet shape is already covered by `NotificationPacketTest` on both sides. The wiring itself needs a
      real `StatusBarNotification` and so is device-verified at 9.2 — not claimed as unit-tested here.
- [x] 2.2 Extract the cache policy into `ReplyActionCache` — Android-free, so it tests on the plain JVM the
      way `FilterState` does (Robolectric is not a dependency and adding one for this is not worth it) — and
      make `get` non-consuming. `ReplyActionCacheTest` covers repeated reads, eviction by notification,
      unknown ids and replacement: 7/7 green.
- [x] 2.3 LRU bound confirmed by two new cases: the cache stays at its maximum under overflow, and a read
      keeps an entry from being evicted as eldest (access order still works, which `remove` had defeated).

## 3. Android — app names

- [x] 3.1 Add a `<queries>` element to `AndroidManifest.xml` declaring the intent filters needed to resolve
      notification-posting apps, without `QUERY_ALL_PACKAGES`; verify `./gradlew assembleDebug` succeeds and
      the merged manifest contains the element.
- [x] 3.2 Verified on device: the list shows "Axis Mobile", "bigbasket", "Airtel", "Amazon Music", "Slack",
      "Snapchat", "Swiggy" and so on. A sweep of the whole list found **zero** strings beginning `com.`, where
      AUD-F saw raw package names for all but two apps.

## 4. Android — the all-denied state

- [x] 4.1 Add `FilterState.withAllSeenAllowed()` as a pure transition plus `NotificationFilterStore.allowAllSeen()`
      persisting once; 9 new JVM tests cover empty-seen (no write), already-partially-allowed, master mute
      untouched, never-seen apps excluded, and survival across a restart. Filter 17/17, store 10/10.
- [x] 4.2 Give `NotificationFilterActivity` the two empty states — "apps have notified but none are enabled",
      with an Enable all action, and "nothing has notified yet", without one; verify by launching the activity
      in both conditions via `adb` and reading the rendered text.
- [x] 4.3 Wire the Enable all action to `allowAllSeen()` and refresh the list in place; verify on device that
      every row turns on without a reload and the state survives reopening the screen.

## 5. Android — the count on the home screen

- [x] 5.1 Add the mirroring strings to `strings.xml` (`Mirroring %d apps` / `Paused` / `No apps on yet`); no
      inline literals. Verify none of the three is hard-coded in Kotlin or XML by grepping for them.
- [x] 5.2 Render the count in the notifications card subtitle in `MainActivity`, observing the store's
      `StateFlow` so a toggle updates it without reopening the screen; verify on device across all three
      states.

## 6. Android — reporting the filter state

- [x] 6.1 Add `BridgePacket.notificationFilter(allowed:seen:muted:)` and send it from `BridgeService` on
      connection establishment and on every filter-state change; verify with a unit test on the packet shape
      and by reading logcat on device for one send per toggle.

## 7. Mac — acting on what the phone sends

- [x] 7.1 Replace the `"not applicable on Mac side"` branch for `bridge.notification.request` in
      `BLEManager.handlePacket` with a call into `NotificationManager`; keep `bridge.notification.reply`
      logging as-is since it genuinely is phone-bound. Verify the project builds.
- [x] 7.2 Add `NotificationManager.withdraw(id:)` calling `removeDeliveredNotifications(withIdentifiers:)`, plus a pure
      `cancelIdentifier(from:)` guard with 5 tests (valid, empty, missing, wrong type, non-string). The no-op on
      an unknown identifier is `removeDeliveredNotifications`' own documented behaviour, so it is not re-tested
      here; what is tested is that a packet naming nothing withdraws nothing.
- [x] 7.3 Handle `bridge.notification.filter`: store the count and mute flag as `@Published` state that starts
      unknown; verify with a unit test that the initial state is unknown, that a received packet sets it, and
      that receiving the same packet twice leaves it unchanged.
- [x] 7.4 Rendered, amber when nothing is reaching the Mac. Observed on device at 284pt (260 + padding) in
      three states — "No apps mirroring", "Mirroring 45 apps", "Mirroring paused" — and the unknown string is
      shorter than all of them, so width is unaffected.

## 8. Verification

- [x] 8.1 Run `xcodebuild -project mac/Bridge/Bridge.xcodeproj -scheme Bridge test` and read the output.
- [x] 8.2 Run `(cd android/Bridge && ./gradlew test assembleDebug --rerun-tasks)` and read the output. The
      `--rerun-tasks` matters: the shared vectors are read at runtime from a relative path and are not a
      tracked Gradle input, so a cached run proves nothing.
- [x] 8.3 Run `openspec validate br-6-notification-mirroring --strict` and read the output.

## 9. Manual device test (MacBook Air M3 + Galaxy S25)

- [x] 9.1 With nothing allowed, open the filter screen, confirm it states that nothing is mirroring, tap
      Enable all, and confirm a notification from a previously-filtered app now reaches the Mac. This is the
      reproduction from 2026-10-26 (`Filtered out com.android.shell (muted=false, allowed=false)`), so re-run
      that exact `adb shell cmd notification post` and confirm it is forwarded rather than filtered.
      **Done.** Filter screen showed "No apps are mirroring. Nothing reaches your Mac until you switch an app
      on below." with ENABLE ALL. After the tap the banner went, the Mac moved to "Mirroring 45 apps", and the
      same shell notification logged `Forwarding notification from com.android.shell` where it had logged
      `Filtered out ... (muted=false, allowed=false)`. The Mac posted it.
- [x] 9.2 Clear a mirrored notification on the phone and confirm it disappears from the Mac's Notification
      Centre; then dismiss one on the Mac and confirm it still clears on the phone, i.e. the existing
      direction did not regress.
      **Phone to Mac verified**: snoozing a notification produced `sendPacket: type=bridge.notification.request`
      and `Withdrew notification 0|com.android.shell|2020|tag_br6|2000`, and a real Instagram DM dismissal
      withdrew too. No loop: the Mac logged only withdrawals and sent nothing back. The Mac-to-phone direction
      is unchanged code — `git diff main...HEAD` on `NotificationManager.swift` is pure addition and does not
      touch `didReceive`, which is what sends that dismissal.
- [ ] 9.3 **Untested — needs a real conversation.** No reply-capable notification was live during testing and
      one cannot be manufactured from the shell (`cmd notification post` produces no RemoteInput), so the
      end-to-end double reply was not exercised. The defect itself is covered by `ReplyActionCacheTest`, whose
      `get does not consume the entry` case fails against the old `replyCache.remove` implementation. Needs
      someone to message the phone.
- [x] 9.4 Confirm the filter list shows names and icons for the three packages AUD-F named.
- [x] 9.5 Confirm both home screens state the count, that toggling an app updates both without reopening
      either, and that master mute reads as paused on both.
      **Done.** Enabling apps on the phone moved the Mac to "Mirroring 45 apps" with no screen reopened;
      toggling master mute moved it to "Mirroring paused" while Android's card read "Paused"; unmuting restored
      the count. Paused correctly outranks the count rather than reporting allowed apps as active.
- [x] 9.6 Clear a stack of ten notifications at once and confirm the Mac keeps up and the control channel does
      not stall — the packet-burst risk from design.md.
      **Done.** 10 posted: 10 forwarded, 10 posted on the Mac. 10 cleared: 10 dismissals sent, 10 withdrawn on
      the Mac. Pings kept flowing throughout, so the control channel did not stall. No batching needed.
