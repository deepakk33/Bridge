## 1. Shared — the ping reply contract

- [x] 1.1 Add ping/pong vectors to `protocol/` — a ping with no `replyTo` and a reply carrying
      `replyTo` — and verify both platforms' packet tests load and assert them
- [x] 1.2 Record the reply rule in `docs/PRD.md`'s packet table (a `bridge.ping` with `replyTo`
      is terminal), and verify the table lists no new packet type

## 2. Mac — answer the ping

- [x] 2.1 Write the failing test first in `PingPacketTests`: a plain ping yields a reply with
      `replyTo` equal to the ping's `id`, and a packet already carrying `replyTo` yields nil.
      Verify both fail against the current `-> Bool` signature
- [x] 2.2 Change `PingHandler.handle` to return `BridgePacket?` with the `replyTo` guard, and
      verify 2.1's tests pass via `xcodebuild ... -scheme Bridge test`
- [x] 2.3 Send the reply from `BLEManager`'s `case "bridge.ping"`, and verify the build is clean
      under Swift 6 strict concurrency with `xcodebuild ... -scheme Bridge build`
- [x] 2.4 Add `bridge.ping` to `PacketGate.alwaysAllowed` and update `PacketGateTests` — the
      existing assertion that an unidentified peer's ping is refused now inverts; verify the
      suite passes and that no packet type other than identity/pair/ping became allowed

## 3. Android — do not answer a reply

- [x] 3.1 Write the failing test first: `PingHandler.handle` on a packet carrying `replyTo`
      returns nothing. Verify it fails — the current handler answers everything
- [x] 3.2 Change Android `PingHandler.handle` to return `BridgePacket?` with the same guard, send
      only a non-null result in `L2capServer.handlePacket`, and verify via
      `(cd android/Bridge && ./gradlew test)`
- [x] 3.3 Add `bridge.ping` to Android `PacketGate`'s always-allowed set, with a test that an
      unverified peer's ping is accepted while `bridge.clipboard` from the same peer is dropped

## 4. Mac — pin the payload to the connected peer

- [x] 4.1 Write the failing test in `PayloadChannelTests`: with two devices pinned, parameters
      built for peer A verify A's fingerprint and reject B's. Verify it fails today
- [x] 4.2 Replace `PayloadTLS.pairedPeerParameters()` with `pairedPeerParameters(for:)`, delete
      `TrustStore.pairedDeviceId()`, and verify the compiler names every call site
- [x] 4.3 Inject a `connectedPeerId: () -> String?` supplier into `FileTransferManager` and
      `FileReceiveController` from `BLEManager`, and verify a nil result fails the transfer with
      `Error.notPaired` rather than opening a listener (test asserts no listener is bound)

## 5. Android — pin the payload to the connected peer

- [x] 5.1 Write the failing test: `PayloadTls.forPairedPeer(deviceId)` with two pins trusts only
      the named device's fingerprint
- [x] 5.2 Replace `PayloadTls.forPairedPeer()` with the `deviceId` form, delete
      `TrustStore.pairedFingerprint()`, and verify `./gradlew test` passes
- [x] 5.3 Expose the live `peerDeviceId` through `BlePeripheral` and inject a supplier into
      `FileTransferCoordinator` and `FileReceiver` from `BridgeService`; verify a transfer
      attempted with no connection throws `NotPairedException` and writes no file

## 6. Mac — a confirmation that waits for the fingerprint

- [x] 6.1 Write the failing test in `PairingManagerTests`: set the peer identity before
      `setLocalFingerprint`, and assert `incomingPairRequest` is non-nil with the correct six
      digits once the fingerprint arrives. Verify it fails today
- [x] 6.2 Add `deferredConfirmation` to `PairingManager`, replay it from `setLocalFingerprint`,
      and clear it in `reset()`; verify 6.1 passes and a test asserting no prompt appears after
      `reset()` also passes

## 7. Android — a fingerprint read that retries

- [x] 7.1 Write the failing test: a `PairingManager` whose `localFingerprint` supplier returns
      null on the first call and a value on the second still raises the confirmation
- [x] 7.2 Change `L2capServer.localFingerprint` from `by lazy` to a function caching only a
      successful read, change `PairingManager`'s parameter to `() -> String?`, and apply
      defer-and-replay; verify 7.1 passes and the held request is dropped in the accept loop's
      `finally`

## 8. Verify

- [x] 8.1 Run `xcodebuild -project mac/Bridge/Bridge.xcodeproj -scheme Bridge test` and
      `(cd android/Bridge && ./gradlew test assembleDebug)` and read both outputs green
- [x] 8.2 Run `openspec validate --change br-19-pairing-and-link-hardening` and read it clean
- [x] 8.3 Grep both trees for `pairedDeviceId` and `pairedFingerprint` and verify no references
      remain outside the archive

## 9. Device test — MacBook Air M3 + Galaxy S25 (exclusive, one window)

- [x] 9.1 Unpair both sides, pair fresh, and leave the Mac's confirmation sheet open for three
      minutes while opening the Android app twice; verify the link is never dropped and the
      confirmation is still answerable (Done-when 1)
- [ ] 9.2 With the phone paired, pin a second device id by hand, then send a file Mac→phone and
      phone→Mac; verify both transfers complete and the logs show the connected peer's
      fingerprint used for the handshake (Done-when 2)
- [ ] 9.3 Cold-start the Mac app with the phone already advertising so the identity packet races
      the Keychain read; verify the pair confirmation appears with matching six digits on both
      screens (Done-when 3). Repeat five times — it is a race, one pass is not evidence
- [x] 9.4 Leave the paired link idle for five minutes and read the Mac's log: verify one ping per
      30 s with one reply each, and no ping storm
- [x] 9.5 Record each criterion pass/fail with what was observed in the change folder's device
      test notes
