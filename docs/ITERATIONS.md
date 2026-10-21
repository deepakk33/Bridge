# Bridge — Iteration Log

## Iteration 1: BLE Spike & Foundation
**Status**: Complete  
**Branch**: `iter-1/ble-spike`  
**OpenSpec**: `openspec show iter-1-ble-spike`

### Layman Summary
Mac and phone find each other over Bluetooth and open a data channel. Both apps launch (menu bar on Mac, foreground service on Android) and exchange ping/pong messages to prove the link works.

### Technical Summary
BLE L2CAP channel between Mac (CoreBluetooth CBL2CAPChannel) and Android (BluetoothServerSocket insecure L2CAP). Android advertises PSM via GATT characteristic, Mac scans and connects. Newline-delimited JSON framing over the channel. Throughput test sends 50KB of JSON lines.

### Known Issues
- Insecure channel (no encryption) — addressed in iter-2
- No auto-reconnect — addressed in iter-2

---

## Iteration 2: Secure Link Layer
**Status**: Complete  
**Branch**: `iter-2/secure-link`  
**OpenSpec**: `openspec show iter-2-secure-link`

### Layman Summary
Devices pair once with a trust-on-first-use handshake. After pairing, they remember each other and reconnect automatically. All communication is authenticated via certificate fingerprints.

### Technical Summary
Self-signed certificates (Keychain on Mac, AndroidKeyStore on Android), mutual identity exchange with fingerprint verification, trust store persistence. PairingManager handles pair request/accept flow. Ping watchdog detects stale connections and triggers reconnect. Re-pairing supported when peer loses trust store.

### Known Issues
- Mac trust store empties on app restart (mitigated by re-accept logic)

---

## Iteration 3: Notification System
**Status**: Complete  
**Branch**: `iter-3/notifications`  
**OpenSpec**: `openspec show iter-3-notifications`

### Layman Summary
Phone notifications appear on the Mac as native macOS notifications. You can reply to messages directly from the Mac notification. Battery level shows in the Mac menu bar.

### Technical Summary
Android NotificationListenerService captures notifications, serializes to `bridge.notification` packets. Mac presents via UNUserNotificationCenter with action buttons. Reply flow: Mac sends `bridge.notification.reply`, Android delivers via RemoteInput. Battery monitoring via BroadcastReceiver, `bridge.battery` packets.

### Known Issues
- Notification actions limited to reply (no generic action forwarding)

---

## Iteration 4: Clipboard Sync
**Status**: Complete  
**Branch**: `iter-4/clipboard`  
**OpenSpec**: `openspec show iter-4-clipboard`

### Layman Summary
Copy text on either device, it appears on the other within a second. Works automatically when the Bridge app is in the foreground on Android. On reconnect, the most recently copied text wins.

### Technical Summary
Both platforms poll clipboard every 500ms. Mac uses NSPasteboard.general.changeCount. Android uses ClipboardManager with Activity-context reads (Service-context reads return null on Android 16). Echo suppression via `isRemoteWrite` flag prevents ping-pong. Connection-gated sends prevent `lastContent` poisoning before BLE ready. `bridge.clipboard.connect` packet on reconnect carries timestamp for newest-wins comparison (100ms tie threshold, zero always loses). ADB grant script for background clipboard access. Quick Settings tile for manual trigger.

### Known Issues
- Android 16 restricts clipboard reads from Service context — requires app in foreground or manual button tap
- Large clipboard content with embedded newlines can break JSON framing (pre-existing serialization issue)
- Builds on iter-5 for file transfer and permission panel

---

## Iteration 5: File Transfer & Polish
**Status**: Complete — bidirectional Tier 3 file transfer verified on hardware (MacBook Air M3 + Galaxy S25, Android 16)  
**Branch**: `iter-5/files-polish`  
**OpenSpec**: `openspec show iter-5-files-polish`

### Layman Summary
Send files both ways. Drag a file onto the Mac menu bar icon, or share a file from
any Android app to Bridge, and it lands on the other device. Small files (under
500 KB) go over Bluetooth with no network needed; larger files go over Wi-Fi when
both devices share a network, and fall back to Bluetooth (slower) otherwise. The
Mac menu bar now shows every permission Bridge needs and how recently the phone
was heard from, and warns when the phone's service is silently killed.

### Technical Summary
**Out-of-band payload channel (KDE Connect mechanism):** `bridge.share.request`
carries `filename`, `fileSize`, `payloadSize`, and `payloadTransferInfo{port}`.
The sender binds an ephemeral TCP port and serves the bytes; the receiver reads
exactly `payloadSize`. Keeps the Tier 1 BLE control channel free during transfer.
`PayloadServer`/`PayloadClient` on both platforms (Network.framework on Mac,
`ServerSocket`/`Socket` on Android) with 30 s inactivity timeout, partial-transfer
discard, and excess truncation.

**Tier routing:** `TierRouter` sends <512 KB over Tier 2 (Bluetooth Classic
RFCOMM), >=512 KB over Tier 3 (Wi-Fi LAN) when a shared network exists, else falls
back to Tier 2 with a slow-transfer warning.

**Tier 2 RFCOMM:** Android server/client via `listenUsingRfcommWithServiceRecord`
+ `createRfcommSocketToServiceRecord`; Mac via IOBluetooth RFCOMM channel + SDP
lookup. File-transfer UUID `A76E1A03-…`.

**Tier 3 Wi-Fi LAN:** `_bridge._tcp` advertised via `NWListener.service`
(Mac) / `NsdManager` (Android); discovery via `NWBrowser` (Mac) / NSD resolve
(Android). Info.plist gains `NSLocalNetworkUsageDescription` + `NSBonjourServices`.

**File UI:** Mac menu bar drop target (`dropDestination`) + "Send File…"
(`NSOpenPanel`) + progress/fallback states. Android `ShareReceiverActivity`
registered for `ACTION_SEND`/`SEND_MULTIPLE` forwards the URI to `BridgeService`,
which reads the bytes and drives `FileTransferCoordinator`.

**Permission panel:** Mac `PermissionChecker` (CBManager / UNUserNotificationCenter
/ Local Network) feeds a menu bar section with Granted/Denied/Not-Requested state,
Fix/Request buttons, and a warning badge on the icon. Android
`PermissionStatusChecker` (Notification Access, Battery Optimization, ADB clipboard
grant) surfaces in the settings screen; the service posts a fix-it notification
when Notification Access is off.

**Watchdog:** heartbeat already at 30 s; dead-detection raised to 90 s.
`LinkHealthEvaluator` (both platforms) classifies connected / stale / dead /
out-of-range / bluetooth-off, formats last-seen ("just now" / "Xs ago" / "Xm ago"),
and emits actionable guidance (distinguishes a clean out-of-range disconnect from a
silent One UI service kill while BLE stays connected).

**Tests:** payload loopback round-trip + checksum + timeout + excess, tier routing,
share-request vectors (with/without payload), link-health classification,
permission mapping/summary, and an all-vectors sweep that parses every shared test
vector on both platforms. All unit tests pass on both platforms; no regressions
from iter-1 through iter-4.

### Hardware validation (2026-10-21, MacBook Air M3 + Galaxy S25 / Android 16)
Verified end-to-end on real devices over BLE L2CAP + Wi-Fi:
- **Phone → Mac**: 2 MB photo and 92 MB video — byte-exact in ~/Downloads, streamed
  to disk (no whole-file-in-RAM), live progress + "Sent ✓" on the phone.
- **Mac → phone**: 604 KB file — byte-exact in the phone's Downloads (MediaStore),
  "Receiving from Mac X%" → "Saved from Mac ✓".
- **Tier routing**: files ≥512 KB took Tier 3 (Wi-Fi); a <512 KB file correctly
  fell to the (unimplemented) Tier 2 path and reported honestly.
- **Permission panel (Android)**: Notification Access / Battery Optimization / ADB
  Clipboard states render in the settings screen.
- **Watchdog heartbeat**: ping interval 30 s, dead threshold 90 s confirmed live;
  Mac detects a clean BLE disconnect instantly ("Out of range").

Three bugs were found and fixed during hardware testing:
1. `ShareReceiverActivity` crashed calling `grantUriPermission` on a URI it did
   not own → now copies to app-private cache and forwards the path.
2. Mac never pulled the payload (receive side unimplemented) → added
   `FileReceiveController` (streams to disk).
3. mDNS service resolution hung on this network → the sender now carries its IP in
   `payloadTransferInfo.host` and the receiver connects directly (mDNS kept as a
   fallback).

### Known Issues
- **Tier 2 (Bluetooth Classic RFCOMM) for small files (<512 KB) is not wired
  end-to-end** — the packet is sent but no RFCOMM payload channel is served, so a
  <512 KB transfer reports failure. Tier 3 (Wi-Fi) covers files ≥512 KB.
- **Tier 3 throughput is bounded by the Wi-Fi link.** On a weak/2.4 GHz/mesh path
  the link bufferbloats (observed 2–3 s RTT under load) and large transfers crawl;
  this is physical, not the app. On a clean 5 GHz LAN it runs at radio speed.
- **mDNS cross-device discovery (tasks 3.1–3.2) not independently verified** —
  resolution hung on the test network, so transfer uses the direct-IP path; mDNS
  remains a coded fallback.
- **Watchdog 90 s silent-death detection (6.4–6.7) not hardware-triggered** —
  `adb force-stop` is a clean BLE disconnect (handled instantly as "Out of range"),
  not a silent service death with BLE still up; the timeout logic is unit-tested.
- Mac notification banners require the Notification TCC grant (the unsigned dev
  build must be granted via the permission panel).
- Tier 4 (phone hotspot) remains deferred to v2 per PRD.

### What the next iteration builds on
v1 feature set is complete: notifications, clipboard, file transfer, battery,
permission panel, and watchdog. v2 candidates: Tier 4 hotspot, transfer
resume/queue UI, and generic notification-action forwarding.

---

## Iteration 6: Notification Filter
**Status**: Complete — automated tests pass, hardware-validated on Galaxy S25 / Android 16
**Branch**: `worktree-iter-6-notification-filter`
**OpenSpec**: `openspec show iter-6-notification-filter`

### Layman Summary
The phone now decides which apps are allowed to send notifications to the Mac.
Nothing reaches the Mac unless you turn an app on — a new "Notification Filter"
screen lists every app that has buzzed since Bridge got notification access, each
with an on/off switch, plus one "Mute all" switch for meetings. This cuts the Mac
notification noise (games, spam, chat groups) down to only what you choose. After
updating, the Mac goes quiet until you allow your first app — that is the intended
deny-by-default behavior, not a regression.

### Technical Summary
Phone-side forwarding gate in front of the iter-3 mirror. `NotificationFilterStore`
(process singleton) holds a `MutableStateFlow<FilterState>` loaded from
`SharedPreferences` (keys: `allowed_packages`, `master_mute`, `seen_packages`).
`BridgeNotificationListener.onNotificationPosted` records the source package as
"seen" (after the iter-3 ongoing/foreground/group-summary skips, modelled by the
pure `NotificationGate.isStructuralSkip`) then drops the notification unless
`!isMuted() && isAllowed(pkg)` — a synchronous, allocation-free `StateFlow.value`
read on the hot path, no suspend/coroutine. `NotificationFilterActivity` (plain
AppCompat Views + RecyclerView, launched from `MainActivity`) renders the seen-apps
list with icon + label resolved off the main thread and cached, a per-row allow
`SwitchCompat`, a master-mute header switch, and an empty-state hint. No Mac,
protocol, or transport change; no new Android permission (`QUERY_ALL_PACKAGES`
deliberately absent — the list is built from the notification stream).

**Deviation from design.md:** persists via `SharedPreferences`, not Jetpack
DataStore — the repo has no DataStore dependency and `TrustStore` et al. use
`SharedPreferences`/`EncryptedSharedPreferences`. Synchronous cached reads satisfy
the spec's hot-path requirement equally. The pure gate/state logic
(`NotificationFilter.kt`, behind a `FilterPersistence` interface) is split out so it
unit-tests on the plain JVM (the suite has no Robolectric). design.md D2 updated to
record this.

### Tests
18 new JVM unit tests (`NotificationFilterTest` ×12, `NotificationFilterStoreTest`
×6): deny-by-default gate, master-mute suppress/restore, newly-seen default-silent,
immutable transitions, structural-skip composition (allowed app's ongoing
notification still dropped), store persistence round-trip (survives restart) and
synchronous accessors. Full `./gradlew test` green, `assembleDebug` builds, no
regressions across iter-1..iter-5. `grep -c QUERY_ALL_PACKAGES AndroidManifest.xml`
= 0.

### Hardware validation (2026-10-21, Galaxy S25 SM-S931B / Android 16)
Driven over ADB; forwarding decisions read from the listener log and the persisted
`bridge_notification_filter.xml`:
- **5.1 deny-by-default** — with an empty allow-set, `com.nextbillion.groww` and the
  shell-posted notification were both `Filtered out (allowed=false)` and both
  recorded in `seen_packages`. No `allowed_packages` key present.
- **5.2 allow one app** — toggling "Shell" on in the real UI wrote
  `allowed_packages={com.android.shell}`; the next shell post logged `Forwarding`,
  while a non-allowed real app (`com.axis.mobile`) in the same window was
  `Filtered out`.
- **5.3 master mute** — enabling mute (`master_mute=true`) filtered the allowed
  shell app (`muted=true, allowed=true`); turning it off restored `Forwarding`.
- **5.4 persistence** — survived an app force-stop (fresh PID reloaded the allow-set
  and forwarded) and a full device reboot: the notification listener was rebound by
  the system without reopening the app, loaded the persisted allow-set, forwarded
  the allowed app and filtered a non-allowed one (`com.samsung.android.app.routines`).
- The filter UI rendered correctly: master-mute header switch, per-app rows with
  icon + label + switch, all off by default.

A one-line `Forwarding` / `Filtered out` log in `BridgeNotificationListener` was
added during validation and kept — low-noise and operationally useful.

### Known Issues
- **OTP/2FA body redaction — documented degradation, not reproduced this session.**
  `cmd notification post` cannot synthesize a platform-redacted sensitive
  notification, so the redaction (applied only to genuine messaging/OTP
  notifications) was not independently observed. On Android 15/16
  an allowed app's OTP notification still mirrors, but with the body redacted to the
  untrusted listener. Orthogonal to filtering; requires a signature/role permission
  unavailable to a sideloaded app.
- **Seen-apps list starts empty on fresh install** — apps appear only after they
  post their first notification (empty-state hint shown). By design; avoids
  `QUERY_ALL_PACKAGES`.

### What the next iteration builds on
Per-app allow state and the seen-apps UI are a base for future per-app rules
(per-app mute schedules, content hiding) if ever wanted. iter-7 (`iter-7-android-ux`)
is the next planned change.
