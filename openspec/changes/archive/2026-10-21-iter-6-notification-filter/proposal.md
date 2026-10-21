## Why

Since iter-3, every non-ongoing phone notification is mirrored to the Mac. In daily use this is too much noise on the Mac — games, spam, chat groups, and system chatter all surface as macOS notifications. The user needs to decide, on the phone, which apps are allowed to reach the Mac. The phone owns the filter (it is the source of notifications); the Mac simply receives whatever passes.

Relevant PRD section: Notification mirroring ("notifications you care about, not every buzz"). No transport tier changes — this rides the existing always-on BLE L2CAP control link used by `bridge.notification` since iter-3.

## What Changes

- **Deny-by-default allowlist on the phone.** A notification is forwarded to the Mac only if its source app is explicitly allowed. Newly-seen apps are silent until the user turns them on. This directly addresses "too much noise on Mac."
- **Per-app toggle screen (Android).** A settings screen lists apps that have posted notifications since Bridge was granted notification access ("seen apps"), each with an on/off switch and its icon + label. No new runtime permission — the list is built from the notification stream, which is unaffected by Android 11+ package-visibility filtering, so `QUERY_ALL_PACKAGES` is **not** required.
- **Global master mute (Android).** One switch pauses all Mac notifications regardless of per-app state (e.g. during a meeting). When off, nothing is forwarded.
- **Filter enforcement in the mirror path.** `BridgeNotificationListener.onNotificationPosted` checks an in-memory allow-set (read synchronously, no suspend on the callback) before building the `bridge.notification` packet. Disallowed notifications are dropped on the phone and never hit the link.
- **Persistence.** Allow-set, master-mute flag, and the seen-apps set persist in DataStore (already used in the repo by `TrustStore` and `BlePeripheral`), surviving app and device restarts.
- No Mac-side behavior change: the Mac keeps presenting every `bridge.notification` it receives. All filtering happens before the packet is sent.

Known degradation (documented, not fixed here): on Android 15/16, OTP/2FA notification bodies arrive redacted to an untrusted listener. This is orthogonal to filtering — an allowed app's OTP still mirrors, just with hidden body text.

## Capabilities

### New Capabilities
- `notifications/filter`: Phone-side, deny-by-default per-app allowlist plus a global master mute that gates which notifications are mirrored to the Mac, with a seen-apps toggle UI and persistent state.

### Modified Capabilities
<!-- None. notifications/mirror (iter-3) describes capture + forwarding of an
     allowed notification; this change adds a gate in front of it without
     altering the mirror packet shape or the forwarding behavior of an allowed
     notification. The gate is specified as its own new capability. -->

## Impact

- **Android code:**
  - `notifications/BridgeNotificationListener.kt` — add allow-set check + seen-app recording in `onNotificationPosted`.
  - New `notifications/NotificationFilterStore.kt` — DataStore-backed allow-set, master-mute flag, seen-apps set, exposed as an in-memory `StateFlow` for synchronous reads on the listener hot path.
  - New settings screen (per-app toggle list) — current app UI is plain programmatic AppCompat Views, no Compose; new screen follows that pattern (RecyclerView or equivalent) launched from `MainActivity`.
  - Background icon/label loading for the list (`PackageManager.getApplicationIcon` / `getApplicationLabel`) off the main thread.
- **Mac code:** none.
- **Protocol:** none — no new or changed packet types. `bridge.notification` unchanged.
- **Transport tiers:** none — uses existing BLE L2CAP control link.
- **Permissions:** no new permissions. Deliberately avoids `QUERY_ALL_PACKAGES` by sourcing the app list from the notification stream.
- **Dependencies:** none beyond existing AndroidX DataStore already in the build.
