## Why

The Android clipboard path spends battery doing nothing and sends the clipboard without
being asked. `ClipboardBridge.start()` posts a 500 ms main-thread poll for the life of the
foreground service, while `hasBackgroundClipboardAccess` — which decides whether that poll
can read anything at all — is computed, logged once, and never consulted. On a device
without the ADB grant the poll wakes the main looper twice a second forever and `pollClipboard()`
reads `null` every time. That is the PRD's "tool gets uninstalled" risk (PRD §Risks, battery
cost) paid for zero function.

At the same time `MainActivity.onWindowFocusChanged` calls `sendClipboardFromActivity()`, so
merely opening the app ships whatever is on the clipboard — a password, an OTP, a card number —
to the Mac over a control channel that is still plaintext until BR-4. Nothing on screen asks
first. And no cap exists anywhere on clipboard text, so a multi-megabyte clip is written as a
single JSON line into the BLE L2CAP control channel (Tier 1), head-of-line blocking pings,
notifications and file metadata behind it for minutes — the PRD's out-of-band rule
(PRD §Protocol, "Large payloads go out of band", 10 KB) is not applied to clipboard at all.

## What Changes

- **Gate the poll on the grant.** `ClipboardBridge.start()` starts the 500 ms poll only when
  `hasBackgroundClipboardAccess` is true. Without the grant it logs why and starts nothing;
  the tile and the in-app button remain the phone→Mac path. `start()` becomes re-callable so
  a grant applied later takes effect on the next service start.
- **BREAKING (user-visible): opening the app no longer sends the clipboard.** The
  `sendClipboardFromActivity()` call is removed from `onWindowFocusChanged`; the pending
  Mac→phone `flushPendingRemote()` stays, since that writes *to* the phone and leaks nothing.
  Phone→Mac becomes deliberate only: the existing "Send Clipboard to Mac" button and the
  Quick Settings tile.
- **Cap clipboard text at 10 KB.** Every phone→Mac send path (poll, button, tile,
  `sendCurrentClipboard`, `makeConnectPacket`) truncates UTF-8 text to 10 240 bytes on a
  character boundary before building the packet, and the truncation is reported to the user
  rather than silent. Out-of-band transfer for oversized clips was considered and rejected:
  it would pull BR-8's unauthenticated `PayloadServer` port into the clipboard path.
- **Make the clipboard card tell the truth.** `updateClipboardStatus()` currently hard-codes
  one orange string. It becomes a function of state — grant present/absent, link up/down,
  last send truncated — amber only when something is wrong, and all its strings move to
  `strings.xml`.

Observed while writing this: the audit's "Copied." toast no longer exists in
`MainActivity` — the silent send is real, the misleading toast is already gone. The
criterion is unaffected.

**Transport tiers**: Tier 1 (BLE L2CAP control channel) only. No tier change; the cap is
what keeps Tier 1 responsive.

**Permissions**: Android — `READ_CLIPBOARD_IN_BACKGROUND` (ADB grant, read-only check via
`AppOpsManager.unsafeCheckOpNoThrow`), no new permission requested. Mac — none.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `clipboard/sync`: "Android 16 blocks automatic background clipboard access" is replaced —
  the background poll becomes conditional on the grant, and its "Opening the app sends the
  current phone clip" scenario is dropped. Two new requirements: an explicit-trigger rule for
  phone→Mac sends, and a 10 KB cap on phone→Mac text.
- `clipboard/adb-grant`: the "UI indicates degraded state" requirement gains the real
  state-dependent card, and the degraded-mode requirement states that no polling runs
  without the grant.

## Impact

- `android/Bridge/app/src/main/java/com/bridge/clipboard/ClipboardBridge.kt` — grant gate in
  `start()`, cap in the send paths, remove `sendClipboardFromActivity`'s focus caller.
- `android/Bridge/app/src/main/java/com/bridge/clipboard/ClipboardTileService.kt` — cap.
- `android/Bridge/app/src/main/java/com/bridge/app/MainActivity.kt` — drop the focus send,
  rewrite `updateClipboardStatus()`.
- `android/Bridge/app/src/main/res/values/strings.xml` — clipboard card strings.
- `android/Bridge/app/src/test/java/com/bridge/clipboard/` — new unit tests for the cap and
  the card-state mapping (both pure, no Context needed).
- No Mac change, no protocol change, no new packet type, no new dependency.
