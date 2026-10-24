# notifications/reply Specification

## Purpose
When a mirrored notification on the Mac supports reply (indicated by `requestReplyId`), the user can type a reply directly in the macOS notification banner. The Mac sends a `bridge.notification.reply` packet back to Android, which injects the reply text into the originating app via RemoteInput. No SMS permission is needed.

## Requirements

### Requirement: Mac sends reply via bridge.notification.reply
When the user replies to a mirrored notification on the Mac, the reply text is packaged into a `bridge.notification.reply` packet and sent to the phone.

#### Scenario: User replies from notification banner
- **WHEN** the user types text into the inline reply field of a mirrored macOS notification and presses Send
- **THEN** the Mac sends a `bridge.notification.reply` packet containing `requestReplyId` (matching the original notification) and `message` (the reply text) to the phone over the secure channel

#### Scenario: Empty reply text
- **WHEN** the user presses Send with no text entered
- **THEN** no `bridge.notification.reply` packet is sent; the notification remains as-is

#### Scenario: Link down at reply time
- **WHEN** the user replies to a notification but the BLE link is disconnected
- **THEN** the reply is dropped and the Mac posts a local notification informing the user that the reply could not be delivered because the phone is out of range

---

### Requirement: Android delivers reply via RemoteInput
The Android side receives `bridge.notification.reply` packets and uses the stored RemoteInput to inject the reply into the originating app's notification action.

#### Scenario: Valid reply received
- **WHEN** Android receives a `bridge.notification.reply` packet with a known `requestReplyId`
- **THEN** it looks up the cached Notification.Action with the matching RemoteInput, creates an Intent with the reply text set on the RemoteInput key, and calls `PendingIntent.send()` to deliver it to the originating app

#### Scenario: Reply delivered successfully
- **WHEN** the PendingIntent.send() completes without exception
- **THEN** the original notification is cancelled (cleared) on Android since the messaging app will typically post an updated notification with the sent message

#### Scenario: requestReplyId not found (stale notification)
- **WHEN** Android receives a `bridge.notification.reply` with a `requestReplyId` that does not match any cached RemoteInput (e.g., notification was dismissed or expired)
- **THEN** the reply is dropped silently; no error is sent back to the Mac

#### Scenario: RemoteInput cache eviction
- **WHEN** more than 200 reply-capable notifications have been received since the oldest cached entry
- **THEN** the oldest entries are evicted from the RemoteInput cache to bound memory usage; evicted notifications can no longer receive replies

---

### Requirement: Reply-capable notifications are visually distinct
The user should know which notifications they can reply to before attempting it.

#### Scenario: Notification with reply on Mac
- **WHEN** a mirrored notification includes a `requestReplyId`
- **THEN** the macOS notification is posted with a category that shows an inline text input action labelled "Reply"

#### Scenario: Notification without reply on Mac
- **WHEN** a mirrored notification has no `requestReplyId`
- **THEN** the macOS notification is posted without a reply action; only dismiss is available
