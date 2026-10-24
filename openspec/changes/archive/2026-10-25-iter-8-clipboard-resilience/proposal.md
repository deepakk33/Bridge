## Why

After a day of real use three problems surfaced. (1) Background clipboard sync stopped
working: the iter-4 ADB grant (`pm grant READ_CLIPBOARD_IN_BACKGROUND`) silently broke
on the Android 16 / One UI 8.5 update, where that permission became *role-managed* and
is no longer grantable to a sideloaded app (shell `pm grant` and Shizuku both fail;
only root can). (2) The phone kept showing "Connected" after the Mac had gone away,
because a silently-dead socket leaves `readLine()` blocked until the 90 s watchdog. (3)
The foreground service was killed when the app was swiped from recents.

## What Changes

**Clipboard (OS regression, best-effort fix):**
- Accept that automatic background clipboard R/W is impossible on Android 16 without
  root. An invisible `SYSTEM_ALERT_WINDOW` overlay (KDE Connect's approach) was built
  and empirically rejected on One UI 8.5 — a non-focusable overlay does not satisfy the
  Samsung ClipboardService focus check — and reverted.
- `ClipboardBridge` now caches the last Mac clip when a background write is denied and
  re-applies it from the Activity context on focus (`flushPendingRemote`, wired into
  `MainActivity.onWindowFocusChanged`). The Quick Settings tile remains the phone→Mac
  manual path. Clipboard status text states the manual reality.

**Link handshake on open:**
- `L2capServer.probeLink()` sends a ping and closes the socket if no packet returns
  within 3 s, so a dead link drops to Advertising and reconnects instead of showing a
  stale "Connected". Invoked from `MainActivity.onResume` when a socket is open.

**Service persistence:**
- `android:stopWithTask="false"` on `BridgeService` so swiping the app from recents no
  longer kills the link (already `START_STICKY`, `connectedDevice` FGS, battery-exempt).

## Capabilities

### New Capabilities

- **`link/handshake`** — on-demand link-liveness probe (ping + 3 s timeout) that drops a
  dead link and triggers reconnect, invoked when the app is opened.
- **`service/persistence`** — the foreground service survives task removal from recents.

### Modified Capabilities

- **`clipboard/sync`** — adds the Android 16 background-access limitation and the
  flush-on-focus behavior for Mac→phone clips received while backgrounded.

## Impact

- No automatic background clipboard on Android 16 without root. Manual paths (QS tile,
  app-open flush + send) are the ceiling. `scripts/adb-grant-clipboard.sh` is retained
  but ineffective on Android 16.
- Handshake probe uses a fixed 3 s timeout; a very slow link could false-drop once on
  open and then auto-reconnect.
