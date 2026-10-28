## Context

See proposal.md — Why. The short version: three surfaces describe a grant Android 16 refuses,
and the script among them reports success for a no-op.

What constrains the approach:

- **BR-9 shipped two days ago and was device-verified.** `ClipboardBridge`, `ClipboardCardState`
  and the clipboard card strings are new, correct, and tested on an SM-S931B. This change must
  not disturb observable clipboard behaviour; it removes claims, not function.
- **`ClipboardBridge.hasBackgroundClipboardAccess` reads a constant that lives in the class being
  trimmed.** `PermissionStatusChecker.READ_CLIPBOARD_IN_BACKGROUND` has exactly two consumers: the
  permission row being deleted, and this gate. The gate must survive the row.
- **`PermissionStatus.LIMITED` has exactly one producer**, `adbClipboardGrant()`. Deleting the row
  leaves the enum case with no way to be produced.
- **BR-10 is queued behind this.** It will render `PermissionItem.detail` in the UI. Whatever this
  change leaves in `detail` is what BR-10 puts on screen.
- **No Mac code, no protocol, no transport.** Nothing here crosses the wire.

## Goals / Non-Goals

**Goals:**

- Every remaining mention of the ADB clipboard grant in code, scripts, specs and docs either
  describes it as unobtainable or is gone.
- The permission list contains only rows that can reach Granted through their own action.
- The removal is detectable by a test, not only by reading the diff, so the row cannot quietly
  come back.

**Non-Goals:**

- **Not restructuring the permission screen.** The monospace `joinToString` dump stays exactly as
  it is; replacing it with real rows is BR-10's entire scope and the two changes would collide.
- **Not changing clipboard behaviour.** Same two triggers, same cap, same card, same packets.
- **Not removing the 500 ms poll machinery.** See the decision below.
- **Not resolving the reconnect clip push.** That is BR-16, and the exception clause in
  `clipboard/sync` stays pointing at it.

## Decisions

### Move `READ_CLIPBOARD_IN_BACKGROUND` into `ClipboardBridge` rather than hardcoding the gate false

The permission constant has to go somewhere once `PermissionStatusChecker` loses its clipboard
row, and the gate is its last real consumer.

*Alternative considered — hardcode `hasBackgroundClipboardAccess = false`.* Rejected. It states a
platform fact as a compile-time constant, so a future Android that reopens the grant, or a build
on an older device where it was grantable, would still be told no by Bridge's own code. The
`checkSelfPermission` call is not the dishonest part; the row that offered a command to make it
pass was. Keeping the real query costs one line and stays true on every Android version.

*Alternative considered — delete the gate and the poll with it.* Rejected here, see below.

### Keep `start()`, `stop()` and the poll runnable

On Android 16 the gate is always false, so the 500 ms poller is unreachable code — which is the
pattern this repo's audit keeps flagging. It stays anyway, for this change:

- It is the mechanism that would work the moment `checkSelfPermission` returns granted, so it is
  unreachable on *this hardware*, not unreachable in principle. That is a different thing from
  `BLEManager.disconnect()` having zero callers.
- `MainActivity.updateClipboardStatus()` feeds `isWatching` into `ClipboardCardState`, and the
  card's `SYNCING` state is defined in terms of it. Deleting the poller collapses a four-state
  card to three and touches BR-9's device-verified UI.
- The battery defect BR-9 was filed for is already fixed: with the gate false, nothing is
  scheduled.

Recorded as the one piece of residue this change knowingly leaves.

### Delete `PermissionStatus.LIMITED` along with its only producer

With `adbClipboardGrant()` gone nothing can return `LIMITED`, and both remaining rows are binary.
An enum case no code can produce is the same class of debt as the row itself, and it is two lines.
`PermissionSummaryTest` is retargeted onto `DENIED`, which it should arguably have used anyway —
the behaviour under test is "a non-granted row is the first problem", not the specific status.

### Prove the removal with a test, not just a diff

A unit test asserts over `PermissionStatusChecker.all()` that no row's `name` or `detail` mentions
a clipboard grant or an `adb` command. This is what makes Done-when 1 and 4 checkable without a
device, and it is what stops the row reappearing when BR-10 rewrites this screen. It needs a
`Context`, so it is a Robolectric or instrumented test if the project has that wiring, and
otherwise a pure assertion over a hand-built list plus a source-level check — decided at
implementation time by what the Android test source set already supports.

### Correct the PRD's three-ways-out list rather than deleting it

`docs/PRD.md` presents three ways around the background clipboard block and picks option 1. The
list is still useful history — option 2 (a no-op IME) remains a real, deliberately rejected
option, and someone will ask again. The edit records that option 1 was tried and is closed by
Android 16's role management, and that option 3 is what ships. Deleting the list would lose the
reasoning and invite the same proposal next time.

### Delete the `clipboard/adb-grant` directory by hand after `openspec archive`

The delta removes every requirement in the capability. Archive's behaviour on a capability
reduced to zero requirements is not something this repo has exercised, so the archive step
verifies what is left at `openspec/specs/clipboard/adb-grant/` and removes the directory if the
tool leaves an empty shell. `clipboard/sync`'s Purpose line still names `clipboard/adb-grant`;
per the OpenSpec instructions a Purpose is edited in the main spec directly, so that is its own
task rather than part of the delta.

## Risks / Trade-offs

- **An unreachable 500 ms poller stays in the tree** → Documented above and in `ITERATIONS.md`.
  It costs nothing at runtime because the gate never opens, and removing it means touching
  BR-9's just-verified card states. If `SYNCING` is still unreachable after BR-10, the poller and
  the card state should go together.
- **Deleting the script removes the only record of what was tried** → The reason survives in three
  places that outlive the script: the `clipboard/adb-grant` delta's REMOVED reasons, the PRD's
  corrected option list, and `ITERATIONS.md`.
- **A capability deleted in `openspec/specs/` cannot be diffed against later** → It remains in git
  history and in the archived change folder, which is where every other removed requirement in
  this repo lives.
- **BR-10 is editing the same screen** → This change deliberately leaves the monospace dump alone
  and touches only what `all()` returns, so the conflict surface is one function. BR-10 should
  land after this, not beside it.
- **`appops reset com.bridge.app` was run on the test device while establishing the facts** →
  It resets every app op for the package to default. Nothing Bridge needs is an app op
  (Notification Access is a special permission, battery optimisation is a power-manager
  allowlist), but the device test re-checks both rows rather than assuming.

## Migration Plan

No data, no protocol, no persisted state is touched, so there is nothing to migrate and nothing
to roll back beyond `git revert`. An existing install that somehow had the row showing Limited
simply stops showing it after the APK is replaced.

## Open Questions

None. The three decisions the item asked for — delete all three surfaces, move the surviving
requirement into `clipboard/sync`, correct the PRD and `.claude/CLAUDE.md` — were taken with the
user on 2026-10-28 and are recorded in the BR-15 detail block in `docs/BACKLOG.md`.
