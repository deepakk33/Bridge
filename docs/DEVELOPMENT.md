# Bridge — Development Guideline

How work gets done on Bridge now. One queue, one command, three gates.

## The loop

```
docs/BACKLOG.md  →  /work BR-n  →  branch → spec → code → review → push
                         ▲                    │        │        │
                         └── you answer ──────┴────────┴────────┘
                                   3 gates
```

1. **Write the item.** Add a row and a detail block to `docs/BACKLOG.md`. A one-liner is enough
   to start — Claude fills the rest with you at the clarify step.
2. **Run `/work BR-n`.** It carries the item the whole way: clarifies what is thin, branches,
   writes the OpenSpec change, implements it test-first, reviews the diff, archives, updates
   `docs/ITERATIONS.md`, pushes on your word.
3. **Answer three gates.** Nothing else interrupts you.

`/work` with no argument prints the queue plus each item's real phase read off disk — use it when
you come back after a few days and have forgotten where something stood.

## The three gates

| Gate | You are looking at | You answer |
|---|---|---|
| **Plan** | `proposal.md`, `specs/`, `design.md`, `tasks.md` | approve / feedback |
| **Review** | the branch diff + the device-test results | approve / fix |
| **Hand-off** | what shipped | push / skip |

Files arrive as clickable absolute paths and open in your editor. Feedback never advances a gate —
it loops until you approve.

Everything between the gates — the spec authoring, the implementation, the commits on a green
build, the archive — happens without asking you. That is deliberate: the plan gate is where scope
is settled and the review gate is where the code is actually read.

## Writing a backlog item

```markdown
### BR-9 — Wi-Fi hotspot fallback

**Scope**: fall back to the phone's hotspot when no shared LAN is present.
**Why**: file transfer drops to BT RFCOMM speed in cafés.
**Platforms**: both
**Done when**:
- [ ] 100MB transfer over hotspot completes on the S25 + M3 with no LAN
- [ ] falls back within 5s of LAN loss, menu bar shows the tier
**Notes**: PRD §transport tiers; tier router lives in mac/Bridge/Sources/Bridge/FileTransfer/.
```

An item is `ready` when Scope, Why and Done-when all exist and **each criterion can be checked on
real hardware**. "Works well" is not a criterion. "100MB in under 40s over hotspot" is.

Statuses move `idea → ready → in-progress → review → done`. `/work` maintains them; you can edit
anything by hand at any time — the file is the source, not a cache.

## What the pipeline will not do

- Commit to `main`. Every item gets `work/<id>-<slug>`.
- Push without you saying so at the hand-off gate.
- Claim a build, test or device check passed without having run it and read the output. A
  criterion it could not test comes back marked untested, not green.
- Archive a change with unchecked tasks unless you confirm it.
- Redo a phase whose artifact is already on disk. Use `/work BR-n --redo <phase>` to go back;
  to re-author a spec from scratch, delete the change folder first.

## State lives on disk, not in the chat

There is no state file to corrupt and no session to lose. The phase is re-derived every run from
the backlog row, the branch, the change folder, the `- [ ]` boxes in `tasks.md`, and the archive
directory. Close the terminal mid-item and re-run `/work BR-n` — it picks up where the disk says
it is.

## Verify commands

```bash
xcodebuild -project mac/Bridge/Bridge.xcodeproj -scheme Bridge build
xcodebuild -project mac/Bridge/Bridge.xcodeproj -scheme Bridge test
(cd android/Bridge && ./gradlew test assembleDebug)
openspec validate --change <change-name>
```

## Working two platforms at once

Only then add a worktree under `.claude/worktrees/`. A single-platform item stays on one branch
in the main checkout — a worktree per item is overhead you will pay for in confusion.

## Where things live

| | |
|---|---|
| Queue | `docs/BACKLOG.md` |
| Pipeline rules (for Claude) | `.claude/commands/work.md`, `.claude/CLAUDE.md` |
| This guide (for you) | `docs/DEVELOPMENT.md` |
| Shipped history | `docs/ITERATIONS.md`, `openspec/changes/archive/` |
| Requirements | `docs/PRD.md` |
| Artifact rules per change | `openspec/config.yaml` |
