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

## Iteration 7: Android UX Shell
**Status**: Complete — automated tests pass, hardware-validated on Galaxy S25 / Android 16
**Branch**: `worktree-iter-7+android-ux`
**OpenSpec**: `openspec show iter-7-android-ux` (archived)

### Layman Summary
The Android app got a real face. The home screen is now a clean card layout: a
status card at the top shows whether the Mac is connected, grouped cards below hold
Notifications, Clipboard, and Permissions. A single smart button in the top bar
reads your situation — **Pair Mac** when no Mac is set up, **Reconnect** when one is
paired but offline, **Connected** when the link is live — and it updates itself as
the connection changes, no reopening needed. The app has its own bridge icon in the
launcher instead of reusing the little notification chain. And the always-on
background notification is now a silent, text-less icon that sinks to the bottom of
the shade (Android forces it to stay, so this is the quietest it is allowed to be).

### Technical Summary
Android-only shell change; no Mac, protocol, or transport change, no new permission.
- **Link state model** (`LinkState`): a pure `enum { UNPAIRED, DISCONNECTED,
  CONNECTED }` with `resolve(hasTrustedMac, isConnected)` and `actionLabel(state)`,
  unit-tested without Android (`LinkStateTest`). `hasTrustedMac` is `!TrustStore.isEmpty`;
  `isConnected` from the BLE `ConnectionState`.
- **State plumbing** (`BridgeService.LinkStatus`): a process-level observable object
  holding the latest `BlePeripheral.ConnectionState` plus `lastConnectedAt`, fed from
  a single `onConnectionStateChanged` callback (shared by first start and reconnect),
  notifying listeners on the main thread. `MainActivity` subscribes in `onResume`,
  unsubscribes in `onPause`, so the top-bar label flips live on transitions.
  `BridgeService.requestReconnect()` posts a BLE restart to the running service.
- **Main screen** (`res/layout/activity_main.xml`): moved off programmatic Views to
  inflated XML — `MaterialToolbar` + `MaterialCardView` status card (glyph, state
  pill, Mac name, last-seen) + Notifications / Clipboard / Permissions section cards.
  The connect action is a single `MaterialToolbar` menu item whose title is rebound
  per `LinkState`; tap behavior: UNPAIRED/DISCONNECTED → ensure service + BLE restart,
  CONNECTED → no-op. The Notifications card opens the iter-6 `NotificationFilterActivity`;
  clipboard send and notification-access / battery-optimization prompts are preserved
  inside the new cards.
- **Launcher icon**: new `ic_launcher_foreground` (suspension-bridge vector) over the
  existing blue background, wired via `mipmap-anydpi-v26/ic_launcher{,_round}.xml`
  (minSdk 31, so the old misplaced `mipmap-hdpi/ic_launcher.xml` was removed).
  `ic_notification` stays as the monochrome status small-icon.
- **Foreground notification**: new `IMPORTANCE_MIN` channel (`bridge_service_min_channel`
  — a new id because Android ignores importance changes on an existing channel),
  icon-only builder (`setSmallIcon` + `setOngoing`, no title/text).

**Deviation from tasks.md:** the old `IMPORTANCE_LOW` channel is kept (not deleted)
to carry the actionable permission-warning notification, which should stay visible;
only the FGS notification moved to the new MIN channel. RecyclerView/ConstraintLayout
were not added — RecyclerView was already a dependency (iter-6) and the layout uses
`LinearLayout`/`NestedScrollView` + Material cards, so ConstraintLayout was unneeded.

### Hardware validation (2026-10-21, Galaxy S25 SM-S931B / Android 16)
Debug APK installed over ADB against a live MacBook Bridge link; states driven by
toggling the phone's Bluetooth and observed via screenshots + `dumpsys notification`
+ logcat:
- **FGS notification (7.1)** — `dumpsys` record: `channel=bridge_service_min_channel`,
  `contentView=null`, no title/text, `sound=null` + `FLAG_MUTE_HAPTIC`,
  `ONGOING|NO_CLEAR`, icon = `ic_notification`. In the shade it collapsed into the
  "N more notifications" bucket at the bottom with no status-bar icon.
- **Launcher icon (7.2)** — recents showed the new blue suspension-bridge adaptive
  icon, distinct from the monochrome chain.
- **Connect action (7.3)** — live link showed toolbar **CONNECTED** (no-op) + green
  pill; disabling Bluetooth flipped it to **PAIR MAC** + grey "No Mac paired" +
  "Last seen 2 minutes ago" with the screen open (no reopen); re-enabling Bluetooth
  reconnected and flipped it back to **CONNECTED** live. All three states observed.
- **Light/dark (7.4)** — status card + section cards render correctly in both themes.

**On-device caveat:** the channel is created at `IMPORTANCE_MIN` (`mOriginalImp=1`),
but Android floors a `connectedDevice` FGS notification's effective importance to
`LOW` (`importance=2`). The status-bar icon is still suppressed and the entry sinks
to the bottom of the shade — the quietest allowed form, as the proposal noted.

### Follow-up: honest trusted-link status + prompt-on-open (same branch)
Field testing surfaced a real defect: the status pill showed green **"Connected"**
whenever an L2CAP *socket* was open, even when the peer was untrusted and every
packet was being dropped (`L2capServer: Dropping packet from untrusted device`), so
the Mac received nothing while the phone claimed success. Fixed by making status
reflect the *verified* link, not the raw socket:
- `L2capServer` now reports `onPeerVerified(verified, peerName)` — computed from the
  trust-store pin check at identity time and again on pair-completion — and surfaces
  the real peer name. `BlePeripheral.ConnectionState.Connected` gained a `verified`
  flag; `BridgeService.LinkStatus` exposes `isVerified`, `isSocketConnected`, and
  `isBluetoothOn` (fed from the adapter + the Bluetooth state receiver).
- `LinkState` extended to `{ BLUETOOTH_OFF, UNPAIRED, DISCONNECTED, CONNECTING,
  CONNECTED }`. **CONNECTED is green only for a verified link**; a socket to an
  untrusted peer is **CONNECTING** (amber), never green. `resolve()` now takes
  `(isBluetoothOn, hasTrustedMac, isSocketConnected, isVerified)`; unit-tested.
- `MainActivity` shows an honest pill/color + the real Mac name, and on open (and on
  live state changes) raises a dialog that names the exact problem and offers the fix
  — Bluetooth-off → system enable prompt; unpaired/unverified → reconnect + "re-pair
  from the Mac"; disconnected → reconnect. The dialog updates live and dismisses
  itself the moment a verified link comes up.

Hardware-confirmed on the Galaxy S25: with the Mac untrusted (trust desync), the pill
reads amber "Connecting…", the card shows the paired Mac's name, and the open dialog
reads "Connected, but not verified … nothing is delivered."

### Follow-up: fix the pairing deadlock (same branch)
Surfacing the unverified state exposed *why* it got stuck: a mutual deadlock. The
Mac's `PairingManager.setPeerIdentity` only auto-initiates pairing when **its** trust
store is empty; if it already trusts the phone it goes `.paired` and sends nothing.
Android's `setPeerIdentity` only ever **reacted** to an incoming `bridge.pair` — it had
no initiate path. So when the phone lost its pin but the Mac kept the phone's,
neither side sent a pair request and every packet was dropped as untrusted forever;
tapping Reconnect only restarted the socket.

Fix (symmetric with the Mac): Android now **initiates** pairing.
`PairingManager.requestPair()` sends `bridge.pair` and awaits the accept (which pins
the peer). `setPeerIdentity` auto-calls it when the Android trust store is empty —
mirroring Mac PairingManager.swift:43-48. The Mac already re-accepts an incoming pair
request while `.paired` (re-pinning the phone), so the handshake completes both ways.
A manual path is wired too: `L2capServer.requestPairing` → `BlePeripheral` →
`BridgeService.requestPairing()`, invoked by the Reconnect action when the state is
CONNECTING (socket up, unverified).

Hardware-verified: on a fresh connection the phone logged "auto-initiating pairing …
Sent pair request … Pair accepted … verified=true", the pill went green "Connected",
and 0 packets were dropped over a full ping cycle.

### Follow-up: Mac notifications never displayed (same branch)
With the link finally verified, notifications still didn't appear on the Mac. The
unified log showed the Mac receiving every packet and calling
`UNUserNotificationCenter.add`, which failed with `hasError: 1` →
`NotificationManager: Failed to post notification`. Root cause: `handleNotificationPacket`
built `content.userInfo` with `"requestReplyId": requestReplyId as Any`, which boxes a
**nil** Optional whenever the notification has no reply action (nearly all of them).
`userInfo` must be property-list serializable, so `add()` rejected every reply-less
notification and nothing was ever shown — a Mac bug latent since iter-3, only exposed
once the link worked.

Fix (`mac/.../NotificationManager.swift`): insert `requestReplyId` into `userInfo`
only when non-nil; fall back to a UUID identifier if the incoming id is empty; log the
`add()` error with `.public` privacy plus a success line. Hardware-verified: the Mac
log now shows `Posted notification <key>` with no error, and WhatsApp/Gmail/other
notifications display on the Mac over the verified link.

Note: getting here also required allowing apps in the iter-6 deny-by-default filter
(a device-config change, not code) — the filter was correctly withholding every app
until allowlisted.

### Known Issues
- **FGS notification cannot be fully removed.** Android requires a notification for a
  `connectedDevice` foreground service; `IMPORTANCE_MIN` + content-less is the
  quietest allowed form. Effective importance is floored to `LOW` by the platform.
- **Launcher icon is a designed placeholder.** A vector suspension-bridge glyph chosen
  without user input; trivially swappable.
- **"Pair Mac" is advertising-only on the phone side.** Pairing is Mac-initiated
  (the phone auto-accepts the iter-2 handshake), so the action ensures the service is
  running and BLE is advertising so the Mac can discover and pair; it cannot
  unilaterally initiate from the phone.

### What the next iteration builds on
A reusable card-based visual system and a single `LinkState` the UI binds to; future
screens (e.g. the iter-6 filter screen) can adopt the same cards, and richer status
(signal, transfer activity) can hang off `BridgeService.LinkStatus`.

## Iteration 8: Clipboard Resilience, Handshake-on-Open, Service Persistence
**Status**: Complete — builds clean, hardware-validated on Galaxy S25 / Android 16 (One UI 8.5)
**Branch**: `iter-8/clipboard-overlay-resilience`
**OpenSpec**: `openspec show iter-8-clipboard-resilience` (archived)

### Layman Summary
Three fixes after a day of real use. (1) Clipboard: automatic phone↔Mac copy-paste in
the background is **not possible on Android 16 without rooting the phone** — the OS
locked down background clipboard access. So clipboard now works manually: the Quick
Settings tile pushes phone→Mac, and opening the app sends your phone clip to the Mac
*and* drops in whatever the Mac last copied. (2) The phone used to show "Connected"
even after the Mac had gone away; now opening the app does a quick handshake and tells
the truth, reconnecting if the link is dead. (3) The service now survives swiping the
app away from recents.

### Technical Summary
**Clipboard (OS regression, best-effort fix).** Android 16 denies background clipboard
R/W to any app that isn't the focused window, the default IME, or holds the privileged
`READ_CLIPBOARD_IN_BACKGROUND`. That permission is now *role-managed* — `pm grant`
fails with "managed by role", and Shizuku (shell-uid) can't grant it either; only root
can. The old iter-4 plan (`pm grant READ_CLIPBOARD_IN_BACKGROUND` via
`scripts/adb-grant-clipboard.sh`) worked on earlier Android and silently broke on the
16 update. An invisible `SYSTEM_ALERT_WINDOW` overlay (KDE Connect's trick) was built
and **empirically rejected** on One UI 8.5 — the overlay added fine (`ty=2038`) but the
Samsung ClipboardService kept logging "Denying clipboard access … not in focus" every
500 ms; a non-focusable overlay doesn't satisfy the focus check, and a focusable one
would hijack input. The overlay was reverted. What shipped instead: `ClipboardBridge`
caches the last Mac clip in `pendingRemoteContent` when a background write is denied and
re-applies it via the Activity context on focus (`flushPendingRemote`, wired into
`MainActivity.onWindowFocusChanged`). Clipboard status text now states the manual
reality.

**Handshake on open.** A silently-dead Mac (sleep / out of range) left `readLine()`
blocked until the 90 s `PingWatchdog` fired, so the phone showed a stale "Connected".
Added `L2capServer.probeLink()` → sends a ping and, if no packet arrives within 3 s,
closes the socket so the accept loop's `onConnectionChanged(false)` drops the status to
Advertising and BLE re-connects. Exposed `PingWatchdog.lastPacketAt`; plumbed
`BlePeripheral.probeLink` → `BridgeService.requestHandshake()`, invoked from
`MainActivity.onResume` when a socket is open. Hardware-verified: opening the app on a
dead link logged "Probe: no response in 3000ms — closing dead link" → Advertising →
GATT reconnect.

**Service persistence.** Added `android:stopWithTask="false"` to the `BridgeService`
manifest entry (already `START_STICKY`, `connectedDevice` FGS, battery-opt exempt) so
swiping the task from recents no longer kills the link.

### Known Issues / Degradations
- **No automatic background clipboard.** Inherent to Android 16 without root. Manual
  paths (QS tile, app-open flush) are the ceiling. Only root + a role grant of
  `READ_CLIPBOARD_IN_BACKGROUND` would restore full auto-sync.
- **`scripts/adb-grant-clipboard.sh` is now ineffective** on Android 16 — it reports success
  for READ_LOGS/appops but those no longer unblock background R/W. *Deleted by BR-15 on
  2026-10-28, once it was established that the appop it sets already defaults to `allow`, so
  the script's "Full sync active." was a false green rather than a partial one.*
- **Handshake probe uses a fixed 3 s timeout** — a very slow link could false-drop once
  on open, then auto-reconnect.

### What the next iteration builds on
`probeLink` / `requestHandshake` give an on-demand link-liveness check the UI can reuse
(e.g. a manual "test link" button). If root ever becomes acceptable, the clipboard
overlay scaffolding history and `flushPendingRemote` cache show exactly where a
privileged background read/write would slot in.

## BR-1: Grey Out the App List While Mirroring Is Paused
**Status**: Complete — builds clean, hardware-validated on Galaxy S25 (SM-S931B / Android 16)
**Branch**: `work/br-1-mute-greys-app-list`
**OpenSpec**: none — a port of already-written, already-hardware-verified code, so the
propose phase was skipped deliberately. First item through the `docs/BACKLOG.md` pipeline.

### Layman Summary
Turning on "Pause mirroring to the Mac" stops every app from being mirrored, but the list
of apps underneath stayed bright and tappable, so the screen looked like those apps were
still going through. The list now fades out and stops responding while mirroring is
paused, and comes back the moment you switch it off again.

### Technical Summary
`NotificationFilterActivity.AppAdapter` gained a `muted` flag. `onBindViewHolder` sets
`itemView.alpha = if (muted) 0.4f else 1f` and `sw.isEnabled = !muted`; `setMuted()`
re-binds on change and no-ops when the value is unchanged. It is called from the master
switch's listener and from `loadApps()`, so entering the screen already paused renders
dimmed on the first frame.

`muted` defaults to `false` rather than reading `store.isMuted()` at construction: the
adapter is a property initializer, and `NotificationFilterStore.init(applicationContext)`
touches the Context before it is attached — doing so crashed the activity (found on the
original branch, preserved here as a comment).

Ported from `2a9119a` on the abandoned `worktree-fix+mute-greys-applist` branch. That
commit also inverted the per-app switch polarity to ON = block and reworded the empty
hint to match; both were **dropped**. Bridge is an allow-list (`store.setAllowed`,
`isChecked = item.allowed`) and `0c31a23` had already rewritten that copy on `main`. The
allowed/`shouldForward` model is untouched — this is display-only.

### Hardware validation (2026-10-26, Galaxy S25 SM-S931B / Android 16)
Release-signed build installed over the existing one (the debug signature does not match,
and a debug install would have wiped the pairing). Driven by `adb input` + `uiautomator`:
- Master ON → every row switch reports `enabled=false`; tapping one leaves it
  `checked=false`; the screenshot shows the list at 40% alpha.
- Master OFF → rows return to `enabled=true` on the same screen, no reload.
- Paused, backed out, reopened → rows render `enabled=false` immediately.
- Polarity unchanged: an app switched ON persists as ON across a reopen (`allowed=true`).

### Known Issues / Degradations
- The 0.4 alpha is a literal, not a theme attribute — it was not checked against a light
  theme (the app is dark-only today).
- The dimmed state is an adapter flag, not derived from the store flow; a mute toggled
  from anywhere other than this screen would not redraw it until `loadApps()` runs. There
  is no such other path today.

### What the next item builds on
First run of the `/work` pipeline end to end. `setMuted()` is the pattern for any future
"this control is overridden" state in the filter screen.

---

## BR-2: Render the Mac Panel as a Window, Not an NSMenu

**Branch**: `work/br-2-render-the-mac-panel-as-a-window` · **Change**:
`openspec/changes/archive/2026-10-26-br-2-render-the-mac-panel-as-a-window` · **Source**: `AUD-A`

### In plain terms

The Mac's drop-down panel was being drawn as an old-style menu instead of a panel. That one
missing line shredded every row: the coloured permission dot, the name and the status each
became a separate greyed-out menu item, and 18 of the 22 items read as disabled. Adding the
line turned it into a real panel, and the rows now sit on one line each.

Two things we expected to fix turned out not to be fixable this way, and saying so is the
point of this entry. The panel still cannot accept a dragged file — it closes the moment you
click a file in Finder — so the line telling you to drag one was deleted rather than left
lying. And the pairing confirmation still cannot appear, because the app accepts pair
requests automatically before the screen ever sees them.

### Technical summary

- `.menuBarExtraStyle(.window)` on the `MenuBarExtra` scene. Single root cause; everything
  below follows from it.
- `StatusPanel`: fixed `.frame(width: 260)` + `.padding(12)` (284pt rendered, stable across
  link states), four dividers cut to two, `Send File…` to `.borderedProminent` and disabled
  while disconnected.
- Permissions list only what is not granted, via `PermissionChecker.needingAttention(in:)`;
  the section is absent when everything is held. Row is info-left / action-right.
- `PairPromptView` reshaped from a 340pt sheet to an inline banner. Its `.sheet` could never
  present from a non-activating panel. Keyboard shortcuts removed — inline, Escape is the
  dismiss key and Return the default action, so either would have refused or accepted a peer
  by accident. Keyed on `request.id` so a replacement request does not inherit the previous
  countdown.
- `BridgePacket.serialize()` emits `id`, `type`, `body` in order. It used `.sortedKeys` while
  Android and `protocol/test-vectors` use declaration order, so the two platforms produced
  different bytes for the same packet. Wire-compatible either way; taken here by explicit
  decision though it belongs to BR-12.
- `PhoneBatteryState.sfSymbol` no longer builds `battery.<n>.bolt`. SF Symbols ships a
  charging variant only for the full glyph, so the icon was blank below 76% while charging —
  in the panel and the menu bar both.

Mac 84/84, Android 190/190 (`--rerun-tasks`; the vector is not a tracked Gradle input).

### Hardware validation (2026-10-26, MacBook Air M3 + Galaxy S25 SM-S931B)

- Accessibility enumeration: one window, no `NSMenu`, zero disabled elements. The AUD-A dump
  had 22 flat items, 18 disabled.
- Permission row measured at a single y-coordinate (name, status and action all at y=229).
- Panel width 284pt in both connected and out-of-range states.
- `Send File…` reports `enabled=false` while the link is down.
- 600 KB file sent end to end over Wi-Fi LAN once both devices shared a subnet.
- Battery glyph draws while charging; panel reads `Phone: 60% · Charging`.
- Permission freshness: four panel opens produced four `Permission refresh` entries, so
  `onAppear` fires per open under `.window`. A review finding claiming stale rows was wrong.

### Known issues / degradations

- **Drag-and-drop onto the panel is gone, not fixed.** The audit read `.dropDestination` as
  inert only because of the `NSMenu` host. It is unreachable under the window presentation
  too: the panel is dismissed as soon as another app takes key focus, and dragging over the
  menu bar icon does not spring it open (two attempts on device). The copy and the handler
  were removed. Real support means owning an `NSStatusItem` instead of a `MenuBarExtra`
  scene — its own piece of work, not yet filed.
- **The pair banner has never run against a real request.** `PairingManager.swift:190-194`
  calls `presentPairConfirmation` then `acceptIncomingPair` in the same run loop while the
  trust store is empty, so `incomingPairRequest` is never left set. Its comment gives the
  reason as "the prompt only renders while the menu-bar panel happens to be open" — the
  premise this change removes. Rendering was verified by injecting a request into a
  throwaway build (that injection caught the fingerprint truncation below); the accept and
  reject wiring was not.
- **The 30-second auto-reject measures panel-open time, not wall-clock**, and contradicts
  `presentPairConfirmation`'s own "No timeout: the user decides". Left for BR-5.
- **Local Network is the only row the permission section ever shows**, because
  `setLocalNetwork(_:)` still has no callers. Honest, and louder than it was between two
  green rows. BR-11.
- **`sendFile` still hardcodes `sharedNetworkAvailable: true`**, so the Mac always picks
  Wi-Fi LAN and waits out a 30s timeout when the phone is unreachable. The timeout fires and
  is logged; computing the flag for real is BR-8.
- VoiceOver was not verified. Explicit `.accessibilityLabel`s were added because the window
  panel does not expose button titles the way the menu items did, but the labels do not
  surface through System Events scripting either, so only a VoiceOver pass settles it.
- Light mode, Increase Contrast and larger text sizes were not checked.

### What the next item builds on

BR-5 can now present a real confirmation — that was the blocker that made pairing automatic,
and removing the auto-accept is the one change that makes this panel's banner reachable.
BR-11 inherits a panel whose only permission row is the dead Local Network one.
A UI review raised polish left deliberately undone: ragged button widths, a single uniform
spacing value that groups nothing, and `SM-S931B` shown twice (BR-11's "device name appears
once").

---

## BR-6: Notification Mirroring — Dismissal, Replies, App Names

**Branch**: `work/br-6-notification-mirroring` · **Change**:
`openspec/changes/archive/2026-10-26-br-6-notification-mirroring` · **Source**: `AUD-F`

### In plain terms

Notification mirroring — the headline feature — was doing nothing at all, and neither screen said so.
Forwarding is deny-by-default and the list of allowed apps starts empty, so a fresh install mirrors nothing,
forever, in silence. On the test phone every app was off. The log said it plainly once we looked:
`Filtered out com.android.shell (muted=false, allowed=false)`.

The list that would have let you fix it was unusable too: Android hides app identities from apps that do not
declare `<queries>`, so nearly every row showed a raw package name like `com.axis.mobile`.

Now both home screens state how many apps are mirroring, the filter screen says plainly when nothing is and
offers one tap to turn everything on, and the list shows real names and icons. Clearing a notification on the
phone now clears it on the Mac, which it never did.

The default is deliberately unchanged. Mirroring everything automatically would have sent one-time passcodes
and banking alerts to the Mac before anyone chose to, over a channel that is still unencrypted until BR-4.

### Technical summary

- `onNotificationRemoved` sends `bridge.notification.request`, and the Mac acts on it. Both halves were
  missing: the Android packet factory had zero callers, and `BLEManager` answered the type with
  *"not applicable on Mac side"*. Sent only for notifications that could have been forwarded (same structural
  skips as posting, then the allow-list), and deliberately not gated on master mute.
- Reply cache extracted to `ReplyActionCache`, Android-free so it tests on the JVM like `FilterState`. `get` no
  longer consumes, an update to a live notification evicts its previous entry rather than stacking, and
  eviction stays keyed by notification. **`handleNotificationReply` no longer cancels the notification after
  replying** — that cancel fired `onNotificationRemoved` and evicted the entry, which is why the obvious
  `remove` → `get` fix alone did nothing.
- `<queries>` in the manifest, narrower than `QUERY_ALL_PACKAGES`.
- New packet `bridge.notification.filter` carries allowed/seen/muted. Sent on connect **and on pair
  completion** — the connect copy goes out before trust exists and the Mac drops it, so on a first pairing the
  pair-completed send is the one that lands. Also sent on every filter change. `MirroringState` is `nil` until
  reported and cleared on disconnect, so the Mac never shows an invented or stale count.
- `FilterState.withAllSeenAllowed()` plus an "Enable all" banner, which states the paused case separately
  because enabling apps changes nothing while muted.

Mac 98 tests, Android 113. Vectors added for the new type and for phone-cleared dismissals; both sweeps pick
them up automatically.

### Hardware validation (2026-10-26, MacBook Air M3 + Galaxy S25 SM-S931B)

- The AUD-F reproduction: the same `adb shell cmd notification post` that logged `Filtered out` now logs
  `Forwarding notification from com.android.shell`, and the Mac posts it.
- Filter list: real labels throughout — a sweep found **zero** strings starting `com.`, where AUD-F saw raw
  names for all but two apps.
- Dismissal phone → Mac on both a shell notification and real Instagram and WhatsApp notifications, with no
  echo loop (the Mac logged only withdrawals and sent nothing back).
- Counts on both screens: "No apps on yet" → "Mirroring 45 apps" → "Mirroring paused", pushed live with no
  screen reopened. Paused outranks the count.
- First pairing: verified on a real unpair/re-pair that the pair-completed send is what delivers the count.
- Burst: 10 posted → 10 forwarded → 10 on the Mac; 10 cleared → 10 withdrawn; pings never stopped.

### Known issues / degradations

- **The double reply was never tested end to end.** It needs a live reply-capable conversation, and
  `cmd notification post` cannot produce a RemoteInput. `ReplyActionCacheTest` covers the cache defect and
  fails against the old implementation, but the headline behaviour has not run on hardware.
- **Replying no longer dismisses the notification.** The originating app owns its notification after a reply;
  whatever it does now reaches the Mac through the dismissal packet. An app that neither updates nor cancels
  its own notification will leave it on screen where Bridge used to clear it.
- An app disallowed between posting and removal leaves one stale notification on the Mac.
- A dismissal that happens while the link is down is dropped, not queued, matching this capability's existing
  rule that a late notification is noise.
- The count is refreshed in `onResume` on Android rather than collected from the store's flow. The only way to
  change the filter is the filter screen, which must be left to see the home screen, so this covers every
  current path — but a future in-place toggle would not update it.

### Found while testing, and left for their owners

- **A fresh pairing can fail and leave both devices unpaired.** Unpairing from the Mac produced
  `Pair packet before peer identity — ignoring` on the Mac and `Pair request timed out` on the phone 30 s
  later; they stayed unpaired until the phone's service restarted. A race between the pair request and
  identity processing — **BR-5**.
- **A notification was mirrored from an untrusted peer**, posted between the failed pair request and its
  timeout. AUD-C's pre-identity gap, observed live — **BR-4**.

### What the next item builds on

BR-4 and BR-5 both gained concrete, reproduced evidence from this item's testing. `protocol/constants.json`
still lists no packet types, so `bridge.notification.filter` was not added there; building that list is BR-12's
criterion and was deliberately left to it.

---

## BR-3: Make the Link State Tell the Truth

**Branch**: `work/br-3-make-the-link-state-tell-the-truth` · **Shipped**: 2026-10-27 · **Platform**: Mac

### In plain terms

The Mac used to lie about the phone in four different ways, and every one of them lied in the
reassuring direction. A phone sitting on the desk whose Bridge service Android had killed showed
"Out of range" — blaming distance for a dead app. A link that dropped without a clean goodbye
kept showing "Connected", with a live-looking battery percentage, for up to two minutes. Every
message the Mac sent reported success whether or not it went anywhere, so the "Reply could not be
delivered" warning could essentially never appear. And the Mac said "Connected" the moment a
socket opened, before it had checked who was on the other end.

Now it says what is actually true: "Not responding" with advice to reopen Bridge on the phone,
"Out of range" only when distance is the likely cause, "Verifying…" while it checks the peer, and
"Connected" only once the phone is both verified and talking. When it cannot vouch for the phone's
battery reading, it hides it rather than showing a stale number.

### Technically

- `LinkHealth` and its `guidance(for:)` copy already existed with **zero callers**. They are now
  what `StatusPanel` and `menuBarIcon` switch on; `LinkState`/`LinkStateMachine` stays as the
  internal transport machine driving reconnect.
- Two new cases, `.verifying` and `.disconnected`, plus a `DisconnectReason` input
  (`local` / `supervisionTimeout` / `peerTerminated` / `unknown`) mapped from the `CBError` on
  `didDisconnectPeripheral`. Silence while the peripheral is still connected, or a peer-terminated
  disconnect, is `.dead`; a supervision timeout or an unrecognised reason is `.outOfRange`.
- `.connected` is gated on `TrustStore.isTrusted` for the peer's identity, matching Android's
  `LinkState.resolve`. The clipboard connect packet moved behind that gate.
- `L2CAPChannel.send` returns `Bool`, looping over partial writes via a new `FramedWriter`, with a
  64 KB cap on the queued tail and a hard stream error distinguished from backpressure.
  `sendPacket` refuses anything but identity, pair and ping to an unverified peer.
- Health is re-evaluated on a 10 s tick, so the window in which a dead link can read "Connected"
  fell from ~120 s to ~70 s with no change to the 30/60/90 s thresholds and no extra packets.

### Defects found and fixed along the way

- **`PingWatchdog.stop()` cleared `onTimeout` immediately before invoking it**, so the silent-death
  callback had never run in this project's history — the entire "phone service died" path was
  unreachable code. `PingWatchdogTests.testTimeoutCallbackFiresAfterSilence` fails against the
  pre-fix implementation.
- Staleness outranked verification, so a socket that opened and went quiet flipped from a red
  "Not responding" back to a neutral "Verifying…" exactly as the link died.
- A reconnect inherited the previous session's last-packet time and briefly read `stale(66s)`;
  `PingWatchdog.resetClock()` is now called on `didConnect`.
- An **unknown** Core Bluetooth state was rendered as "Bluetooth off". The TCC round-trip measured
  **34 seconds** on the test Mac, and the state also goes unknown across sleep — so every launch
  showed a red dot with settings guidance for half a minute, and the app flapped
  `bluetoothOff`/`outOfRange` every ~15 minutes overnight. An unreported state is now
  `.disconnected`: no claim, no guidance.
- Plus five smaller ones from the review: a hard stream error queued as backpressure, `bridge.ping`
  blocked before verification (which would let the phone's own watchdog tear down an unanswered
  pair prompt), "Last seen: just now" when nothing had ever been seen, and two ordering issues.

### Known issues / degradations

- **A supervision timeout specifically was never reproduced**, though the fallback path it would
  take is now confirmed on hardware. Three attempts to walk the phone out of range failed to drop
  the link at all, and adb cannot force a timeout, since disabling the phone's Bluetooth is a
  peer-terminated teardown that the spec maps to `.dead` by design.

  What *was* observed live (2026-10-27 12:51:29) is a real disconnect carrying no `CBError` at
  all — logged as "Disconnected cleanly" — which mapped to `.unknown` → `Link health: outOfRange`,
  with an automatic reconnect 1.4 s later. So the discrimination demonstrably works in both
  directions on this hardware: a force-stop reports `peerTerminated` and reads "Not responding",
  while an unexplained drop reads "Out of range". The residual risk is now narrow — only if a
  genuine range-loss timeout were reported as `peerTerminated` specifically would a departed
  phone read "Not responding", and nothing observed suggests it would.
- **The delivery-failure notification was not exercised end to end.** It needs a mirrored
  notification on screen and a human clicking Reply. Both halves are covered in isolation by
  `FramedWriterTests` and `PingWatchdogTests`.
- A queued-but-possibly-delivered write reports failure. This is deliberate — a false failure and
  a retyped reply beat a false success — but it means a stalled stream can produce a
  "could not be delivered" for a reply that does eventually go out.
- Unpairing while connected parks the panel on "Verifying…" with a Pair button, since there is no
  `.unpaired` state on the Mac. Left to **BR-5**/**BR-7**.

### Found while testing, and left for their owners

- **BR-13 (new)**: `BLEManager` cannot distinguish "I asked Core Bluetooth to scan and nothing
  arrived" from "nothing is there". Observed live: a second app instance quitting sent `stop scan`
  with `updateScanParams:YES` seven seconds after the live instance began its own scan, and the
  live app kept believing it was scanning while `bluetoothd` was not scanning for it. It sat on
  "Out of range" indefinitely with no retry and no recovery short of a restart. The app's *report*
  was honest throughout — it genuinely could not see the phone — which is why this is a discovery
  gap and not a BR-3 one.

### What the next item builds on

Every later fix is now observable: the link state is the single published `linkHealth`, logged at
public visibility, so a device test reads a state name rather than inferring one from redacted
transitions. BR-5's auto-accept gap is now *visible* rather than hidden — an unverified peer shows
"Verifying…" instead of silently reading "Connected".

---

## BR-9: Clipboard — Stop the Dead Poll, Stop the Silent Send

**Branch**: `work/br-9-clipboard` · **Shipped**: 2026-10-28 · **Platform**: Android

### In plain terms

Three things were wrong with the phone's clipboard, and they pulled in different directions.

The app checked the clipboard twice a second, forever, on the main thread — and Android 16 denies
that read, so it got nothing every single time. Pure battery cost for zero function, on a tool
whose main risk is being uninstalled for eating battery.

Opening the app sent your clipboard to the Mac. Not a button — merely bringing Bridge to the
front. Whatever was on the clipboard, a password, a one-time code, a card number, went over a
link that is still unencrypted until BR-4, with nothing on screen asking first.

And nothing limited how big a clip could be. A large copy went down the same narrow Bluetooth
channel that carries notifications and battery readings, holding everything else up behind it.

Now: no polling at all, because the permission that would make polling work cannot be obtained on
this phone (see below). Sending is deliberate — the button or the Quick Settings tile, nothing
else. Anything over 10 KB is cut to 10 KB, and the app tells you it cut it and how big the
original was. The clipboard card on the home screen now changes with the situation instead of
being permanently orange, and it is only orange when something is actually wrong.

### Technically

- `ClipboardCap` (new, pure) bounds outgoing text at 10 240 **bytes** of UTF-8 but cuts on a
  **code-point** boundary, so a clip of CJK or emoji is neither mis-measured nor split into a
  replacement character. Every phone→Mac path routes through it: the poll, the in-app button, the
  Quick Settings tile, the reconnect clip, and the exported `com.bridge.SEND_CLIPBOARD` test
  receiver, which had been sending raw text straight to the peer.
- `ClipboardTileService` previously built its own `BridgePacket.clipboard` and bypassed
  `ClipboardBridge` entirely. It now sends through `ClipboardBridge.sendClipboardFrom(context)`,
  so it both sets and clears the truncation outcome. Its `packetSender` hook is deleted.
- `ClipboardBridge.start()` returns early when the background grant is absent, scheduling nothing.
  `isWatching` exposes whether a watcher is *actually running*, which is what the card reads — not
  the grant, so the card cannot promise sync that no running watcher provides.
- `MainActivity.onWindowFocusChanged` keeps `flushPendingRemote()` (inbound, leaks nothing) and no
  longer calls the outbound send.
- `ClipboardCardState` (new, pure) maps `(isWatching, isLinkUp, lastSend)` to one of four states,
  precedence: link down → truncated → manual → syncing. The Activity maps the returned `Kind` to a
  string resource; the mapping stays out of the helper so its unit tests need no generated `R`.
  Card strings moved to `strings.xml`, the `#FF9800` literal to `colors.xml`.
- 129 unit tests green, including UTF-8 boundary cases and the full card precedence table.

### Known issues and degradations

- **The ADB clipboard grant does not exist on this hardware, and never did.** Verified on
  SM-S931B / Android 16 on 2026-10-28: `pm grant com.bridge.app
  android.permission.READ_CLIPBOARD_IN_BACKGROUND` fails with
  `SecurityException: Permission ... is managed by role`. The permission was also absent from the
  manifest, so `checkSelfPermission` could never have returned granted either. Automatic
  phone→Mac sync is therefore **permanently unavailable**, and the `SYNCING` card state is
  unreachable on real hardware — it is covered by unit test only. Filed as **BR-15**.
- **A truncated clip looks like a whole one on the Mac.** The phone reports the cut; the Mac
  receives no flag saying the text is partial. Adding one is a protocol change and belongs with
  **BR-12**, which is already about packet fields nobody reads.
- **The 10 KB cap is the PRD's number, not a measured one.** Measured on hardware: a packet queued
  immediately behind a capped clip arrived in 0.44 s, so the cap does its job at this size. It was
  not bisected to find where blocking actually starts.
- `PermissionStatusChecker`'s "ADB Clipboard" row still reports `LIMITED` and still holds the
  failing command in `.detail`. Out of scope here, but **BR-10 is about to surface `.detail` in
  the UI** and must not ship a command that throws.

### Found while testing, and left for their owners

- **BR-14 (new)**: two Mac instances were running — the installed `/Applications/Bridge.app` and
  an Xcode `DerivedData` debug build. Killing one dropped the *survivor's* BLE scan: it then sat
  on "Out of range" with the phone 30 cm away until the app was restarted. This is the exact
  precondition for BR-13's observed failure, now reproduced deliberately. BR-13 makes the app
  recover from a dropped scan; BR-14 removes the commonest cause by making the Mac single-instance.
- **BR-16 (new)**: `makeConnectPacket()` still pushes the phone's clip to the Mac on every
  reconnect (`BridgeService.kt:259`), which is neither user-initiated nor grant-backed — the
  same silent send BR-9 removed from the focus path, arriving through a different door. It is
  mandated by the pre-existing reconnect requirement and predates BR-9, so BR-9's own
  "user-initiated or grant-backed" requirement carries an exception clause until BR-16 decides
  the rule.
- **BR-15 (new)**: the `clipboard/adb-grant` spec capability, `scripts/adb-grant-clipboard.sh`
  and the "ADB Clipboard" permission row all describe a grant Android 16 refuses. This is an
  instance of **BR-12**'s last criterion — a spec requirement with no working code behind it.

### What the next item builds on

The phone no longer sends anything the user did not ask for, which is the precondition for BR-4
being worth doing: until now, hardening the channel would have protected a leak that fired on
every app open. The clipboard is also off the main thread's timer entirely, so battery
measurements for later items are not polluted by a 2 Hz wake.

One method worth reusing: the card states were verified by sampling pixel colours out of
`adb screencap` rather than by eye — manual `#BCBCBC` with zero amber pixels, link-down and
truncated both `#FF9800`. That turns "is it amber only when something is wrong" into a check
that either passes or fails.

---

## BR-15: Clipboard — The ADB Grant Path Is Dead, Decide What Replaces It

**Branch**: `work/br-15-adb-grant` · **Shipped**: 2026-10-28 · **Platform**: Android

### In plain terms

Bridge shipped a setup script that promised to turn on automatic clipboard sync from the phone.
It could not. Worse, it said it had.

The script's clipboard step did not actually run the command everyone assumed. Instead of
`pm grant READ_CLIPBOARD_IN_BACKGROUND` — which Android 16 refuses outright, with
`SecurityException: Permission ... is managed by role` — it ran `appops set READ_CLIPBOARD
allow`. That command succeeds. It succeeds because every app on the phone already has that
setting switched on by default, so setting it changes precisely nothing. The script then
printed *"Success! Phone-to-Mac clipboard sync is now active."* Running it with `--check`
reported *"Full sync active."* on any phone, including one where nothing had ever been granted.

So the repo contained a green light wired to nothing. Alongside it: a spec capability describing
how that grant works, and a permission row in the app pinned forever at "Limited", whose detail
text held the failing command ready for BR-10 to put on screen.

All three are gone. The script is deleted. The spec capability is deleted. The permission list
is two rows now — Notification Access and Battery Optimization — and both of them can actually
be turned on by tapping the button next to them. The knowledge that was worth keeping did not go
with them: the clipboard spec now states plainly that background phone-to-Mac clipboard is
unobtainable on Android 16 and that the tile and the in-app button are the answer, and the PRD
records that the ADB route was tried and is closed rather than still recommending it.

Nothing about using Bridge changed. The clipboard worked manually before this and works manually
after. What changed is that the product stopped claiming otherwise.

### Technically

- **The real finding.** `READ_CLIPBOARD`'s appop default mode is `allow` for every package —
  verified against untouched packages and after `appops reset com.bridge.app`. The appop was
  never the gate; Android's focused-window rule is, and no appop or `pm grant` moves it for a
  sideloaded build. `READ_CLIPBOARD_IN_BACKGROUND` was also absent from the manifest, so
  `checkSelfPermission` could never have returned granted on any path.
- **`PermissionStatusChecker`** loses `adbClipboardGrant()`, `ADB_GRANT_COMMAND`, the
  `READ_CLIPBOARD_IN_BACKGROUND` constant and the third row. `PermissionStatus.LIMITED` goes with
  them — it had exactly one producer.
- **Row construction moved behind a pure `PermissionRows.of()`**, following the existing
  `PermissionSummary` precedent, because this source set has no Robolectric and `all()` needs a
  `Context`. `PermissionRowsTest` can then assert off-device that no row's name or detail
  mentions `adb`, `pm grant`, `appops` or `clipboard`.
- **`PermissionCheckerDelegatesTest` closes the hole that guard left.** Asserting over
  `PermissionRows.of()` does not stop a row being appended inside `all()` — which is exactly the
  regression. It reads the source and requires `all()` to delegate and to construct no
  `PermissionItem` of its own. Both guards were mutation-checked: reinstating the row either way
  fails the build.
- **`ClipboardBridge` keeps its real `checkSelfPermission` query**, with the permission constant
  moved in beside it, rather than hardcoding the gate to `false`. The platform should keep saying
  no in its own voice; a constant would also lie on any Android where the answer differs.
- **Specs**: `clipboard/adb-grant` deleted (5 requirements). `clipboard/sync` gains "Background
  phone-to-Mac clipboard is unobtainable on Android 16", "Phone-to-Mac clipboard sends are
  user-initiated" (the old name promised a "grant-backed" alternative that cannot exist) and
  BR-9's clipboard-card requirement, which had been filed under `adb-grant` and would otherwise
  have been deleted with it. `ui/permission-panel`'s Android requirement is replaced by one whose
  rule is that every listed row must offer an action that can succeed.
- **Docs**: script deleted; PRD's three-ways-out list marks option 1 tried-and-closed with the
  exact `SecurityException` and records option 3 as shipped; `clipboard/sync`'s Purpose, the
  README paragraph and `.claude/CLAUDE.md` corrected.

### Known issues / degradations

- **The 500 ms poll machinery is still in `ClipboardBridge` and is unreachable on Android 16.**
  Kept deliberately: it is the mechanism that would work the moment `checkSelfPermission` returns
  granted, so it is unreachable on *this hardware* rather than in principle, and `isWatching`
  feeds `ClipboardCardState`, whose `SYNCING` arm would collapse with it — BR-9's card was device
  verified two days ago. If `SYNCING` is still unreachable after BR-10, the poller and the card
  state should be removed together.
- **`openspec archive` cannot retire a capability on its own.** Deleting the last requirement
  leaves a spec it refuses to write, and once the main spec is deleted by hand a REMOVED-only
  delta reads as a request to *create* an empty capability, which it also refuses. The sequence
  that works: delete `openspec/specs/<cap>/`, delete the delta folder from the change, then
  archive. The five removal reasons were moved into the proposal's appendix first so the record
  archives with the change rather than being lost with the delta.
- **One scenario escaped the delta.** `clipboard/sync`'s "Latency under one second" still carried
  an "Android to Mac latency (with ADB grant)" scenario, caught only by grepping the rebuilt main
  specs after archiving and fixed directly. A capability-wide grep belongs in the delta-authoring
  step, not after it.
- **The Quick Settings tile was not exercised on device.** `cmd clipboard set-text` does not exist
  on this build and a tile cannot be tapped over adb, so the in-app send control — which shares
  the send path — was used instead.

### Found while testing, and left for their owners

- **README carried a second stale claim**, unrelated to the grant: it told the user that opening
  the app sends the current phone clip, which BR-9 removed. Corrected here since the same
  paragraph was already being rewritten.
- **The OS corroborated the premise unprompted.** During the device test logcat showed
  `ClipboardService: Denying clipboard access to <pkg>, application is not in focus nor is it a
  system service` for several unrelated apps — the rule no grant moves, visible in the wild.

### What the next item builds on

**BR-10 is now safe to start.** It was going to render `PermissionItem.detail` in the UI, and the
only `detail` that would have thrown is gone, along with the status value that had no action
behind it. The screen BR-10 rebuilds is two honest rows, and `PermissionRows.of()` is the single
place a new row can be added — with two tests watching it.

The repo also has one fewer instance of **BR-12**'s last criterion: a spec requirement with no
working code behind it. Two remain, both in `openspec/specs/security/` and both owned by BR-4
and BR-5.

---

## BR-10: Android — Permission Rows, Resume Modal, Denied-Path Recovery

Branch `work/br-10-permission-rows-and-denied-path-recovery`, shipped 2026-10-28.

### In plain terms

The Permissions section of the phone app used to be a block of computer output — two lines of
monospace text reading `Notification Access: Granted`, with one button underneath that applied to
only one of them. It never said what any permission was *for*, or what Bridge could not do
without it.

It is now four rows. Each has a coloured dot, a name, one sentence saying what breaks without it,
and its own button. A permission you already have shows a green dot and no button, because there
is nothing left to do.

Two permissions that were never listed at all — Bluetooth, and the one that lets Bridge show you
alerts — are now rows too. Before, if you said no to one of those when the app first asked, the
app had no way to ask again: the only route back was Android's own app-info screen, which nothing
in Bridge pointed at. Now the row's button asks again, and if Android has stopped showing the
prompt it takes you straight to the screen where you can still switch it on.

Opening the app also used to throw up a pop-up telling you the link was down. The coloured pill,
the device name and the toolbar button already said that, so the pop-up was the fourth voice
saying one thing — and it came back every single time you opened the app. It is gone. The same
sentence now sits on the status card with its button beside it, and a Dismiss that lets you put
it away.

### Technically

- `PermissionItem.detail` had zero readers; it is now the row's consequence line, and non-null.
- A new `PermissionAction` sealed type says what a row's button does — `NotificationAccess`,
  `BatteryOptimization`, `Runtime(permissions)`, `None` — holding no `Context`, so
  `PermissionRows.of` stays a pure builder with off-device tests. Every `Intent` stays in
  `MainActivity`.
- `PermissionRows.of` now takes four statuses and returns four rows. `postNotifications` is
  nullable: `minSdk` is 31 and `POST_NOTIFICATIONS` does not exist below 33, so that row is
  dropped rather than invented.
- `RuntimeRecovery.resolve(hasAsked, showRationale)` decides prompt-vs-settings.
  `shouldShowRequestPermissionRationale` is false both before the first prompt and after a
  permanent denial, so a `has_asked_<permission>` boolean in `SharedPreferences` supplies the
  second input.
- Rows are inflated from `view_permission_row.xml` into a `LinearLayout`, not a RecyclerView —
  four static rows, nothing to recycle. The dot reuses `bg_state_pill`, whose 100dp corner radius
  renders a 10dp square as a circle, so no new drawable.
- `maybePromptLinkIssue`, `syncLinkPrompt`, `applyPromptText` and `LinkState.promptTitle` are
  deleted. `promptBody`/`actionLabel` are unchanged and now feed an inline guidance line rendered
  from the existing `refreshLinkUi()`. A dismissal is recorded against the state it was made in.
- `app_glyph` is gone, and with it the status card's horizontal wrapper, which had one child left.
- No protocol change, no packet change, no Mac change.

### Known issues and degradations

- **The adb criterion was dropped, not met.** "The adb command is reachable from the UI" cannot
  be satisfied: BR-15 deleted the grant, the script and the `clipboard/adb-grant` capability
  because Android 16 refuses the permission by role, and `ui/permission-panel` now forbids a row
  that shows a command. Recorded `[~]` in the backlog.
- **The runtime rows read "Granted" for nearly every user, nearly always.** That is the cost of
  making a denial visible, and it is why a granted row shows no button — a permanent row with an
  action that does nothing is the pattern BR-9 and BR-11 both name as training users to ignore
  the honest rows.
- **`has_asked_<permission>` is absent on installs that predate this build.** It self-heals:
  `checkPermissionsAndStart` marks it at the next launch. Worst case is one prompt Android
  declines to show.
- **One duplicate action remains.** The Notifications card still has its own "Grant Notification
  Access" button alongside the new permission row. It is contextual where the user is already
  thinking about notifications, and removing it was outside this item's scope.

### Found while testing, and left for their owners

- **Five defects came out of code review, two of which the device test then confirmed rather than
  the reasoning.** Granting via app settings used to leave the service stopped until the process
  was killed; and service start was gated on *every* runtime result, so declining the notification
  prompt left Bridge dead with nothing on screen saying so — the status card no longer carried
  the error line that used to hint at it. Both are fixed; `.BridgeService` was observed running
  with `POST_NOTIFICATIONS` denied.
- **Grant status had briefly become colour-only** — no word, no `contentDescription`, and
  `PermissionStatus.label` left with no production caller. A non-granted row now reads
  "Name — Disabled" and the dot carries the label for TalkBack.
- **`openspec/config.yaml` still describes the ADB clipboard grant as a workaround**, which BR-15
  disproved. It is prompt context rather than a shipped document, so it fell outside this item's
  "Done when" — flagged for **BR-12**.

### Second testing pass, same day

The first pass covered the "Done when" criteria and left several changed paths unexercised. A
second pass on the same S25 closed most of them, and found no new defects:

- **All three action types fire the right intent, and only their own row.** With Notification
  Access and Battery Optimization both disabled at once, the rows read
  `Notification Access — Disabled` / **ENABLE** and `Battery Optimization — Disabled` / **ALLOW**
  side by side, while Bluetooth and Notifications stayed Granted with no button. ENABLE opened
  `Settings$NotificationAccessSettingsActivity`; ALLOW opened
  `fuelgauge.RequestIgnoreBatteryOptimizations`; the Bluetooth row's GRANT opened
  `applications.InstalledAppDetails`. Granting any of them and returning flipped that row to
  Granted and removed its button, with no relaunch.
- **The Bluetooth row behaves correctly denied**, reading `Bluetooth — Disabled` with GRANT while
  the status card went on describing the *link* rather than the permission. With Bluetooth denied
  `.BridgeService` correctly did **not** run — the mirror of the notifications case, where it
  does.
- **Review finding 4's asymmetric case turns out to be unreachable.** `BLUETOOTH_ADVERTISE` and
  `BLUETOOTH_CONNECT` are both in the `NEARBY_DEVICES` permission group, so Android grants and
  denies them together: revoking only `CONNECT` and requesting it got it back **with no prompt at
  all**. The `all { shouldShowRequestPermissionRationale }` form is still the safer one and is
  kept, but the dead end it prevents cannot occur with today's two rows. It would matter again
  for any future row combining permissions from different groups.
- **Inline guidance verified in a second state and the dismissal reset verified across one.**
  With the phone's Bluetooth off the card read "Bridge needs Bluetooth to reach your Mac. Turn it
  on?" with a TURN ON BLUETOOTH action and no dialog anywhere in the hierarchy. Dismissing it and
  then turning Bluetooth back on — with the Mac app still dead — brought the guidance back with
  the *new* state's text, confirming a dismissal does not outlive the state it was made in.

**Still unexercised:** the `UNPAIRED` and `CONNECTING` guidance strings (both need a trust-store
state this test could not reach without destroying the pairing), and the branch that drops the
Notifications row below API 33 — unit-tested only, since the S25 is API 36.

### What the next item builds on

**BR-11** is the Mac mirror of this item and can copy the shape: a row that cannot reach Granted
should not be shown, and a status with no action behind it is the thing to remove. The Local
Network row it has to deal with is exactly that case.

**BR-7** gets a working home for Disconnect and Unpair: the status card now has a guidance line
and an action slot that render from link state, and `LinkState` is still the single place that
decides what the user is offered.

---

## BR-13: Mac — Notice a Scan That Never Returns Anything

Branch `work/br-13-notice-a-scan-that-never-returns`, shipped 2026-10-28.

### In plain terms

The Mac app looks for the phone by asking macOS to scan for it. It used to ask once and then
assume the scan was running forever. It had no way to tell "I am looking and the phone is not
here" apart from "I asked to look and macOS quietly stopped looking on my behalf".

That second case is real: on 2026-10-27 a second copy of the app quitting told the Bluetooth
daemon to stop scanning, which stopped the scan belonging to the copy that was still running.
That copy went on believing it was searching, showed "Out of range", and stayed there — with the
phone 30 cm away, advertising the whole time. The only fix was quitting the app or toggling
Bluetooth.

Now the app times its own scan. If twenty seconds pass with nothing found, it says so in the log,
stops the scan and starts a fresh one — so a scan that was killed behind its back comes back by
itself.

The panel also stopped overstating what it knows. For the first twenty seconds it says
**Searching…**, because looking is not the same as having looked. Only after a full period with
nothing found does it say "Out of range", which is now a conclusion rather than a guess.

### Technically

- `BLEManager` tracks its own `isScanning` and `searchingSince`. `CBCentralManager.isScanning`
  reports *this app's request*, not the daemon's behaviour — it stayed `true` throughout the
  observed failure, so it is the symptom and not the signal.
- A one-shot `Timer` per scan, `LinkHealthEvaluator.scanWatchdogPeriod` = 20 s, following
  `healthTimer`'s existing main-actor pattern. It is invalidated on discovery, on connect, in
  `disconnect()` and when Bluetooth goes away.
- `retrieveConnectedPeripherals(withServices:)` is called on `.poweredOn` and before each scan
  restart. A peripheral macOS already holds never advertises to this central, so scanning alone
  could not find it.
- `LinkHealth` gains `.searching`, applied only where `.outOfRange` previously came from
  `lastDisconnect` being nil or `.unknown`. A supervision timeout is evidence the phone left, so
  it still reports `.outOfRange` immediately. `evaluate` stays pure; `searchingSince` defaults to
  nil so every pre-existing case keeps its answer.
- No protocol change, no packet change, no Android change. Tier 1 only.

### Known issues and degradations

- **Criterion (c) is untested, not passed.** macOS released the peripheral on every app exit
  tried, including `SIGKILL`, so `retrieveConnectedPeripherals` returned empty on every launch
  and the non-empty branch has never executed. The call is made where the spec says; what is
  unverified is what happens when it returns something. No way to make macOS hold the peripheral
  across the app's death was found.
- **The restart loop runs every 20 s for as long as the phone is away.** Deliberate: `stopScan` +
  `scanForPeripherals` is negligible on a Mac on mains power, and a backoff is the bug — the
  failure being fixed is a user sitting in front of a phone 30 cm away.
- **The watchdog can restart a scan that was about to succeed.** Worst case one extra discovery
  delay, against a current worst case of never.
- **A connected-but-silent peripheral is deliberately out of scope.** `restartStalledScan`
  returns early while `peripheralConnected` is true, because reconnecting would reset the ping
  watchdog's silence clock and hide a dead phone service. That silence belongs to the ping
  watchdog, which reports "Not responding".

### Found while testing, and left for their owners

- **The device test caught a defect no unit test could.** Discovering the phone cleared
  `searchingSince`, so for the 676 ms between `didDiscover` and `didConnect` the panel reported
  "Out of range" about a phone it had just found at RSSI −79. The clock now runs until a
  peripheral actually connects. Fixed in `ad433bb`, re-observed clean across a 1.29 s connect.
- **Code review caught three more before hardware did**, the worst being that
  `restartStalledScan` called `startScanning()`, which reset `searchingSince` — so the state
  could never age out of `.searching` and "Out of range" was unreachable. Exactly the opposite of
  the spec row it was written for, and invisible to a unit test of the pure evaluator.
- **BR-14 is still the right next item.** Reproducing criterion (b) meant deliberately running
  two instances, which is the state BR-14 exists to prevent. This item makes the app *recover*
  from a dropped scan; it is not a substitute, since macOS can drop a scan for its own reasons —
  but the commonest cause is still unguarded.

### Second testing pass, same day

The first pass proved the *state machine* from the log and claimed criterion (d) on that basis.
That was an overclaim: the panel itself had never been looked at. A second pass captured it.

- **The panel really does distinguish the two.** Screenshotted in both states: *Searching…* with
  `dot.radiowaves.left.and.right` and **no guidance line**, against *Out of range* with
  `antenna.radiowaves.left.and.right` **and** "Phone is out of range. It will reconnect
  automatically when back in range." The two menu bar glyphs are visibly different at menu bar
  size — a dot with waves either side versus an antenna mast with waves either side.
- **`showsLastSeen` correctly suppresses the last-seen line while searching**, so a fresh scan
  shows no stale timestamp.

**Still unexercised, all three Core Bluetooth states this test could not create:** the
`retrieveConnectedPeripherals` non-empty branch; `abandon()`, which drops a peripheral whose
Bridge service is missing; and the `peripheralConnected` guard in `restartStalledScan`, which
needs a ping-watchdog timeout while the BLE connection survives — force-stopping the phone app
tears the connection down instead, so it reports `peerTerminated` and never reaches that path.
Two of those three are review fixes, so this change ships with two fixes that have only ever been
reasoned about.

### Also observed, for BR-11

The menu bar item's accessibility title resolved to a **neighbouring** item's name in both runs
that queried it — "Mobile Data" once and "Radio" the next — which is exactly the defect BR-11
records. Reproduced here twice, unprompted, while scripting the panel open.

### What the next item builds on

**BR-14** can now be judged on its own terms rather than as a bug fix: the surviving instance
already recovers, so a single-instance guard is about not reaching the state, not about escaping
it. Its fourth criterion — "the surviving instance never loses its scan to another instance
quitting" — is the one this item already demonstrated recovering from, with timestamps.

**BR-11** inherits a `LinkHealth` with eight states and a `StatusPanel` switch that the compiler
forces to handle all of them; a new state cannot be silently defaulted.
