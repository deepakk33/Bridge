## Context

See `proposal.md` — *Why*. What shapes the approach here:

- `BridgeApp.init()` constructs `BLEManager` directly, which creates the `CBCentralManager`. By
  the time any `NSApplicationDelegate` callback runs, the central exists. The guard therefore has
  to run inside `init()`, as its first statement.
- The app is `LSUIElement` (`mac/Bridge/Sources/Bridge/Info.plist`): no dock icon, no window.
  There is nothing to raise and no public API to open another process's `MenuBarExtra` panel.
- Both copies are built from the one Xcode project, so both carry bundle identifier
  `com.bridge.mac` regardless of where they are installed (`mac/Bridge/project.yml`).
- `BridgeTests` is a unit-test bundle whose `TEST_HOST` is `Bridge.app` itself. `xcodebuild test`
  launches the app, so a naive guard would make the *test host* exit whenever a real Bridge is
  running on the machine.

## Goals / Non-Goals

**Goals:**
- One Bridge process, enforced before any Core Bluetooth object is created.
- A guard with no persistent state, so a crash or force-quit cannot wedge the next launch.
- The decision itself unit-testable without launching two apps.

**Non-Goals:**
- Visible feedback to the person who launched the second copy beyond activating the first — see
  *Decisions*. A notification or a flashing icon was considered and dropped.
- Cross-process messaging of any kind.
- Anything on Android.
- Replacing BR-13's scan watchdog. macOS can drop a scan for its own reasons; this change removes
  one cause, not the need for recovery.

## Decisions

**Detect via `NSRunningApplication`, not a lock file or a port bind.**
`NSRunningApplication.runningApplications(withBundleIdentifier:)` reads the live process list, so
it is true by construction: a crashed process is not in it. A pid file or a `flock` has to be
cleaned up on exit, and the one exit path that matters here — the crash — is the path that cannot
clean up. A bound TCP port behaves correctly on crash too but adds a listener, a port number and a
firewall prompt for a question that is purely local. The running-applications list also matches
across install paths, which the lock-file alternative only does if its path is chosen carefully.

**Compare by bundle identifier and exclude `NSRunningApplication.current` by pid**, not by bundle
URL. Two builds at different paths are exactly the case BR-14 is about, so path must not enter the
comparison. Excluding self by process identifier (rather than by object identity) is what makes
the decision a pure function over `(pid, bundleID)` pairs, and therefore testable.

**Run the guard as the first statement of `BridgeApp.init()`.** Everything the second instance
must not do — the central, the payload server, the notification request — happens in or after that
`init`. Putting the guard in a delegate callback would be too late.

**Exit with `exit(0)`, not `NSApp.terminate(nil)`.** During `App.init()` the AppKit run loop has
not started; `terminate` posts through machinery that is not yet running. `exit(0)` is immediate
and leaves nothing half-initialised, and at this point the process owns no resource that needs
unwinding.

**Activate the running instance and stop there.** The user's call at the plan stage: no
notification, no icon flash. For a window-less `LSUIElement` app `activate()` is close to a no-op
visually, and that is accepted — the observable criteria that matter are that one process remains
and that the survivor's scan is untouched.

**Skip the guard when running under XCTest.** `BridgeTests` hosts itself in `Bridge.app`; without
this the whole Mac test suite fails on any machine with Bridge running. Detect via the
`XCTestConfigurationFilePath` environment variable, which Xcode sets only for a test run. The
alternative — changing `TEST_HOST` to a stub app — is a bigger change to the project file for no
other benefit.

## Risks / Trade-offs

- **A second launch looks like nothing happened.** → Accepted, decided at the plan gate. The app
  is already invisible by design; someone who double-launches it sees the single menu bar icon
  that was always there.
- **The XCTest escape hatch is a behaviour difference between test and ship.** → It is confined to
  one environment-variable check at the single call site, and the decision function it guards is
  the part under test; the escape hatch itself is asserted in a test of its own.
- **`runningApplications(withBundleIdentifier:)` only sees applications in the current user
  session.** → That is the intended scope: two instances in one session is the reported failure.
  Fast-user-switched sessions each get their own Bridge, which is correct, not a regression.
- **A race between two near-simultaneous launches could let both pass the check.** → Possible in
  principle, not reachable by the human path this fixes (launch one, then build and launch
  another, seconds apart). Not mitigated; mitigating it would need the lock the first decision
  rejected.
