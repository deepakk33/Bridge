## Context

See proposal.md — Why. iter-3 mirrors every non-ongoing notification via `BridgeNotificationListener.onNotificationPosted`, which already resolves `sbn.packageName` and the app label before building the `bridge.notification` packet. This change inserts a forwarding gate in front of that send and adds a phone-side UI to manage it. No Mac, protocol, or transport change.

Relevant current state:
- `BridgeNotificationListener` ([android/.../notifications/BridgeNotificationListener.kt](../../../android/Bridge/app/src/main/java/com/bridge/notifications/BridgeNotificationListener.kt)) — already extracts `packageName` + label; the `packetSender?.invoke(packet)` call at the end is the single forwarding point to gate.
- DataStore is already a project dependency (used by `TrustStore`, `BlePeripheral`).
- App UI is plain programmatic AppCompat Views (`MainActivity`), no Jetpack Compose, no RecyclerView yet.

## Goals / Non-Goals

**Goals:**
- Synchronous allow-set lookup on the notification callback (no suspend / no blocking I/O there).
- Build the toggle list with zero new runtime permissions.
- Keep the gate independent of iter-3 so the mirror packet and Mac side are untouched.

**Non-Goals:**
- Per-app content hiding / privacy redaction (KDE Connect has it; deliberately out of scope — user chose minimal).
- Listing all installed apps / `QUERY_ALL_PACKAGES` path.
- Any Mac-side UI to view or change the filter. Phone owns it entirely.
- Fixing Android 15/16 OTP body redaction (documented as a known degradation).

## Decisions

**D1 — Deny-by-default allowlist.** Newly-seen apps are silent until explicitly enabled. Chosen over KDE Connect's default-allow because the driving goal is cutting Mac noise; an empty allow-set means a silent Mac, and the user opts in the few apps that matter.
- Alternative (default-allow + denylist): rejected — noise leaks from every new app until the user mutes it, the opposite of the goal.

**D2 — In-memory `StateFlow` mirror of persisted state.** `NotificationFilterStore` loads the allow-set, master-mute flag, and seen-apps set into a `MutableStateFlow<FilterState>` at init; the listener reads `.value` synchronously in `onNotificationPosted`. Writes (toggles) persist and update the StateFlow.
- **Implementation note (deviates from the original "DataStore" wording):** the repo does not depend on Jetpack DataStore — `TrustStore` and the rest of the app persist via `SharedPreferences`/`EncryptedSharedPreferences`. To follow the existing project pattern (CLAUDE.md: "follow existing code patterns"), the store persists via `SharedPreferences` instead. This satisfies the spec requirement equally: `SharedPreferences` reads are synchronous and served from an in-memory map after first load, so the hot-path read never blocks. The pure gate/state logic (`NotificationFilter.kt`) is split out from the Android persistence behind a small `FilterPersistence` interface so it is unit-testable on the plain JVM (the repo's test suite has no Robolectric).
- Alternative (read persistence in the callback): rejected — the listener callback must not block or launch a coroutine per notification; the `StateFlow.value` read is synchronous and allocation-free.

**D3 — Seen-apps list sourced from the notification stream.** On each `onNotificationPosted`, record `sbn.packageName` into the persisted seen-apps set. The settings screen lists exactly these packages. This needs no package-visibility permission because the listener already legitimately receives these `StatusBarNotification`s.
- Alternative (`getInstalledApplications` + `QUERY_ALL_PACKAGES`): rejected — adds a broad permission for marginal benefit (pre-toggling an app that has not notified yet); the user chose seen-apps-only.

**D4 — UI with RecyclerView in plain Views.** New `NotificationFilterActivity` launched from `MainActivity`, following the existing no-Compose pattern. RecyclerView of rows (icon, label, Switch) + a master-mute Switch header. Icons and labels resolved off the main thread (background executor / coroutine) and cached per package; bind from cache to keep scrolling smooth.
- Alternative (introduce Compose): rejected — inconsistent with the rest of the app for one screen; larger change surface.

**D5 — Gate placed at the single forwarding point.** The check (`master mute off && packageName in allow-set`) wraps the `packetSender?.invoke(packet)` call, after iter-3's existing ongoing/foreground/group-summary skips. Seen-app recording happens before the gate so even dropped apps become toggleable.

## Risks / Trade-offs

- **Seen-apps list starts empty on fresh install** → user sees nothing to toggle until apps notify. Mitigation: brief in-screen hint ("apps appear here after they notify"); acceptable given the 1-metre single-user private context.
- **DataStore write latency on rapid toggles** → UI could lag reality. Mitigation: StateFlow updates optimistically; DataStore write is fire-and-forget from the toggle handler.
- **Listener process killed by One UI** (same risk as iter-3/5) → filter state is persisted, so it restores on rebind; no new exposure here. Covered by iter-5 watchdog/battery-exemption work.
- **OTP/2FA bodies redacted on Android 15/16** → an allowed app's OTP mirrors with hidden body. Documented degradation, out of scope to fix (requires signature/role permission unavailable to a sideloaded app).

## Migration Plan

- Additive only. On first run after update, DataStore keys are absent → allow-set empty, master-mute off, seen-apps empty. Deny-by-default means the Mac goes quiet until the user allows apps — this is the intended behavior and should be called out in the iter-6 report so it is not mistaken for a regression.
- Rollback: reverting the gate restores iter-3 forward-everything behavior; persisted filter keys are simply ignored.
