# Bridge — Backlog

The work queue. This file replaces a ticket tracker: `/work` reads it, and it is the only
place a work item is born. One row per item, one detail block per row.

## Rules

- **ID**: `BR-<n>`, next free number, never reused.
- **Status**: `idea` → `ready` → `in-progress` → `review` → `done`. Only `ready` items can start.
- **Branch**: `work/<id-lowercase>-<kebab-title>`, filled in when work starts.
- An item is `ready` only when its detail block has a one-line **Scope**, a **Why**, and
  **Done when** criteria that can be checked on real hardware.
- `/work` keeps Status and Branch current. Edit Scope/Why/Done-when by hand, any time.
- `done` rows stay for ~10 items, then move to `docs/ITERATIONS.md` and drop out of here.

## Queue

| ID | Title | Status | Branch |
|----|-------|--------|--------|
| BR-1 | Grey out the app list while mirroring is paused | done | `work/br-1-mute-greys-app-list` |

## Items

### BR-1 — Grey out the app list while mirroring is paused

**Scope**: when the master "Pause mirroring to the Mac" switch is on, dim the per-app list to
40% and disable its switches, since mute overrides every per-app choice.
**Why**: the per-app switches stay bright and tappable while paused, so the screen reads as if
those apps are still being mirrored. The work already exists, hardware-verified, on the stale
branch `worktree-fix+mute-greys-applist` (`2a9119a`) — but that commit also inverts the switch
polarity to ON = block, which contradicts the allow-list model and the copy shipped in
`0c31a23`. Port the greying only.
**Platforms**: android
**Done when**:
- [x] with the master switch ON, every app row is dimmed and its switch cannot be toggled
- [x] turning the master switch OFF restores full opacity and tappable switches, same screen,
      no reload
- [x] opening the screen while already paused shows the dimmed state immediately
- [x] per-app switch polarity is unchanged — ON still means "mirror this app"
**Notes**: `adapter.setMuted()` must not read `store.isMuted()` at adapter construction — the
store lazily touches `applicationContext` before the Context is attached and the activity
crashes. Default the field false and apply the real value in `loadApps()`. The polarity
inversion and the empty-hint rewrite from `2a9119a` are deliberately dropped.

<!-- Template — copy for each new item:

### BR-<n> — <title>

**Scope**: one line, what changes.
**Why**: one line, the problem it solves.
**Platforms**: mac | android | both | protocol
**Done when**:
- [ ] criterion checkable on a MacBook Air M3 + Galaxy S25
- [ ] criterion
**Notes**: links to PRD sections, prior iterations, known constraints.

-->
