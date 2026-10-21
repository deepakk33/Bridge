## 1. Filter State Store (Android)

- [x] 1.1 Create `notifications/NotificationFilterStore.kt` backed by Preferences DataStore with keys: `allowed_packages` (Set<String>), `master_mute` (Boolean, default false), `seen_packages` (Set<String>). Verify: unit test writes and reads each key back correctly.
- [x] 1.2 Expose in-memory state as a `MutableStateFlow` loaded from DataStore at init; provide synchronous accessors `isAllowed(pkg): Boolean` and `isMuted(): Boolean` reading `.value`. Verify: unit test — after load, synchronous accessors return the persisted values without suspending.
- [x] 1.3 Implement write operations `setAllowed(pkg, Boolean)`, `setMasterMute(Boolean)`, `recordSeen(pkg)` that update DataStore and the StateFlow. Verify: unit test — toggling updates both StateFlow value and persisted DataStore on reload.
- [x] 1.4 Instantiate the store in `BridgeService` (or as a process-scoped singleton) so the listener and the settings UI share one instance. Verify: toggling in UI is visible to the listener without an app restart (covered by 5.x manual test).

## 2. Forwarding Gate (Android)

- [x] 2.1 In `BridgeNotificationListener.onNotificationPosted`, after the existing iter-3 skips, call `filterStore.recordSeen(sbn.packageName)` before any gate so dropped apps still become toggleable. Verify: unit/instrumented test — a notification from a never-seen app adds its package to `seen_packages`.
- [x] 2.2 Gate the `packetSender?.invoke(packet)` call: forward only if `!filterStore.isMuted() && filterStore.isAllowed(sbn.packageName)`; otherwise return without sending. Verify: unit test with a fake `packetSender` — disallowed app → sender not invoked; allowed app, mute off → sender invoked once.
- [x] 2.3 Verify the gate composes with iter-3 skips (own package, ongoing, foreground-service, group-summary still dropped regardless of allow state). Verify: unit test — an allowed app's ongoing notification is still not forwarded.

## 3. Settings UI (Android)

- [x] 3.1 Create `NotificationFilterActivity` (plain AppCompat Views, RecyclerView) launched from a new button in `MainActivity`. Header row holds a master-mute Switch bound to `setMasterMute`. Verify: launching the activity shows the screen with the master switch reflecting persisted state.
- [x] 3.2 Populate the RecyclerView from the `seen_packages` set; each row shows app icon, label, and an on/off Switch reflecting `isAllowed`. Resolve icon (`getApplicationIcon`) and label (`getApplicationLabel`) off the main thread and cache per package. Verify: with 3+ seen apps, list renders icons+labels with no main-thread I/O (no StrictMode disk-read violation).
- [x] 3.3 Wire row Switch to `setAllowed(pkg, checked)`; wire master Switch to `setMasterMute`. Show an empty-state hint ("apps appear here after they notify") when `seen_packages` is empty. Verify: toggling a row persists across reopening the activity; empty install shows the hint.
- [x] 3.4 Confirm no new permission is declared — build the list purely from seen packages; assert `QUERY_ALL_PACKAGES` is absent from the manifest. Verify: `grep -c QUERY_ALL_PACKAGES AndroidManifest.xml` returns 0.

## 4. Automated Tests (Android)

- [x] 4.1 Unit tests for `NotificationFilterStore` (persistence, StateFlow sync, default values). Verify: `./gradlew test` passes for the new test class.
- [x] 4.2 Unit tests for the gate logic covering the spec scenarios: disallowed drop, allowed forward, newly-seen default-silent, master-mute suppress, mute-off restore. Verify: all scenario tests pass.
- [x] 4.3 Confirm no regressions in existing notification tests (`NotificationPacketTest`). Verify: full `./gradlew test` is green.

## 5. Manual Device Testing (Galaxy S25, Android 16)

- [x] 5.1 Fresh state: with empty allow-set, post notifications from several apps — confirm Mac shows nothing and all those apps appear in the filter list. Verify: Mac Notification Center stays empty; apps listed.
- [x] 5.2 Allow one app (e.g. Messages), post a notification — confirm it appears on the Mac within 2 seconds; post from a non-allowed app — confirm Mac stays silent. Verify: only the allowed app mirrors.
- [x] 5.3 Toggle master mute on, post from an allowed app — confirm Mac silent; toggle off — confirm mirroring resumes. Verify: mute gates everything.
- [x] 5.4 Restart the Bridge app and reboot the phone — confirm allow-set and master-mute persist. Verify: same apps allowed after restart and after reboot.
- [ ] 5.5 Confirm documented degradation: an allowed OTP app mirrors the notification with redacted body on Android 16. Verify: observe redacted body, note in report.

## 6. Validate & Report

- [x] 6.1 Run `openspec validate --change iter-6-notification-filter` clean. Verify: validation passes with no errors.
- [x] 6.2 Run full Android test suite — no regressions across iter-1..iter-5. Verify: `./gradlew test` green.
- [x] 6.3 Update `docs/ITERATIONS.md` with iter-6 layman + technical summary and known issues (deny-by-default quiet-until-allowed behavior, OTP redaction). Verify: section added.
- [ ] 6.4 Archive the change: `openspec archive iter-6-notification-filter`. Verify: change moved to `openspec/changes/archive/` and `notifications/filter` spec synced to `openspec/specs/`.
