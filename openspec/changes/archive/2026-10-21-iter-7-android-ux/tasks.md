## 1. Foreground Notification (Android)

- [x] 1.1 Add a new `IMPORTANCE_MIN` notification channel (new channel id to override the old one on upgrade) and delete the old channel. Verify: on a device, the Bridge FGS notification shows no status-bar icon and sits collapsed at the bottom of the shade.
- [x] 1.2 Rebuild `BridgeService.buildNotification()` with only `setSmallIcon` + `setOngoing(true)`, no title/text. Verify: notification shows icon only, no "Bridge active" text, makes no sound, cannot be swiped away while service runs.

## 2. Launcher Icon (Android)

- [x] 2.1 Create `ic_launcher_foreground` vector drawable (bridge/link glyph) and a background color/drawable; wire `mipmap-anydpi-v26/ic_launcher.xml` + `ic_launcher_round.xml` to the new layers. Verify: launcher shows the new adaptive icon with distinct foreground/background, not the notification glyph.
- [x] 2.2 Keep `ic_notification` as the monochrome small-icon and confirm nothing else references the old foreground reuse. Verify: mirrored/FGS notifications still use the monochrome glyph; `grep` shows no icon regressions.

## 3. Link State Model (Android)

- [x] 3.1 Define `LinkState { UNPAIRED, DISCONNECTED, CONNECTED }` derived from `TrustStore` (trusted Mac present?) and `BlePeripheral` connection status. Verify: unit test maps each trust/connection combination to the correct `LinkState`.
- [x] 3.2 Expose link-state changes to `MainActivity` (reuse existing BLE callback; add a minimal listener hook if none exists). Verify: a simulated connect/disconnect delivers a state-change callback to a test observer.

## 4. Main Screen Redesign (Android)

- [x] 4.1 Add `androidx.recyclerview` (and ConstraintLayout if used) to `app/build.gradle.kts`. Verify: project syncs and builds.
- [x] 4.2 Create `res/layout/activity_main.xml`: `MaterialToolbar` + `MaterialCardView` status card (app glyph, state pill, Mac name, last-seen) + grouped Notifications / Clipboard / Permissions sections. Verify: screen inflates and renders the card + sections in light and dark theme.
- [x] 4.3 Rewrite `MainActivity` to inflate the layout, set the toolbar, and bind the status card to `LinkState` (state pill, Mac name, last-seen). Verify: card reflects actual connection state on a device.
- [x] 4.4 Wire the Notifications section to open the notification-filter screen (iter-6 `NotificationFilterActivity`; if not yet present, a placeholder entry point). Verify: tapping Notifications launches the filter screen (or placeholder).
- [x] 4.5 Preserve existing clipboard status + "Send Clipboard to Mac" and notification-access prompt inside the new sections. Verify: clipboard status and notification-access button behave as before within the redesigned layout.

## 5. Smart Connect Action (Android)

- [x] 5.1 Add the single adaptive connect action to the `MaterialToolbar`; bind its label to `LinkState` (Pair Mac / Reconnect / Connected). Verify: label matches state for all three cases on a device.
- [x] 5.2 Implement tap behavior: `UNPAIRED` → start `PairingManager` handshake; `DISCONNECTED` → trigger BLE scan/advertise restart; `CONNECTED` → no-op. Verify: from unpaired, tapping starts pairing; from disconnected, tapping starts a reconnect attempt.
- [x] 5.3 Make the action update live on state transitions (no reopen). Verify: forcing a disconnect→connect while the screen is open flips the label to "Connected" automatically.

## 6. Automated Tests (Android)

- [x] 6.1 Unit tests for `LinkState` derivation and the action-label mapping. Verify: `./gradlew test` passes for the new tests.
- [x] 6.2 Confirm no regressions across iter-1..iter-6 test suites. Verify: full `./gradlew test` is green.

## 7. Manual Device Testing (Galaxy S25, Android 16)

- [x] 7.1 Confirm FGS notification is icon-only, silent, collapsed, and non-dismissable. Verify: observe in the shade.
- [x] 7.2 Confirm the new launcher icon appears in the launcher and recents. Verify: visual check.
- [x] 7.3 Walk the connect action through all three states (unpaired → pair, paired+disconnected → reconnect, connected → status). Verify: each transition behaves and the label updates live.
- [x] 7.4 Verify the redesigned screen in light and dark mode; status card, sections, and clipboard/notification entry points all work. Verify: visual + functional check.

## 8. Validate & Report

- [x] 8.1 Run `openspec validate iter-7-android-ux --strict` clean. Verify: validation passes.
- [x] 8.2 Update `docs/ITERATIONS.md` with iter-7 layman + technical summary and known issues (FGS notification cannot be fully removed; icon is a designed placeholder, swappable). Verify: section added.
- [x] 8.3 Archive the change: `openspec archive iter-7-android-ux`. Verify: change moved to archive and `ui/android-shell` spec synced to `openspec/specs/`.
