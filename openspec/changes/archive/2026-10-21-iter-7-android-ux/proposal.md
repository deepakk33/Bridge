## Why

The Android app's shell is rough: the launcher/app icon reuses the monochrome notification glyph, the main screen is a plain vertical stack of `TextView`s and `Button`s with no visual hierarchy, there is no obvious way to trigger a connection to the Mac, and the always-on foreground-service notification shows an unnecessary "Bridge active" line that clutters the shade. The user wants a cleaner app, a real app icon, a top-bar action to connect to the Mac, and a quieter persistent notification.

Relevant PRD section: Android app ("foreground service, must be obviously running and controllable"). No transport tier change — the smart connect action drives the existing BLE pairing/reconnect machinery from iter-2. Android-only; no Mac or protocol change.

## What Changes

- **Minimize the foreground-service notification.** Lower the channel to `IMPORTANCE_MIN`, drop the title/text content, keep only a small icon. The notification stays (Android requires one for a `connectedDevice` foreground service) but collapses silently to the bottom of the shade. **Note:** fully removing it is not possible without dropping the background link; this is the quietest allowed form.
- **Smart "Connect to Mac" action in the top app bar.** Add a `MaterialToolbar` (the theme is `NoActionBar`) with a single adaptive primary action whose label and behavior follow link state:
  - No trusted Mac paired → **Pair Mac** (starts the iter-2 pairing/trust handshake).
  - Paired but disconnected → **Reconnect** (kicks a fresh BLE scan/advertise; auto-reconnect still runs in the background).
  - Connected → **Connected** (non-actionable status).
- **Redesigned main screen.** Replace the raw `LinearLayout` of labels with a Material card-based layout: a prominent status card (app glyph, connection-state pill, paired Mac name, last-seen time) and grouped sections for Notifications (entry point to the iter-6 filter screen), Clipboard status, and Permissions. Material Components (already a dependency) via Views — no Jetpack Compose, consistent with the existing codebase.
- **New app icon.** A purpose-designed adaptive launcher icon (distinct foreground vector + background layer) replacing the current reuse of `ic_notification`. Keep a monochrome notification-status icon separate.

## Capabilities

### New Capabilities
- `ui/android-shell`: The Android app's main screen, top-bar connect action, launcher icon, and the minimized foreground-service notification — the observable shell behavior of the app.

### Modified Capabilities
<!-- None. No existing capability's requirements change. The smart connect
     action drives the existing link/reconnect and protocol/pairing capabilities
     but does not alter their contracts. -->

## Impact

- **Android code:**
  - `app/BridgeService.kt` — lower notification channel to `IMPORTANCE_MIN`, strip notification content to icon-only.
  - `app/MainActivity.kt` — full rewrite of the view layer: `MaterialToolbar` + connect action, status card, grouped sections. Wire connect action to pairing (`PairingManager`) / reconnect (BLE restart).
  - New layout XML resources (card-based main screen) — move off programmatic Views to inflated layouts.
  - New launcher icon resources: `mipmap-anydpi-v26/ic_launcher.xml`, `ic_launcher_foreground` vector, background color/drawable; keep `ic_notification` for the status icon.
  - Possible new dependency: `androidx.recyclerview` (also needed by iter-6) and/or `androidx.constraintlayout` for the layout.
- **Mac code:** none.
- **Protocol:** none — no new or changed packet types.
- **Transport tiers:** none — reuses existing BLE link, iter-2 pairing/reconnect.
- **Permissions:** no new permissions.
- **Cross-iteration:** the iter-6 notification-filter settings screen should adopt the same visual system defined here; order iter-7 design ahead of or alongside iter-6 UI work.
