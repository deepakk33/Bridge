## 1. Diagnosis — stop at the first task that explains the failure

> Nothing in section 3 is written until section 1 names a cause and section 2 records it.

- [x] 1.1 Rule the macOS Local Network permission in or out **without** trusting the UI row: read
      the real authorization state for the Bridge bundle, and record what it says. Verify by
      stating the value observed, not by reading `PermissionChecker`.
- [x] 1.2 Prove the socket path independently of Bridge: run a plain TCP listener on the Mac and
      connect from the phone (`adb shell` or a scratch app), then the reverse. Verify by recording
      which directions carried bytes. This separates "macOS blocks the connection" from "the TLS
      handshake fails".
- [ ] 1.3 If 1.1/1.2 show the network path is clean, instrument the handshake: log on the Mac when
      the listener accepts a connection and when state changes, and enable TLS diagnostics on both
      sides. Verify by capturing a logged accept (or a logged absence of one) during a real
      attempt.
- [ ] 1.4 Write the cause into `design.md` under a new "Finding" section, with the evidence that
      establishes it. Verify the section exists and names evidence, not a guess.

## 2. Gate — confirm scope before fixing

- [ ] 2.1 If the cause lies outside this item (for example, the Local Network permission needs
      requesting and reporting, which is BR-11), stop and surface it rather than absorbing it.
      Verify by stating the boundary decision and who owns each part.

## 3. Make Tier 3 complete

- [ ] 3.1 Write a failing test at the level the cause allows — a unit test if the cause is
      configuration, otherwise a documented device repro in `design.md`. Verify it fails first.
- [ ] 3.2 Apply the fix the evidence names, on whichever side owns it. Verify
      `(cd android/Bridge && ./gradlew test assembleDebug)` and
      `xcodebuild -project mac/Bridge/Bridge.xcodeproj -scheme Bridge test` are green.
- [ ] 3.3 If the Mac cannot use the local network, make that failure say so instead of surfacing as
      a timeout. Verify with a Mac unit test over the error mapping.

## 4. Close the security holes — Mac

- [ ] 4.1 Reduce an incoming filename to a basename before it reaches `appendingPathComponent`,
      rejecting or flattening separators and parent references. Write the test first, including
      `../../.zshenv`, an empty name, and a name that is only separators. Verify the Mac suite goes
      red then green.
- [ ] 4.2 Refuse a `payloadSize` above the cap before a port is opened or a file created, and
      surface the refusal. Test first with a size far above the cap. Verify the Mac suite.
- [ ] 4.3 Serve the payload only to the peer whose pinned certificate matches the requester; a
      different host gets no bytes. Test first. Verify the Mac suite.
- [ ] 4.4 Announce an incoming file with name and size before writing, with a decline that leaves
      nothing behind and closes the sender's window. Verify the Mac suite and that a declined
      transfer does not hold the listener open for its full timeout.
- [ ] 4.5 Surface a failed receive to the user rather than only logging it. Verify the Mac suite.

## 5. Close the security holes — Android

- [ ] 5.1 Apply the same basename reduction in `FileReceiver`, test first, including `../`
      traversal. Verify `./gradlew test`.
- [ ] 5.2 Apply the same `payloadSize` cap before committing resources, test first. Verify
      `./gradlew test`.
- [ ] 5.3 Restrict `PayloadServer` to the requesting pinned peer. Verify `./gradlew test`.
- [ ] 5.4 Announce before writing, with a decline, and surface a failed receive — reusing
      `TransferNotifier` rather than adding a surface. Verify `./gradlew test assembleDebug`.
- [ ] 5.5 Add a shared vector or fixture for the traversal and oversize cases so both platforms are
      checked against the same inputs. Verify both suites consume it.

## 6. Device test — MacBook Air M3 + Galaxy S25

> Exclusive: this window holds the devices for all of section 6, and no second `Bridge.app` runs.
> Say so before starting and when done. If pairing fails with a fingerprint mismatch, clear
> `shared_prefs/bridge_trust_store.xml` via `run-as` — not `pm clear`, which drops the runtime
> permissions.

- [ ] 6.1 Mac→phone over Tier 3 (>512 KB): confirm arrival and that `shasum` on the Mac equals
      `sha256sum` on the phone. Record both digests.
- [ ] 6.2 Phone→Mac over Tier 3: same, both digests recorded.
- [ ] 6.3 With two devices pinned in the trust store, repeat one direction and confirm the
      handshake verified against the connected peer's pin (BR-19's untested task 9.2).
- [ ] 6.4 Send a file named with a `../` traversal and confirm it lands inside the download
      directory flattened, or is refused — and that nothing outside it was created.
- [ ] 6.5 Send a share request declaring an absurd `payloadSize` and confirm it is refused before
      any bytes are written, and that the user is told.
- [ ] 6.6 From a third LAN host, connect to the payload port during a transfer window and confirm
      it gets nothing while the real transfer still completes.
- [ ] 6.7 Confirm an incoming file is announced before it is written, and that declining leaves
      nothing behind and does not strand the sender.
- [ ] 6.8 Force a receive failure and confirm the user is told, on both platforms.
- [ ] 6.9 Record each `Done when` criterion pass/fail with what was observed. Anything not run is
      reported as untested, never as passed.

## 7. Close out

- [ ] 7.1 Run `(cd android/Bridge && ./gradlew test assembleDebug)`,
      `xcodebuild -project mac/Bridge/Bridge.xcodeproj -scheme Bridge test` and
      `openspec validate br-8-tier-3-transfer`, and read all three outputs.
