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
- **`scripts/adb-grant-clipboard.sh` is now ineffective** on Android 16 (left in place;
  it reports success for READ_LOGS/appops but those no longer unblock background R/W).
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
