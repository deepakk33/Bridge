## Why

Notifications are the number one reason Bridge exists. The PRD success criteria require that "a notification arriving on the phone appears on the Mac in under two seconds." This is the first iteration that delivers daily value -- if the project stalls here, it has still paid for itself (PRD: Phase 3). Every subsequent feature (clipboard, files) is less important than this one working reliably.

## What Changes

**Android changes:**
- `NotificationListenerService` running in the same process as the foreground service (PRD: "Process model on the phone"). Captures notification title, text, app name, icon, actions, and `RemoteInput` keys.
- Constructs `bridge.notification` packets and sends them over the TLS-wrapped L2CAP channel.
- Handles incoming `bridge.notification.reply` packets: uses `RemoteInput` to inject the reply text into the originating app's notification (PRD: "requestReplyId field in the KDE Connect notification packet").
- Handles incoming `bridge.notification.request` packets to dismiss notifications by id.
- Sends `bridge.battery` packets periodically and on charge-state change. Carries `currentCharge` (0-100) and `isCharging` boolean.

**Mac changes:**
- Receives `bridge.notification` packets and posts to macOS Notification Center via `UNUserNotificationCenter`.
- Notification display includes app name, title, and text. Actions from the Android notification are mapped where possible.
- Reply action on notifications that carry a `requestReplyId`: user types reply in the macOS notification banner or Notification Center, Mac sends `bridge.notification.reply` back to Android.
- Dismiss action sends `bridge.notification.request` to clear the notification on Android.
- Menu bar updated to show battery percentage and charging state from `bridge.battery` packets.

**Protocol packets implemented:**
- `bridge.notification` (phone to Mac) -- id, appName, title, text, actions array, requestReplyId (optional).
- `bridge.notification.reply` (Mac to phone) -- requestReplyId, message text.
- `bridge.notification.request` (Mac to phone) -- cancel notification by id.
- `bridge.battery` (phone to Mac) -- currentCharge, isCharging.

## Capabilities

### New Capabilities

- **`notifications/mirror`** -- Android notifications appear in macOS Notification Center within 2 seconds of arrival. Covers all apps that post standard notifications. Requires Notification Access permission on Android (one-time grant in Settings) and Notification permission on Mac (one-time TCC prompt).
- **`notifications/reply`** -- Notifications with `RemoteInput` support (WhatsApp, Messages, Slack, etc.) can be replied to directly from the Mac notification. Reply text is injected via Android's `RemoteInput` mechanism. No SMS permission needed -- this works through the notification system (PRD: "a WhatsApp or Messages notification can be answered from the Mac through RemoteInput without any SMS permission at all").
- **`battery/status`** -- Phone battery percentage and charging state displayed in the Mac menu bar. Updated on change and periodically. Visible at a glance without interacting with the phone.

### Modified Capabilities

None. Builds on the secure channel from iter-2 without modifying it.

## Impact

- **Permissions required:** Android Notification Access (Settings > Notification access > Bridge, one-time). Mac Notification permission (TCC prompt on first notification post, one-time).
- **First real daily-use feature.** After this iteration, Bridge is useful even if nothing else ships.
- **Observability improvement:** Battery in the menu bar is the first persistent status indicator beyond connection state. Combined with iter-2's last-seen time, the menu bar now shows: connection state, last-seen time, battery %, and charging state.
- **Notifications are dropped when out of range, not queued** (PRD: "a notification that arrives twenty minutes late is noise"). If the link is down when a notification fires, it is lost.
- **The silent failure risk is highest here** (PRD: "Every one of these presents to the user as nothing happening"). If `NotificationListenerService` is killed by One UI battery optimization, notifications stop silently. The ping watchdog from iter-2 and the battery heartbeat together provide the detection mechanism.
