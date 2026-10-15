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

## OpenSpec Iterations

5 iterations, each a standalone OpenSpec change:

| Iteration | Change Name | Scope |
|-----------|-------------|-------|
| 1 | `iter-1-ble-spike` | BLE L2CAP channel, app skeletons, throughput test |
| 2 | `iter-2-secure-link` | Pairing, certs, TLS, auto-reconnect, status UI |
| 3 | `iter-3-notifications` | Mirror, reply, battery status |
| 4 | `iter-4-clipboard` | Bidirectional clipboard, ADB grant, timestamp rule |
| 5 | `iter-5-files-polish` | File transfer (BT+WiFi), permission panel, watchdog |

## Iteration Workflow (6 Phases)

**Every iteration MUST follow these 6 phases in order.** Do not skip phases. Do not mark an iteration complete until phase 6 passes.

### Phase 1: Understand
- Read the OpenSpec change: `openspec show <change-name>`
- Read `proposal.md`, `specs/`, `design.md`, `tasks.md`
- Read relevant sections of `docs/PRD.md`
- Identify platform permissions needed
- Identify packet types used
- List any unknowns or assumptions
- **Gate**: Can explain what this iteration does and why, in one paragraph

### Phase 2: Spec Review & Update
- Run `openspec validate --change <change-name>`
- Verify specs match PRD requirements
- Update specs if implementation reveals gaps
- Ensure test criteria are defined
- **Gate**: `openspec validate` passes clean

### Phase 3: Design
- Review `design.md` for this iteration
- Identify file paths, class/struct names, function signatures
- Plan the implementation order (dependencies first)
- Note platform-specific gotchas from PRD constraints section
- **Gate**: Know exactly which files to create/modify

### Phase 4: Implement
- Use git worktree if working on both platforms simultaneously
- Follow existing code patterns in the repo
- Mac: Swift 6 concurrency (async/await, actors)
- Android: Kotlin coroutines, foreground service patterns
- Protocol: Match KDE Connect wire format exactly
- Shared test vectors in `protocol/` for cross-platform validation
- **Gate**: Code compiles on both platforms

### Phase 5: Test
- Unit tests for protocol handling (both platforms)
- Integration tests for BLE/network communication
- Run shared test vectors from `protocol/` on both sides
- Manual device test on real hardware:
  - MacBook Air M3 (macOS 26.6.2)
  - Galaxy S25 (Android 16, One UI 8.5)
- Test the specific success criteria from the proposal
- **Gate**: All tests pass, manual device test succeeds

### Phase 6: Validate & Report
- Run full test suite
- Verify the iteration's success criteria end-to-end
- Archive the OpenSpec change: `openspec archive <change-name>`
- Update `docs/ITERATIONS.md` with:
  - What was built (layman summary)
  - What was built (technical summary)
  - Known issues or degradations
  - What the next iteration builds on
- **Gate**: Can demo the feature working on real devices

## Conventions

### Git
- Branch per iteration: `iter-1/ble-spike`, `iter-2/secure-link`, etc.
- Conventional commits: `feat(mac):`, `feat(android):`, `feat(protocol):`, `fix(mac):`, etc.
- Commit after each phase completes

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
- Clipboard: ADB grant for background read (`pm grant` READ_LOGS + READ_CLIPBOARD_IN_BACKGROUND)

## Key Decisions (from PRD)
- No Apple Developer Program — self-signed Keychain certificate
- No screen mirroring — scrcpy launched by hand
- Native Swift (not Node/Electron) for Mac
- Private tool, phone may change (no hard-coded device IDs)
- Phone always within ~1 metre of Mac (BLE is resident link)
