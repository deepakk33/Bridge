## Why

Notification mirroring is the PRD's headline v1 feature — "Notification mirror, phone to Mac" and
"Notification reply from Mac" are both rated High value in the feature catalogue. On the test phone it is
doing nothing at all, and nothing on either screen says so. Reproduced live on SM-S931B on 2026-10-26:

```
BridgeNotifListener: Filtered out com.android.shell (muted=false, allowed=false)
```

Not muted — the allow-list is simply empty. `shouldForward(pkg) = !masterMute && pkg in allowed`
([`NotificationFilter.kt:21`](../../../android/Bridge/app/src/main/java/com/bridge/notifications/NotificationFilter.kt#L21))
is deny-by-default, and `allowed` is seeded only by the user toggling apps that have already posted. A fresh
install therefore mirrors nothing, forever, in silence. AUD-F records three further defects behind that one.

This is exactly the PRD's stated worst failure mode: *"Every one of these presents to the user as nothing
happening. A notification that does not arrive looks identical to no notification having been sent."*

## What Changes

- **Dismissal travels phone → Mac.** `onNotificationRemoved` sends `bridge.notification.request`, and the Mac
  gains a handler for it. The Android factory `BridgePacket.notificationRequest(cancel:)` has zero callers, and
  the Mac currently answers the packet with *"Received bridge.notification.request — not applicable on Mac
  side"* ([`BLEManager.swift:173-174`](../../../mac/Bridge/Sources/Bridge/BLE/BLEManager.swift#L173)). Both ends
  are missing; the backlog's scope line named only the Android half.
- **A second reply to one notification arrives.** `getCachedAction` uses `replyCache.remove`, consuming the
  entry on first use, so the second reply is a silent cache miss. It becomes a `get`, with eviction staying
  where it already is in `onNotificationRemoved`.
- **The app list becomes readable.** A `<queries>` element in the manifest so `getApplicationLabel()` resolves.
  Without it Android 11+ package visibility hides nearly every app and the list shows raw package names —
  observed as `com.axis.mobile`, `com.bt.bms`, `com.dreamplug.androidapp`. The list is also the only way to fix
  the empty allow-list, so an unreadable list makes that unfixable by the user.
- **Both home screens state how many apps are mirroring** — Android's notifications card and the Mac's status
  panel — so "nothing is mirroring" is never silent again.
- **The filter screen states the empty case and offers one tap out of it.** With nothing allowed it says so
  plainly and offers "Enable all", which allows every app already seen.

**Deny-by-default is kept, deliberately.** Flipping to allow-by-default was considered and rejected by the user
on 2026-10-26: it would mirror OTP and banking notifications to the Mac before the user had chosen anything,
over a control channel that is still plaintext until BR-4. **No migration either** — an upgrade does not seed
`allowed` from `seen`, so the same empty state and the same one tap apply to a new install and an upgraded one,
and nothing begins mirroring because the user installed an APK. Full rationale in `docs/BACKLOG.md` under BR-6.

Not in scope: the oversized filter rows and mid-word wrapping AUD-F notes in passing; the notification
`bridge.notification.reply` ack that AUD-B4 wants, which belongs with BR-3's send-failure work.

## Capabilities

### New Capabilities

None. All three surfaces already have specs.

### Modified Capabilities

- `notifications/mirror`: dismissal becomes bidirectional — a notification cleared on the phone is withdrawn on
  the Mac, which the capability does not currently require in that direction.
- `notifications/reply`: replying to the same notification more than once is required to work; the current spec
  does not say a reply may only be used once, and the implementation silently allows exactly one.
- `notifications/filter`: the filter list must identify apps by name and icon; the enabled count must be stated
  on both home screens; and the all-denied state must be stated with a one-tap route out of it.

## Impact

**Android** — `BridgeNotificationListener` (send on removal, `remove` → `get`), `AndroidManifest.xml`
(`<queries>`), `NotificationFilterActivity` (empty state, "Enable all"), `NotificationFilterStore` /
`FilterState` (a bulk allow), `MainActivity` + `activity_main.xml` (the count on the notifications card).

**Mac** — `BLEManager` routes `bridge.notification.request` instead of logging it away; `NotificationManager`
withdraws the delivered notification; `StatusPanel` shows the mirroring count.

**Protocol** — `bridge.notification.request` already exists on both sides with a `cancel` field carrying the
notification key, and already travels Mac → phone; this change uses it in the other direction, so a test vector
for it is added to `protocol/test-vectors` and checked on both sides.

One new packet type, `bridge.notification.filter`, carries the mirroring count phone → Mac. No existing packet
can hold it honestly: `bridge.identity` describes who a device is and is sent once per connection, while this
value changes whenever the user toggles an app, and re-sending identity to announce a filter change would
overload a packet whose meaning is "this is me". The new type is sent on connect and on every filter change,
and like every other handler it is idempotent. It is added to `protocol/constants.json` and gets a test vector
checked on both sides.

**Transport tiers** — Tier 1 only (BLE L2CAP control channel). Every packet here is small and newline-delimited;
nothing approaches the PRD's 10 KB out-of-band threshold.

**Permissions** — no new runtime permission on either side. `<queries>` is a manifest declaration, not a
permission, and is the narrow alternative to `QUERY_ALL_PACKAGES`, which Play policy restricts and which this
app does not need. Notification access (`NotificationListenerService`, manually enabled) is already required and
unchanged. Mac TCC is unchanged.
