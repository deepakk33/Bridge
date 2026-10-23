## Context

See proposal.md — Why. Current Android UI is built programmatically in `MainActivity.onCreate` as a `LinearLayout` of `TextView`/`Button`. The theme is `Theme.MaterialComponents.DayNight.NoActionBar` and `com.google.android.material:material:1.12.0` is already a dependency; `androidx.appcompat` is present, but no RecyclerView/ConstraintLayout dependency yet. The foreground-service notification is built in `BridgeService.buildNotification()` on an `IMPORTANCE_LOW` channel with app-name title and "Bridge active" text. The current launcher adaptive icon reuses `@drawable/ic_notification` as its foreground.

Link state is available from the BLE layer (`BlePeripheral`) and trust store (`TrustStore`), and pairing from `PairingManager` (iter-2). This change only presents and triggers them.

## Goals / Non-Goals

**Goals:**
- A clean, hierarchical main screen with a status card and grouped sections, using Material Views.
- One adaptive top-bar action that correctly reflects unpaired / disconnected / connected.
- Quietest allowed foreground-service notification.
- A real adaptive launcher icon separate from the monochrome status icon.

**Non-Goals:**
- Jetpack Compose (stay on Views for consistency).
- Any Mac-side UI or protocol change.
- New pairing/reconnect logic — this only surfaces and triggers iter-2 mechanisms.
- The notification-filter screen's internals (iter-6 owns it); iter-7 only defines the visual system it should match and the entry point to it.

## Decisions

**D1 — Move from programmatic Views to inflated layout XML.** Rewrite `MainActivity` to `setContentView(R.layout.activity_main)` with a `MaterialToolbar` + `MaterialCardView` status card + sections. Rationale: a card-based hierarchy is impractical to maintain in code; XML is the idiomatic path and keeps the diff reviewable.
- Alternative (keep programmatic Views): rejected — verbose and hard to style to Material spec.

**D2 — Smart connect action derives from a single `LinkState`.** Define `LinkState { UNPAIRED, DISCONNECTED, CONNECTED }` computed from `TrustStore` (has a trusted Mac?) + `BlePeripheral` connection status. The toolbar action and the status card both bind to it. On tap: `UNPAIRED` → `PairingManager` handshake; `DISCONNECTED` → BLE scan/advertise restart; `CONNECTED` → no-op.
- Alternative (separate Pair and Reconnect buttons): rejected — user asked for one connect action; a single adaptive control is cleaner and matches the three mutually-exclusive states.

**D3 — Observe state via existing signals, refresh in `onResume` + a lightweight listener.** Bind the action/card to link-state changes so the label flips live (spec: updates without reopening). Reuse whatever callback the BLE layer already exposes to `MainActivity`; fall back to an `onResume` refresh plus a short poll if no push signal exists. Keep it on the main thread (UI only).
- Alternative (full reactive StateFlow refactor of BLE layer): rejected as out of scope; use the minimal hook needed.

**D4 — Foreground notification to `IMPORTANCE_MIN`, content-less.** New `IMPORTANCE_MIN` channel (or lower the existing one), builder with only `setSmallIcon` + `setOngoing(true)`, no title/text. Rationale: `IMPORTANCE_MIN` removes the status-bar icon and collapses the entry; this is the quietest form Android permits for a required FGS notification.
- Note: changing an existing channel's importance after creation is ignored by Android, so use a new channel id to guarantee `MIN` takes effect on upgrade.

**D5 — New adaptive launcher icon as vector layers.** `mipmap-anydpi-v26/ic_launcher.xml` (+ `ic_launcher_round`) referencing a new `ic_launcher_foreground` vector drawable (a stylized bridge/link glyph) over a solid/branded background color. Keep `ic_notification` as the monochrome small-icon. Provide the icon so it renders without external assets.
- Alternative (raster PNG mipmaps): rejected — vector adaptive icon is smaller, crisper, themeable, and needs no multi-density export.

**D6 — Add `androidx.recyclerview` dependency.** The sections list (and iter-6's filter list) use RecyclerView. Add it here so both iterations share it.

## Risks / Trade-offs

- **`IMPORTANCE_MIN` channel change ignored on upgrade** → use a new channel id so existing installs get the minimized behavior. Mitigation baked into D4.
- **No push signal for link state** → label could lag a transition. Mitigation (D3): `onResume` refresh + reuse existing BLE callback; accept a short poll if needed.
- **`MainActivity` rewrite touches code iter-6 also edits** (adds a filter-screen entry point / button) → merge friction. Mitigation: land iter-7's shell first (or in the same branch) and have iter-6 hook into the new Notifications section rather than the old button.
- **Icon is a designed asset, not user-supplied** → the user may dislike the glyph. Mitigation: it is a vector, trivially swappable; the user chose "you pick a direction," and it can be iterated after they see it on-device.

## Migration Plan

- Additive and Android-only. On upgrade: new launcher icon replaces the old; new FGS channel supersedes the old (old channel can be deleted to avoid a stale duplicate in app settings).
- Rollback: revert `MainActivity`, `BridgeService` notification, icon resources, and the RecyclerView dependency; no persisted-data or protocol implications.
