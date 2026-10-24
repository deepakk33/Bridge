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
| —  | _(empty — add a row and a detail block below)_ | | |

## Items

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
