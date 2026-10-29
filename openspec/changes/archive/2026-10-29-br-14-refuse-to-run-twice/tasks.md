## 1. Mac — the decision

- [x] 1.1 Add `SingleInstanceGuard` with a pure decision function over `(pid, bundleIdentifier)`
      pairs plus the current pid and bundle identifier, returning whether another instance holds
      the app; verify `xcodebuild -project mac/Bridge/Bridge.xcodeproj -scheme Bridge build`
      succeeds
- [x] 1.2 Write `SingleInstanceGuardTests` first, covering: no other process → run; one other
      process with the same bundle id → yield; a process with the same pid as self → ignored (not
      treated as another instance); a process with a different bundle id → ignored; two other
      instances → yield; verify with `xcodebuild -project mac/Bridge/Bridge.xcodeproj -scheme
      Bridge test`

## 2. Mac — the effect

- [x] 2.1 Add the guard's side-effecting entry point: query `NSRunningApplication`, and on a
      yield decision activate the other instance and `exit(0)`; verify the build succeeds
- [x] 2.2 Make the entry point return without checking anything when
      `XCTestConfigurationFilePath` is set in the environment, and cover that with a test, so the
      `BridgeTests` host is never killed by the guard; verify `xcodebuild … test` passes while a
      copy of `Bridge.app` is running
- [x] 2.3 Call the guard as the first statement of `BridgeApp.init()`, ahead of
      `NotificationManager.shared.requestPermission()` and the `BLEManager` construction; verify
      the build succeeds and the app still launches to a menu bar icon

## 3. Verify

- [x] 3.1 Run `xcodebuild -project mac/Bridge/Bridge.xcodeproj -scheme Bridge test` and read the
      output; all tests pass
- [x] 3.2 Run `openspec validate br-14-refuse-to-run-twice --strict`; it reports no errors

## 4. Device test (MacBook Air M3 + Galaxy S25)

- [x] 4.1 With one Bridge running from `/Applications`, launch the Xcode build from its
      `DerivedData` path; confirm with `pgrep -fl Bridge.app` that exactly one process remains
      and the survivor is the original pid
- [x] 4.2 With the phone connected and the survivor showing a live link, repeat 4.1 and confirm
      the survivor's link health does not drop and the phone stays visible — the failure BR-14
      exists to prevent
- [x] 4.3 Quit the running instance normally, relaunch; it starts and reaches the menu bar.
      Then `kill -9` the running instance and relaunch; it starts with nothing to clear by hand
