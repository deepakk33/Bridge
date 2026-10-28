## 1. Android — the 10 KB cap (pure)

- [ ] 1.1 Write `ClipboardCapTest` in `app/src/test/java/com/bridge/clipboard/` covering: text
      under the cap passes through unchanged with `truncated = false`; text over the cap comes
      back at or under 10 240 UTF-8 bytes with `truncated = true` and the true `originalBytes`;
      a cut landing inside a multi-byte character (CJK) and inside a surrogate pair (emoji)
      falls back to the preceding character boundary and the result re-encodes without a
      replacement character; empty string is handled. Verify: `./gradlew test` fails on the
      missing class.
- [ ] 1.2 Add `ClipboardCap` (`apply(text: String): Capped`, `Capped(text, originalBytes,
      truncated)`, `MAX_BYTES = 10_240`) in `com.bridge.clipboard`. Verify: `./gradlew test`
      passes 1.1.

## 2. Android — route every send through the cap

- [ ] 2.1 Add `SendOutcome(originalBytes: Int, truncated: Boolean)` and
      `var lastSend: SendOutcome?` to `ClipboardBridge`; have `pollClipboard`,
      `sendCurrentClipboard` and `sendClipboardFromActivity` call `ClipboardCap.apply` before
      `BridgePacket.clipboard(...)` and set `lastSend` (null-out on an untruncated send).
      Verify: `./gradlew assembleDebug` compiles and no `BridgePacket.clipboard(` call in
      `ClipboardBridge.kt` takes unmeasured text (`grep -n 'BridgePacket.clipboard(' ` shows
      every call site taking a `Capped`).
- [ ] 2.2 Cap `makeConnectPacket()` the same way, so the clip offered on reconnect cannot
      exceed the cap either. Verify: compiles; `grep` as in 2.1 covers this call site too.
- [ ] 2.3 Route `ClipboardTileService.onClick` through `ClipboardCap.apply` as well — it builds
      its own packet and bypasses `ClipboardBridge`. Verify: `grep -n 'BridgePacket.clipboard('
      android/Bridge/app/src/main/java/com/bridge/clipboard/ClipboardTileService.kt` shows the
      capped text, and `./gradlew assembleDebug` passes.

## 3. Android — gate the poll on the grant

- [ ] 3.1 Make `ClipboardBridge.start()` return early with a single log line when
      `hasBackgroundClipboardAccess` is false, scheduling nothing. Verify: `./gradlew
      assembleDebug`, then on the device with the grant absent, `adb logcat -s ClipboardBridge`
      over 60 s shows the one "poll not started" line and no polling activity.

## 4. Android — stop the silent send

- [ ] 4.1 Remove `sendClipboardFromActivity(this)` from `MainActivity.onWindowFocusChanged`,
      keeping `flushPendingRemote()`. Verify: `grep -n 'sendClipboardFromActivity'
      android/Bridge/app/src/main/java/com/bridge/app/MainActivity.kt` returns only the
      `sendClipboardToMac()` button path.

## 5. Android — the clipboard card

- [ ] 5.1 Write `ClipboardCardStateTest` covering the precedence from design §3: link down
      beats grant absent beats truncated beats all-well, and each case's `isWarning`. Verify:
      `./gradlew test` fails on the missing class.
- [ ] 5.2 Add pure `ClipboardCardState.of(hasGrant, isLinkUp, lastSend): CardState` in
      `com.bridge.clipboard`. Verify: `./gradlew test` passes 5.1.
- [ ] 5.3 Move the clipboard card strings into `res/values/strings.xml` (one per card state,
      the truncation one taking a size format arg) and the `#FF9800` literal into
      `res/values/colors.xml`. Verify: `grep -n 'FF9800\|Manual sync'
      android/Bridge/app/src/main/java/com/bridge/app/MainActivity.kt` returns nothing.
- [ ] 5.4 Rewrite `MainActivity.updateClipboardStatus()` to call `ClipboardCardState.of(...)`
      and apply `messageRes`/`formatArg`/`isWarning`, and call it from the existing link-state
      refresh as well as `onResume`. Verify: `./gradlew test assembleDebug` passes.

## 6. Spec and build verification

- [ ] 6.1 Run `openspec validate --change br-9-clipboard` and fix anything it reports.
- [ ] 6.2 Run `(cd android/Bridge && ./gradlew test assembleDebug)` and read the output — all
      green, no new warnings in the touched files.

## 7. Manual device test (Galaxy S25 + MacBook Air M3)

- [ ] 7.1 With the ADB grant **absent** (`adb shell pm revoke com.bridge.app
      android.permission.READ_CLIPBOARD_IN_BACKGROUND`), start the service and watch
      `adb logcat -s ClipboardBridge` for 60 s: one "poll not started" line, nothing else.
      Covers "no clipboard polling occurs when background access is denied".
- [ ] 7.2 Copy a distinctive string on the phone, note the Mac clipboard, then open the Bridge
      app and bring it to the foreground. The Mac clipboard is unchanged. Then tap "Send
      Clipboard to Mac" and confirm the Mac can paste it. Covers "opening the app does not send
      the clipboard without the user asking".
- [ ] 7.3 Copy ~40 KB of text on the phone (a long file via a text editor), tap send, and
      confirm: the Mac pastes roughly the first 10 KB; the phone card reports the truncation
      and the original size; and a notification posted on the phone immediately after reaches
      the Mac without a multi-second stall. Covers "a clip over the cap does not block the
      control channel".
- [ ] 7.4 Walk the card through all four states — link down, grant absent, truncated, all well
      (grant re-applied via `scripts/adb-grant-clipboard.sh`) — and confirm it is amber only in
      the first three. Covers "the clipboard card changes with state and is amber only when
      something is wrong".
- [ ] 7.5 With the grant **applied**, copy on the phone with the app backgrounded and confirm
      the clip reaches the Mac within ~1 s — the gate must not have broken the granted path.
- [ ] 7.6 Record each of 7.1–7.5 as pass/fail with what was observed, in the review gate report.
