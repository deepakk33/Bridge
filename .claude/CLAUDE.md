# Bridge — Android ↔ Mac Direct Link

## Project Overview

Bridge makes a Galaxy S25 and a MacBook Air M3 behave as one machine when in the same room. No network, no account, no cloud. BLE for control, Wi-Fi for bulk. Speaks KDE Connect wire format.

- **Mac**: Swift 6, SwiftUI menu bar app, Core Bluetooth, Network.framework
- **Android**: Kotlin, foreground service (connectedDevice), NotificationListenerService
- **Protocol**: JSON newline-delimited, 10 packet types, mutual TLS with pinned certs
- **PRD**: `docs/PRD.md` (full requirements, architecture, decisions)

## Monorepo Structure

```
Bridge/
├── mac/          # Xcode project (Swift)
├── android/      # Gradle project (Kotlin)
├── protocol/     # Shared test vectors, packet schemas
├── docs/         # PRD, iteration notes
├── openspec/     # Specs, changes, iterations
└── scripts/      # ADB grant scripts, build helpers
```

## Development Pipeline

Work is driven by `docs/BACKLOG.md` (the queue — one row + one detail block per item) and the
`/work <BR-n>` command (`.claude/commands/work.md`), which runs the whole item: clarify → branch
→ propose → implement → review → wrap. Phase is derived from disk, never from memory. `/work`
with no argument prints the queue and each item's real phase.

Three human gates, and only these: **plan** (the OpenSpec artifacts), **review** (the diff plus
the device test), **hand-off** (push). Everything between gates is automatic.

Full rules for a human: `docs/DEVELOPMENT.md`.

Iterations 1–8 are archived under `openspec/changes/archive/` and summarized in
`docs/ITERATIONS.md`; new work is a backlog item, not a numbered iteration.

### Non-negotiables

- Never commit to `main` — branch `work/<id>-<slug>` per item.
- Never claim a build, test, or device check passed without running it and reading the output.
- Never archive a change with unchecked tasks unless the user confirms it.

## Conventions

### Git
- Branch per backlog item: `work/br-9-hotspot-fallback` (archived iterations used `iter-N/<slug>`)
- Conventional commits: `feat(mac):`, `feat(android):`, `feat(protocol):`, `fix(mac):`, etc.
- Commit at each pipeline step — spec folder, each green verify, the wrap-up docs

### Code Style
- Swift: Swift 6 strict concurrency, SwiftUI for UI, no force unwraps
- Kotlin: Coroutines for async, sealed classes for state, no !! operator
- Both: Descriptive names, minimal comments (code explains itself)

### Protocol
- All packets are single-line JSON, newline-delimited
- Packet types prefixed with `bridge.` (e.g., `bridge.clipboard`)
- Every handler is idempotent
- Payloads over 10KB use out-of-band transfer (separate TCP connection)

### Permissions (reference)
**Mac TCC grants needed:**
- Local Network (NSLocalNetworkUsageDescription + NSBonjourServices)
- Bluetooth (Core Bluetooth scan)
- Notifications (post mirrored notifications)

**Android permissions needed:**
- Foreground service (connectedDevice type)
- Notification access (NotificationListenerService, manual enable)
- Bluetooth LE advertise
- Clipboard: none. Background read needs READ_CLIPBOARD_IN_BACKGROUND, which Android 16 manages
  by role and will not `pm grant` to a sideloaded build. Phone-to-Mac is manual by design —
  quick-settings tile or the in-app send control. Do not add a grant, script or adb command.

## Key Decisions (from PRD)
- No Apple Developer Program — self-signed Keychain certificate
- No screen mirroring — scrcpy launched by hand
- Native Swift (not Node/Electron) for Mac
- Private tool, phone may change (no hard-coded device IDs)
- Phone always within ~1 metre of Mac (BLE is resident link)
