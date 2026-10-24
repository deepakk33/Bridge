---
description: Drive a backlog item through the Bridge development pipeline
argument-hint: "[BR-n] [--redo <phase>]"
allowed-tools: Read, Write, Edit, Bash, Skill, Agent
---

User input: `$ARGUMENTS`

# /work — Bridge pipeline orchestrator

You **are** the orchestrator, running in the main conversation loop. You drive one backlog item
from `docs/BACKLOG.md` to a pushed branch. You run every gate yourself — a subagent cannot pause
for a human.

## Standing rule — disk is the state

There is no state file. **Re-derive the phase from disk at the top of every invocation and before
every gate** (long sessions compact; conversation memory is not state). Everything already on disk
is done and is never redone:

| Signal on disk | Means |
|---|---|
| `docs/BACKLOG.md` row Status / Branch | the item's declared phase and branch |
| branch `work/<id>-<slug>` exists | clarify + branch phase done |
| `openspec/changes/<change>/proposal.md` | proposal authored |
| `openspec/changes/<change>/tasks.md` | planning artifacts complete |
| unchecked `- [ ]` in `tasks.md` | implementation still open |
| `openspec/changes/archive/*<change>` | item complete |

`<change>` = `<id-lowercase>-<kebab-title>` (same slug as the branch, no `work/` prefix).
`--redo <phase>` re-runs one phase (`clarify|propose|implement|review|wrap`); it never deletes
committed work — to re-author artifacts, delete the change folder by hand first.

Timestamps: `date -u +%Y-%m-%dT%H:%M:%SZ`. Never hand-write a date.

## Gate protocol (every human gate)

Three gates, no more: **plan**, **review**, **hand-off**. At each one:

1. Print every artifact under review as a markdown link with an **absolute** path
   (`ABS="$(git rev-parse --show-toplevel)"`), one line each plus a one-line summary.
2. Best-effort auto-open, fire-and-forget, all paths in one call:
   ```bash
   if command -v code >/dev/null; then code -g <abs-path>… || true; elif [ -n "$EDITOR" ]; then "$EDITOR" <abs-path>… </dev/null || true; fi
   ```
   `if`/`elif` not `||` chaining (a failed `code` must not launch vim); `</dev/null` so a terminal
   editor cannot grab stdin and deadlock the gate; `|| true` so auto-open never fails a gate.
3. Ask the gate question. Never print file contents or diffs inline.

Feedback at a gate never advances it — fix, re-present, ask again.

## Step 0 — No argument: status

With no `BR-n`, print the Queue table from `docs/BACKLOG.md` with each row's real phase
(derived from disk, per the table above) beside its declared Status, flag any mismatch, and stop.

## Step 1 — Load the item

Read `docs/BACKLOG.md`. Resolve `BR-n` to its row and detail block. Then:

- No such ID → list the open IDs and stop.
- Status `idea`, or the detail block is missing **Scope**, **Why** or **Done when** → go to Step 2.
- Status `done` or an archived change exists → say so and stop (`--redo` to reopen).
- Otherwise route to the first phase whose disk signal is absent.

Never start an item whose `Done when` criteria cannot be checked on real hardware.

## Step 2 — Clarify (only if the item is thin)

Invoke `superpowers:brainstorming`. Binding overrides:

1. **Scope = clarify the requirement.** No code scan, no implementation plan — the proposal phase
   owns that.
2. **Output = the item's detail block in `docs/BACKLOG.md`**, not a separate doc. Fill Scope, Why,
   Platforms, Done when, Notes.
3. **Terminal override**: on approval, do not invoke `writing-plans` or any implementation skill.
   Return here.
4. **Nothing stays open.** Resolve every question with the user (`AskUserQuestion` for choices)
   before writing. A deferral is only valid if the user explicitly defers it, and it is recorded
   under Notes. Record each answer, verbatim where the user typed free text.

Then set the row Status to `ready` and continue.

## Step 3 — Branch

Never work on `main`. Create `work/<id-lowercase>-<kebab-title>` from current `main`
(`git switch -c`), or reuse it if it exists. Add a worktree under `.claude/worktrees/` **only**
when Mac and Android work will run in parallel; otherwise a plain branch. Write the branch name
into the item's row, commit that row (`chore(backlog): start BR-n`), set Status `in-progress`.

## Step 4 — Propose → **plan gate**

Skip if `tasks.md` already exists.

Invoke `openspec-propose` for change `<change>`. The item's detail block is the input; the
`rules` in `openspec/config.yaml` (PRD reference, transport tiers, permissions, per-platform specs,
packet types, <4h tasks, device-test tasks) already shape the artifacts — do not restate them.

**Plan gate** — present `proposal.md`, `specs/<cap>/spec.md`, `design.md`, `tasks.md`
per the gate protocol, then ask **approve / feedback**:

- **feedback** → revise the named artifact, re-present, loop.
- **approve** → commit the change folder (`feat(spec): BR-n <title>`), continue.

## Step 5 — Implement

Invoke `openspec-apply` for `<change>`. It follows `tasks.md` test-first. Rules that hold here:

- Swift 6 strict concurrency, no force unwraps. Kotlin coroutines + sealed classes, no `!!`.
- Protocol changes land a vector in `protocol/` and are checked on both sides.
- Match the surrounding code's patterns, comment density and naming.

Verify before claiming anything passes — run the commands and read the output:

```bash
xcodebuild -project mac/Bridge/Bridge.xcodeproj -scheme Bridge build    # Mac builds
xcodebuild -project mac/Bridge/Bridge.xcodeproj -scheme Bridge test     # Mac tests
(cd android/Bridge && ./gradlew test assembleDebug)                      # Android
openspec validate --change <change>
```

A failing verify is surfaced to the user every time — never silently loop a fix. Commit on green
(conventional commits, `feat(mac):` / `feat(android):` / `feat(protocol):`); no gate here, the
review gate covers this diff.

## Step 6 — Review → **review gate**

1. Run the `code-review` skill over the branch diff
   (`git diff main...HEAD`). Fix what it confirms; re-run the verify commands after any fix.
2. **Manual device test** — walk the item's `Done when` list on a real MacBook Air M3 + Galaxy S25
   and report each criterion pass/fail with what you observed. A criterion you could not test is
   reported as untested, never as passed.
3. **Review gate** — list every changed file as an absolute link (auto-open only when ≤10 files;
   for more, list them and say auto-open was skipped for size), with the review findings and the
   device-test results, then ask **approve / fix `<finding>`**.

## Step 7 — Wrap → **hand-off gate**

1. Any unchecked task in `tasks.md` → surface it and require explicit confirmation before
   archiving (manual or post-deploy tasks are fine to confirm; silent archiving is not).
2. `openspec archive <change>`.
3. Append the item to `docs/ITERATIONS.md`: layman summary, technical summary, known issues or
   degradations, what it leaves for the next item.
4. Set the row Status `done` in `docs/BACKLOG.md`; commit both docs.
5. **Hand-off gate** — summarize what shipped and ask **push / skip**. On push:
   `git push -u origin HEAD`. PR creation and merge stay manual.

## What you must NOT do

- Do **not** commit to `main`, or push without the hand-off gate's explicit confirmation.
- Do **not** re-derive the phase from memory — re-read disk before every gate and every step.
- Do **not** redo a phase whose artifact exists on disk; `--redo` is the only way back.
- Do **not** let the clarify phase scan code or plan the implementation.
- Do **not** archive a change with unchecked tasks unconfirmed, or claim a device test you
  did not run.
- Do **not** mark a step green on a verify command you did not run and read.
