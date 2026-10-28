## Context

See `proposal.md` — Why. The constraints that shape the approach:

- `ClipboardBridge` holds a `Handler`-based 500 ms poll, a remote-write echo guard, a pending
  remote clip, and five send paths that each build a packet independently (`pollClipboard`,
  `sendCurrentClipboard`, `sendClipboardFromActivity`, `makeConnectPacket`, and
  `ClipboardTileService.onClick`, which bypasses `ClipboardBridge` entirely and calls
  `BridgePacket.clipboard(text)` itself).
- `hasBackgroundClipboardAccess` reads `AppOpsManager.unsafeCheckOpNoThrow("android:read_clipboard")`,
  which is an app-op check and is cheap — but it is not the same signal as
  `PermissionStatusChecker.adbClipboardGrant()`, which does `checkSelfPermission(READ_CLIPBOARD_IN_BACKGROUND)`.
  Two ways of asking the same question already exist in the codebase.
- The Android unit-test source set is plain JVM JUnit with no Robolectric (see
  `app/src/test/java/com/bridge/`), so anything that needs a `Context` cannot be tested.
  `PermissionSummary` in `permissions/` is the project's existing answer: push the logic into
  a pure object and leave the Context-touching shell untested.
- `MainActivity.updateClipboardStatus()` is already called from `onResume`, and
  `refreshLinkUi()` already runs on every link-state callback — both are hooks the card can
  reuse without new plumbing.

## Goals / Non-Goals

**Goals:**
- One place that decides what text is allowed to leave the phone, reached by every send path.
- One place that decides what the clipboard card says, testable without a device.
- Both pure, so BR-9's behaviour is covered by JVM unit tests and only the wiring needs hardware.

**Non-Goals:**
- Out-of-band transfer for clipboard (rejected in the proposal — it would pull BR-8's
  unauthenticated `PayloadServer` port into the clipboard path).
- Reconciling `hasBackgroundClipboardAccess` with `PermissionStatusChecker.adbClipboardGrant()`
  into a single signal. They disagree in principle (app-op vs. permission) and unifying them
  is BR-10's permission-row work, not this item's.
- Any Mac-side change. The Mac keeps accepting whatever text arrives; it never sees a flag
  saying the clip was cut.
- Changing the echo guard, the reconnect timestamp protocol, or the pending-remote flush.

## Decisions

### 1. The cap is a pure helper, not a check at each call site

Add `ClipboardCap` in `com.bridge.clipboard`: `fun apply(text: String): Capped`, where
`Capped` carries the text to send, the original UTF-8 byte count, and whether it was cut.
Every send path calls it; no path builds a `BridgePacket.clipboard(...)` from raw text.

*Why not a check in each of the five paths*: five copies of a boundary rule is how one of
them gets missed, and `ClipboardTileService` is already the path that has drifted (it never
goes through `ClipboardBridge`). A pure function also unit-tests without a Context.

*Boundary rule*: the cap is 10 240 **bytes** of UTF-8, but the cut is made at a **character**
boundary — walk the string accumulating each character's UTF-8 length and stop before the one
that would exceed the cap. Measuring in bytes is what bounds the line actually written to the
channel; cutting on characters is what keeps the Mac from decoding a replacement character.
A naive `text.take(10240)` would do neither correctly.

*Alternative considered*: cap by `String.length`. Rejected — a clip of CJK or emoji is up to
4× its character count in bytes, so a character cap does not bound the line.

### 2. Truncation is reported through state, not a toast

`ClipboardBridge` gains `var lastSend: SendOutcome?` (set by every send path, including the
reconnect clip and the Quick Settings tile — the tile sends through `sendClipboardFrom`
rather than building its own packet, so it both sets and clears the outcome) — the capped byte count, the original
byte count, and the timestamp. A send that fits clears it to `null`. `MainActivity` reads it
in `updateClipboardStatus()`.

*Why not a toast*: the tile and the background poll can both send while no Activity is on
screen, so a toast is either impossible or fires from the wrong place. The card is already
re-read on `onResume` and link change, and it is where the user looks for clipboard state.

### 3. The card is a pure state mapping

Add `ClipboardCardState` (pure, like `PermissionSummary`):

```
data class CardState(val messageRes: Int, val formatArg: String?, val isWarning: Boolean)
fun of(hasGrant: Boolean, isLinkUp: Boolean, lastSend: SendOutcome?): CardState
```

Precedence, highest first: link down → last send truncated → no watcher running → all well.
Truncation outranks the manual-sync notice because the manual send button is *where most
truncated sends come from*, so ordering it below would make the truncation unreportable in
the default (un-granted) configuration. The Activity maps the returned `Kind` to a string
resource in a four-line `when`, then sets text and colour — the mapping stays out of
`ClipboardCardState` so the pure helper's unit tests need no generated `R` class.

*Why precedence rather than a combined message*: a card that says three things at once is the
monospace permission dump BR-10 is removing. One sentence, the most actionable one.

*Colour*: amber (`?attr/colorError`-adjacent — keep the existing `#FF9800` constant, moved to
`colors.xml`) only when `isWarning`; otherwise the theme's secondary text colour. The
hard-coded `Color.parseColor` call leaves `MainActivity`.

### 4. The poll gate reads the app-op, and `start()` becomes idempotent-with-re-check

`start()` returns early (logging once) when `hasBackgroundClipboardAccess` is false. The
existing `if (!polling)` guard stays, so repeated `start()` calls are still safe, and a grant
applied by ADB after install takes effect the next time the service starts — which is what
the ADB grant script already instructs.

*Alternative considered*: re-check the app-op inside `pollRunnable` every tick so a mid-session
grant starts sync with no restart. Rejected — it re-introduces the per-tick work the item is
removing, to serve a one-time setup step that already ends in a service restart.

### 5. The focus send is deleted, not made conditional

`onWindowFocusChanged` keeps `flushPendingRemote()` and drops `sendClipboardFromActivity(this)`.
`sendClipboardFromActivity` itself stays — the "Send Clipboard to Mac" button is its only
remaining caller and that is the explicit trigger the spec requires.

### Key flows

Service start:

```
BridgeService.onCreate
  └─ ClipboardBridge.start()
       ├─ hasBackgroundClipboardAccess == false
       │    └─ log "no background clipboard grant; poll not started"   [nothing scheduled]
       └─ hasBackgroundClipboardAccess == true
            └─ handler.postDelayed(pollRunnable, 500ms)                [as today]
```

Explicit send of an oversized clip:

```
User taps "Send Clipboard to Mac"  (or the QS tile)
  └─ read primaryClip  ──▶ 42 KB of text
       └─ ClipboardCap.apply(text)
            └─ Capped(text = first 10 240 bytes on a char boundary,
                      originalBytes = 43 008, truncated = true)
                 ├─ sendPacket(BridgePacket.clipboard(capped.text))   ──▶ Mac pastes the head
                 └─ lastSend = SendOutcome(truncated = true, 43 008)
                      └─ next updateClipboardStatus()
                           └─ ClipboardCardState.of(...)
                                └─ "Last clip was 42 KB — sent the first 10 KB"  [amber]
```

Opening the app (the removed path):

```
onWindowFocusChanged(hasFocus = true)
  ├─ flushPendingRemote()        [kept — writes TO the phone]
  └─ (sendClipboardFromActivity) [removed — no packet leaves the phone]
```

## Risks / Trade-offs

- **A truncated clip pastes as plausible-looking partial text on the Mac, and the Mac says
  nothing.** → The phone card reports it, and the truncation is at the end, so the user sees
  the paste is short. A `truncated` flag in the packet body would be the real fix; it is a
  protocol change and belongs with BR-12, which is already reading packet fields nobody reads.
  Recorded there rather than smuggled in here.
- **With the ADB grant active, the background poll still ships every copy — including a
  password manager's paste buffer — to a plaintext channel.** → Unchanged by this item, and
  by definition what the grant asks for. BR-4 is the channel fix. This item removes the
  *un-granted* leak (opening the app), which no one opted into.
- **10 KB is a guess at "does not block the channel", not a measurement.** → It is the PRD's
  own threshold, so it is at least consistent with the file-transfer rule. If a 10 KB clip
  still stalls a ping on real hardware, the device test will show it and the constant is one
  line to lower.
- **Deleting the focus send removes a convenience some muscle memory may rely on.** → The
  button sits on the same screen that focus-send fired from, so the replacement is one tap
  away and visible, and the Quick Settings tile does it without opening the app at all.
- **`hasBackgroundClipboardAccess` (app-op) and `adbClipboardGrant()` (permission) can
  disagree**, so the card could claim a grant the poll does not act on, or vice versa. → The
  card does not read the grant at all. It reads `ClipboardBridge.isWatching` — whether a
  watcher is *actually running* — so a grant applied after the service started reads as
  manual (which it is) until the restart the card's own copy asks for. The permission-checker row may still disagree with the card; that row is
  BR-10's and the device test will note it if it shows.

## Migration Plan

No data, no protocol, no stored state. Deploy is the normal sideload. Rollback is reverting
the branch — the only persistent artefact is the ADB grant, which this change reads and never
writes.
