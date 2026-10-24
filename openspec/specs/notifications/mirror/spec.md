# notifications/mirror Specification

## Purpose
Android NotificationListenerService captures incoming notifications and sends them to the Mac as `bridge.notification` packets over the secure L2CAP channel. The Mac posts each notification to macOS Notification Center via UNUserNotificationCenter, preserving app name, title, and text.

## Requirements

### Requirement: Android enables NotificationListenerService
Bridge registers a NotificationListenerService that runs in the same process as the foreground service. It requires the user to grant Notification Access once in Android Settings.

#### Scenario: Notification Access granted
- **WHEN** the user enables Bridge under Settings > Notification access
- **THEN** the NotificationListenerService binds and begins receiving notification events

#### Scenario: Notification Access not granted
- **WHEN** the Bridge app starts without Notification Access permission
- **THEN** the app shows a prompt directing the user to the Notification Access settings page and does not attempt to capture notifications

#### Scenario: Notification Access revoked while running
- **WHEN** the user revokes Notification Access while Bridge is connected
- **THEN** notification mirroring stops and the Mac is not informed of new notifications (silent degradation, no crash)

---

### Requirement: Android captures notifications and sends bridge.notification packets
When a notification is posted on Android, the NotificationListenerService extracts the relevant fields and sends a `bridge.notification` packet to the Mac over the existing secure channel.

#### Scenario: Standard notification posted
- **WHEN** any app posts a notification on the phone
- **THEN** the NotificationListenerService fires onNotificationPosted, extracts `id` (notification key), `appName` (package label), `title`, `text`, `actions` array, and `requestReplyId` (if a RemoteInput is present), and sends a `bridge.notification` packet to the Mac within 500ms

#### Scenario: Notification with RemoteInput
- **WHEN** a notification carries a RemoteInput action (e.g., WhatsApp, Messages, Slack)
- **THEN** the `bridge.notification` packet includes `requestReplyId` set to a unique identifier linking back to the RemoteInput, and the `actions` array includes a "Reply" entry

#### Scenario: Notification without text
- **WHEN** a notification has no text body (e.g., progress-only or icon-only)
- **THEN** the packet is still sent with `text` set to an empty string; the Mac decides whether to display it

#### Scenario: Duplicate notification (same key, updated)
- **WHEN** an app updates an existing notification (same notification key)
- **THEN** a new `bridge.notification` packet is sent with the same `id`, and the Mac replaces the existing notification rather than posting a duplicate

#### Scenario: Link is down when notification arrives
- **WHEN** a notification fires while the BLE channel is disconnected
- **THEN** the notification is dropped silently; it is not queued for later delivery (PRD: "a notification that arrives twenty minutes late is noise")

---

### Requirement: Android filters system and low-priority notifications
Not every notification should be forwarded. Bridge filters out noise to avoid flooding the Mac.

#### Scenario: Ongoing/foreground-service notification
- **WHEN** the notification has FLAG_ONGOING_EVENT or FLAG_FOREGROUND_SERVICE set
- **THEN** it is not forwarded (these are persistent status bars, not events)

#### Scenario: Bridge's own notification
- **WHEN** the notification originates from Bridge's own package
- **THEN** it is not forwarded (prevents feedback loops)

#### Scenario: Group summary notification
- **WHEN** the notification is a group summary (FLAG_GROUP_SUMMARY)
- **THEN** it is not forwarded; only the individual child notifications are forwarded

---

### Requirement: Mac posts to Notification Center
The Mac receives `bridge.notification` packets and posts them to macOS Notification Center using UNUserNotificationCenter.

#### Scenario: First notification triggers permission prompt
- **WHEN** Bridge posts its first notification and the user has not yet granted Notification permission
- **THEN** macOS shows the standard TCC prompt for Notifications; if denied, no notifications are shown

#### Scenario: Standard notification received
- **WHEN** the Mac receives a `bridge.notification` packet with appName, title, and text
- **THEN** a local notification is posted with the subtitle set to the appName, the title set to the notification title, and the body set to the text, within 500ms of packet receipt

#### Scenario: Notification with reply action
- **WHEN** the `bridge.notification` packet contains a `requestReplyId`
- **THEN** the posted macOS notification uses a UNNotificationCategory with a UNTextInputNotificationAction so the user sees an inline reply field

#### Scenario: Updated notification replaces existing
- **WHEN** a `bridge.notification` packet arrives with an `id` matching a previously posted notification
- **THEN** the existing macOS notification is replaced (same notification identifier) rather than a second notification being posted

#### Scenario: Notification dismissed on Mac
- **WHEN** the user dismisses a mirrored notification on the Mac
- **THEN** a `bridge.notification.request` packet with the notification `id` is sent to Android to dismiss it there as well

---

### Requirement: End-to-end latency under 2 seconds
Notification mirroring must feel instantaneous. The PRD success criterion is under 2 seconds from phone notification to Mac notification.

#### Scenario: Latency measurement
- **WHEN** a notification is posted on the phone while both devices are connected over BLE
- **THEN** the corresponding macOS notification appears within 2 seconds of the Android notification's timestamp
