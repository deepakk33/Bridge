## 1. Android Notification Capture

- [ ] 1.1 Create `BridgeNotificationListener` class extending `NotificationListenerService` in the same process as `BridgeService`. Register in AndroidManifest with `BIND_NOTIFICATION_LISTENER_SERVICE` permission and intent filter. Verify: `adb shell dumpsys notification --listeners` shows Bridge as an enabled listener after granting access in Settings.
- [ ] 1.2 Implement `onNotificationPosted` with filtering: skip own package, skip FLAG_ONGOING_EVENT, skip FLAG_FOREGROUND_SERVICE, skip FLAG_GROUP_SUMMARY. Verify: unit test with mock StatusBarNotification confirms each filter case is excluded.
- [ ] 1.3 Extract notification fields in `onNotificationPosted`: id (sbn.key), appName (package label via PackageManager), title (EXTRA_TITLE), text (EXTRA_BIG_TEXT falling back to EXTRA_TEXT), actions (list of action title strings). Verify: unit test with a constructed notification extras bundle returns expected field values.
- [ ] 1.4 Detect RemoteInput actions: iterate notification.actions, check for RemoteInput entries, generate UUID as requestReplyId, cache the Action in a LinkedHashMap (cap 200, LRU eviction). Verify: unit test with a mock notification carrying a RemoteInput produces a non-null requestReplyId and the action is retrievable from cache by that id.
- [ ] 1.5 Wire `BridgeNotificationListener` to `BridgeService` packet sender. On capture, construct and send `bridge.notification` packet over the secure channel. Verify: connect Mac and Android, post a test notification on Android via `adb shell am broadcast`, confirm packet arrives on Mac (log output).
- [ ] 1.6 Add a check on app start for Notification Access permission. If not granted, show a button that opens the Notification Access settings page. Verify: on a fresh install with no permission, the button appears and tapping it opens the correct settings screen.

## 2. Protocol Packets

- [ ] 2.1 Add `bridge.notification` packet codec to both platforms. Body fields: id (String), appName (String), title (String), text (String), actions (Array of String), requestReplyId (String, nullable). Verify: shared test vector in `protocol/test-vectors/notification.json` is serialized and deserialized identically on both platforms.
- [ ] 2.2 Add `bridge.notification.reply` packet codec to both platforms. Body fields: requestReplyId (String), message (String). Verify: shared test vector in `protocol/test-vectors/notification-reply.json` passes on both platforms.
- [ ] 2.3 Add `bridge.notification.request` packet codec to both platforms. Body fields: cancel (String, the notification id to dismiss). Verify: shared test vector in `protocol/test-vectors/notification-request.json` passes on both platforms.
- [ ] 2.4 Add `bridge.battery` packet codec to both platforms. Body fields: currentCharge (Int, 0-100), isCharging (Bool). Verify: shared test vector in `protocol/test-vectors/battery.json` passes on both platforms.
- [ ] 2.5 Register packet handlers for all four new types in the packet dispatcher on both platforms. Verify: unrecognized packets are still ignored (existing behaviour), and each new type routes to its handler.

## 3. Mac Notification Posting

- [ ] 3.1 Request notification permission on first launch using `UNUserNotificationCenter.requestAuthorization(options: [.alert, .sound, .badge])`. Verify: permission prompt appears on first run; after granting, Bridge appears in System Settings > Notifications.
- [ ] 3.2 Register notification categories: `BRIDGE_REPLY` (with UNTextInputNotificationAction for inline reply) and `BRIDGE_DEFAULT` (no custom actions). Register on app init. Verify: categories are registered (check via logging or debugger).
- [ ] 3.3 Implement `NotificationManager.handleNotificationPacket()`: parse bridge.notification body, create UNMutableNotificationContent (title, subtitle=appName, body=text, categoryIdentifier based on requestReplyId presence, userInfo storing requestReplyId and bridgeNotificationId), post via UNNotificationRequest with id as identifier. Verify: send a test bridge.notification packet from Android; macOS notification appears with correct title, subtitle, and body.
- [ ] 3.4 Handle notification updates: when a bridge.notification arrives with an id matching a previously posted notification, the new UNNotificationRequest replaces the old one (same identifier). Verify: send two packets with the same id but different text; only one notification is visible, showing the updated text.
- [ ] 3.5 Implement UNUserNotificationCenterDelegate to handle dismiss: on UNNotificationDismissActionIdentifier, extract bridgeNotificationId from userInfo, send `bridge.notification.request` packet to Android. Verify: dismiss a mirrored notification on Mac; Android notification is also cleared.

## 4. Reply Flow

- [ ] 4.1 Implement UNUserNotificationCenterDelegate reply handler: on "REPLY_ACTION", cast response to UNTextInputNotificationResponse, extract requestReplyId from userInfo and reply text from userText, send `bridge.notification.reply` packet. Verify: reply to a mirrored notification on Mac; packet with correct requestReplyId and message text appears in Android logs.
- [ ] 4.2 Implement Android `bridge.notification.reply` handler: look up cached Action by requestReplyId, bundle reply text via RemoteInput.addResultsToIntent, call PendingIntent.send(), remove from cache, cancel the notification. Verify: send a reply from Mac to a WhatsApp notification; the reply appears in the WhatsApp conversation.
- [ ] 4.3 Handle stale requestReplyId: if the id is not in the cache, drop the reply silently (log a debug message). Verify: send a bridge.notification.reply with a bogus requestReplyId; no crash, debug log entry appears.
- [ ] 4.4 Handle link-down reply: if the Mac user replies but the BLE channel is disconnected, post a local macOS notification saying "Reply could not be delivered -- phone is out of range." Verify: disconnect BLE, reply to a notification, see the failure notification.

## 5. Battery Status

- [ ] 5.1 Register BroadcastReceiver in BridgeService for ACTION_BATTERY_CHANGED, ACTION_POWER_CONNECTED, ACTION_POWER_DISCONNECTED. Extract currentCharge and isCharging. Verify: unit test with mock intents produces correct values.
- [ ] 5.2 Implement periodic battery sender: CoroutineScope timer fires every 5 minutes and sends `bridge.battery` packet. Verify: connect both devices, wait 5 minutes, confirm battery packet received on Mac.
- [ ] 5.3 Implement event-driven battery sender: send immediately on charge state change (plugged/unplugged) and on 10% level change. Verify: plug and unplug the phone; battery packet arrives on Mac within 2 seconds each time.
- [ ] 5.4 Send initial `bridge.battery` on BLE connect. Verify: disconnect and reconnect; Mac receives battery packet within 1 second of reconnection.
- [ ] 5.5 Update Mac menu bar to display battery: show percentage text and SF Symbol icon (battery.100/.75/.50/.25/.0, .bolt variant when charging). Red tint when level <= 15 and not charging. Hide or grey out on disconnect. Verify: send bridge.battery packets with various levels and charging states; menu bar reflects each correctly.

## 6. Testing

- [ ] 6.1 Create shared test vectors: `protocol/test-vectors/notification.json`, `notification-reply.json`, `notification-request.json`, `battery.json`. Each contains a valid packet and the expected parsed fields. Verify: files are valid JSON and match the packet schemas from design.md.
- [ ] 6.2 Write Mac unit tests: NotificationManager parses bridge.notification packets correctly, creates UNNotificationRequest with correct fields, chooses correct category based on requestReplyId presence. Verify: `swift test` passes.
- [ ] 6.3 Write Android unit tests: BridgeNotificationListener filtering logic, field extraction, RemoteInput cache put/get/eviction, reply handler with mock PendingIntent. Verify: `./gradlew test` passes.
- [ ] 6.4 End-to-end notification mirror test on real devices: post a notification on the S25 (e.g., send a message via a test app), confirm it appears on the MacBook Air within 2 seconds. Repeat 10 times. Verify: all 10 appear within 2 seconds.
- [ ] 6.5 End-to-end reply test on real devices: receive a WhatsApp message on the S25, reply from the Mac notification, confirm the reply appears in the WhatsApp conversation. Verify: reply is delivered and visible in the chat.
- [ ] 6.6 Battery display test: confirm menu bar shows correct percentage after connect, updates on plug/unplug, shows warning style at low battery, hides on disconnect. Verify: all four states visually confirmed.
- [ ] 6.7 Stress test: send 50 notifications in rapid succession (via `adb shell` script). Confirm all arrive on Mac, no crashes, no dropped packets, no duplicates. Verify: Mac shows exactly 50 notifications (accounting for any with the same id replacing earlier ones).
