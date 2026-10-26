## 1. Shared — protocol

- [ ] 1.1 Add `bridge.notification.filter` to `protocol/constants.json` alongside the existing packet types,
      and add a test vector for it plus one for a phone→Mac `bridge.notification.request` under
      `protocol/test-vectors/`; verify both files parse with `python3 -m json.tool`.
- [ ] 1.2 Extend the Mac's `AllVectorsTests` and Android's `AllVectorsTest` so the new vectors are covered by
      the existing "every vector parses on both sides" sweep; verify both suites still pass.

## 2. Android — dismissal and replies

- [ ] 2.1 Send `BridgePacket.notificationRequest(cancel = sbn.key)` from
      `BridgeNotificationListener.onNotificationRemoved`, keeping the existing reply-cache eviction; verify
      with a unit test that removal produces a packet with the right type and `cancel` value.
- [ ] 2.2 Change `getCachedAction` from `replyCache.remove(id)` to a non-consuming read; verify with a unit
      test that two consecutive lookups of the same id both return the action, and that a lookup after
      `onNotificationRemoved` returns null.
- [ ] 2.3 Confirm the 200-entry LRU bound still holds with the non-consuming read by running the existing
      cache-eviction test; read the output.

## 3. Android — app names

- [ ] 3.1 Add a `<queries>` element to `AndroidManifest.xml` declaring the intent filters needed to resolve
      notification-posting apps, without `QUERY_ALL_PACKAGES`; verify `./gradlew assembleDebug` succeeds and
      the merged manifest contains the element.
- [ ] 3.2 Verify on device that the filter list shows real labels for the packages AUD-F observed as raw names
      (`com.axis.mobile`, `com.bt.bms`, `com.dreamplug.androidapp`), and that icons render.

## 4. Android — the all-denied state

- [ ] 4.1 Add `FilterState.withAllAllowed(seen:)` as a pure transition and a matching
      `NotificationFilterStore.allowAllSeen()` that persists once; verify with JVM unit tests covering the
      empty-seen case, the partially-allowed case, and that master mute is untouched.
- [ ] 4.2 Give `NotificationFilterActivity` the two empty states — "apps have notified but none are enabled",
      with an Enable all action, and "nothing has notified yet", without one; verify by launching the activity
      in both conditions via `adb` and reading the rendered text.
- [ ] 4.3 Wire the Enable all action to `allowAllSeen()` and refresh the list in place; verify on device that
      every row turns on without a reload and the state survives reopening the screen.

## 5. Android — the count on the home screen

- [ ] 5.1 Add the mirroring strings to `strings.xml` (`Mirroring %d apps` / `Paused` / `No apps on yet`); no
      inline literals. Verify none of the three is hard-coded in Kotlin or XML by grepping for them.
- [ ] 5.2 Render the count in the notifications card subtitle in `MainActivity`, observing the store's
      `StateFlow` so a toggle updates it without reopening the screen; verify on device across all three
      states.

## 6. Android — reporting the filter state

- [ ] 6.1 Add `BridgePacket.notificationFilter(allowed:seen:muted:)` and send it from `BridgeService` on
      connection establishment and on every filter-state change; verify with a unit test on the packet shape
      and by reading logcat on device for one send per toggle.

## 7. Mac — acting on what the phone sends

- [ ] 7.1 Replace the `"not applicable on Mac side"` branch for `bridge.notification.request` in
      `BLEManager.handlePacket` with a call into `NotificationManager`; keep `bridge.notification.reply`
      logging as-is since it genuinely is phone-bound. Verify the project builds.
- [ ] 7.2 Add `NotificationManager.withdraw(id:)` calling `removeDeliveredNotifications(withIdentifiers:)`, and
      a unit test that a withdraw for an unknown identifier is a no-op rather than an error.
- [ ] 7.3 Handle `bridge.notification.filter`: store the count and mute flag as `@Published` state that starts
      unknown; verify with a unit test that the initial state is unknown, that a received packet sets it, and
      that receiving the same packet twice leaves it unchanged.
- [ ] 7.4 Render the mirroring line in `StatusPanel`, including the unknown case; verify by eye that the panel
      holds its 260pt width in all four states (unknown, none, paused, N apps).

## 8. Verification

- [ ] 8.1 Run `xcodebuild -project mac/Bridge/Bridge.xcodeproj -scheme Bridge test` and read the output.
- [ ] 8.2 Run `(cd android/Bridge && ./gradlew test assembleDebug --rerun-tasks)` and read the output. The
      `--rerun-tasks` matters: the shared vectors are read at runtime from a relative path and are not a
      tracked Gradle input, so a cached run proves nothing.
- [ ] 8.3 Run `openspec validate br-6-notification-mirroring --strict` and read the output.

## 9. Manual device test (MacBook Air M3 + Galaxy S25)

- [ ] 9.1 With nothing allowed, open the filter screen, confirm it states that nothing is mirroring, tap
      Enable all, and confirm a notification from a previously-filtered app now reaches the Mac. This is the
      reproduction from 2026-10-26 (`Filtered out com.android.shell (muted=false, allowed=false)`), so re-run
      that exact `adb shell cmd notification post` and confirm it is forwarded rather than filtered.
- [ ] 9.2 Clear a mirrored notification on the phone and confirm it disappears from the Mac's Notification
      Centre; then dismiss one on the Mac and confirm it still clears on the phone, i.e. the existing
      direction did not regress.
- [ ] 9.3 Reply twice from the Mac to the same live WhatsApp or Messages notification and confirm both replies
      appear in the conversation.
- [ ] 9.4 Confirm the filter list shows names and icons for the three packages AUD-F named.
- [ ] 9.5 Confirm both home screens state the count, that toggling an app updates both without reopening
      either, and that master mute reads as paused on both.
- [ ] 9.6 Clear a stack of ten notifications at once and confirm the Mac keeps up and the control channel does
      not stall — the packet-burst risk from design.md.
