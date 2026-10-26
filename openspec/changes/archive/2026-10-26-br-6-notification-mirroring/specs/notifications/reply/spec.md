## MODIFIED Requirements

### Requirement: Android delivers reply via RemoteInput
The Android side receives `bridge.notification.reply` packets and uses the stored RemoteInput to inject the
reply into the originating app's notification action. A cached RemoteInput SHALL remain usable for as long as
its notification is live, so that a conversation can be replied to more than once from the Mac. Looking up a
cached action SHALL NOT consume it.

#### Scenario: Valid reply received
- **WHEN** Android receives a `bridge.notification.reply` packet with a known `requestReplyId`
- **THEN** it looks up the cached Notification.Action with the matching RemoteInput, creates an Intent with the reply text set on the RemoteInput key, and calls `PendingIntent.send()` to deliver it to the originating app

#### Scenario: Reply delivered successfully
- **WHEN** the PendingIntent fires and the originating app accepts the RemoteInput
- **THEN** the reply appears in the app's conversation as if typed on the phone

#### Scenario: Second reply to the same notification
- **WHEN** the user replies from the Mac to a notification they have already replied to, and that notification
  is still live on the phone
- **THEN** the second reply is delivered to the originating app in the same way as the first

#### Scenario: requestReplyId not found (stale notification)
- **WHEN** Android receives a `bridge.notification.reply` with a `requestReplyId` that does not match any cached RemoteInput (e.g., notification was dismissed or expired)
- **THEN** the reply is dropped silently; no error is sent back to the Mac

#### Scenario: Reply after the notification is dismissed
- **WHEN** the notification a `requestReplyId` belongs to has been removed on the phone, and a reply for it
  arrives afterwards
- **THEN** its cached RemoteInput is no longer present and the reply is dropped as a stale reply

#### Scenario: RemoteInput cache eviction
- **WHEN** more than 200 reply-capable notifications have been received since the oldest cached entry
- **THEN** the oldest entries are evicted from the RemoteInput cache to bound memory usage; evicted notifications can no longer receive replies
