Order follows design.md — Migration Plan. The spike settles first; the pairing flow lands before
the trust gate tightens, so a re-pair is always reachable.

Verify commands used below:
- Mac build: `xcodebuild -project mac/Bridge/Bridge.xcodeproj -scheme Bridge build`
- Mac tests: `xcodebuild -project mac/Bridge/Bridge.xcodeproj -scheme Bridge test`
- Android: `(cd android/Bridge && ./gradlew test assembleDebug)`

## 1. Spike: secure L2CAP (blocks nothing, decides task 7)

- [ ] 1.1 Throwaway branch build: switch `L2capServer` to `adapter.listenUsingL2capChannel()`,
      install on the S25, and record whether the Mac's `openL2CAPChannel` succeeds, fails, or
      triggers a macOS Bluetooth pairing prompt. Verify by reading both device logs; write the
      observed outcome into design.md — Decision 5 as a dated note. Discard the spike code.

## 2. Shared: six-digit derivation and its vectors

- [x] 2.1 Write `protocol/vectors/pairing-code.json` — at least 8 fingerprint pairs with their
      expected six digits, including a pair given in both orders, two identical fingerprints, and
      a pair whose digits begin with a zero. Verify the file parses and each entry has both
      fingerprints and a 6-character expected string.
- [x] 2.2 Mac: add `PairingCode.derive(_:_:)` per `protocol/pairing` — lowercase, sort,
      concatenate, SHA-256, first 4 bytes big-endian, mod 1e6, zero-pad 6. Verify a new test
      reads the vector file and every entry passes, including the leading-zero case.
- [x] 2.3 Android: add `PairingCode.derive(a, b)` with the identical algorithm. Verify a new unit
      test reads the same vector file and every entry passes.
- [x] 2.4 Verify cross-platform agreement: both test suites green against the one shared file
      (Mac tests and `./gradlew test`), so neither implementation can drift alone.

## 3. Mac: pairing becomes a two-sided gate

- [x] 3.1 Add separate local and remote decision state to `PairingManager` (pending / accepted /
      declined each), pinning only when both are accepted, in either arrival order. Verify unit
      tests cover: local-then-remote pins; remote-then-local pins; remote accept alone does not
      pin; decline on either side pins nothing and clears both.
- [x] 3.2 Remove the auto-pair in `setPeerIdentity` (the `TrustStore.shared.isEmpty` branch) and
      the `acceptIncomingPair()` call that follows `presentPairConfirmation` in
      `handlePairAcceptOrRequest`'s `.unpaired` branch. Verify a test asserts that an empty trust
      store plus an incoming request leaves the store empty until an explicit accept.
- [x] 3.3 Make the `.paired` branch stop re-accepting a changed fingerprint: close the connection
      and leave the pin intact, per `protocol/pairing`'s mismatch requirement. Verify a test
      asserts the pinned fingerprint is unchanged and the connection is closed.
- [x] 3.4 Confirm `yieldsOnCrossedRequest` still resolves crossed initiation with the gate in
      place. Verify the existing crossed-request tests pass plus a new one where both sides' users
      accept after the yield.
- [x] 3.5 Scope the 30-second timeout to the peer's decision only; a local prompt awaiting the
      user does not expire. Verify a test asserts a pending local prompt survives past 30s while
      an unanswered remote request expires.

## 4. Mac: pairing UI

- [x] 4.1 Show the six digits, the peer device name, and Accept / Decline in the pair
      confirmation surface built in BR-2. Verify by launching the app against a staged pending
      request and reading the rendered panel.
- [x] 4.2 Surface the confirmation outside the panel (the BR-2 banner path) so it is reachable
      when the menu-bar panel is closed. Verify by triggering a request with the panel shut and
      observing the banner.

## 5. Android: pairing becomes a two-sided gate

- [x] 5.1 Mirror task 3.1 in `security/PairingManager.kt` — separate local and remote decisions,
      pin only on both. Verify unit tests cover the same four cases.
- [x] 5.2 Remove all three unconditional `acceptPair()` paths: the `onPairRequest` callback in
      `L2capServer.handleConnection`, and the two inside
      `PairingManager.handlePairAcceptOrRequest`. Verify a test asserts an incoming request
      leaves the trust store unchanged until `acceptPair()` is called explicitly.
- [x] 5.3 Close the connection on a fingerprint that does not match the pin, leaving the pin
      intact. Verify a unit test asserts both.

## 6. Android: pairing UI

- [x] 6.1 Add a pairing confirmation screen showing the six digits, the peer name, and Accept /
      Decline, raised from the foreground service when a request arrives. Verify on device that
      it appears with the app backgrounded.
- [ ] 6.2 Verify the two screens show identical digits for the same device pair, side by side on
      real hardware.

## 7. Transport encryption

- [ ] 7.1 Take the branch task 1.1 decided: either switch `L2capServer` to
      `listenUsingL2capChannel()`, or leave the insecure listen and record why. Verify the link
      still comes up end-to-end on device and the choice is noted in design.md.
- [x] 7.2 Android payload TLS: wrap `PayloadServer`'s `ServerSocket` with
      `createSSLContext(BridgeTrustManager(peerFingerprint)).serverSocketFactory`, `needClientAuth
      = true`; wrap `PayloadClient` with the matching `socketFactory`. Verify a unit test transfers
      bytes over a loopback TLS pair and a second test asserts a wrong-fingerprint peer is refused.
- [ ] 7.3 Mac payload TLS: give `PayloadServer`'s `NWListener` and `PayloadClient`'s
      `NWConnection` `NWParameters(tls:tcp:)` with
      `sec_protocol_options_set_peer_authentication_required` and a verify block comparing the
      leaf's SHA-256 to the pin. Verify the same two tests on the Mac side.
- [x] 7.4 Refuse to open a payload listener toward a peer with no pinned fingerprint, on both
      platforms, before the `bridge.share.request` is sent. Verify a test on each side asserts no
      listener is bound and the transfer is reported refused.
- [x] 7.6 **Added 2026-10-29, blocks 7.3.** Fix `CertificateManager.loadExistingIdentity()`:
      a `kSecClassIdentity` query on macOS ignores both `kSecAttrLabel` and
      `kSecAttrApplicationTag`, so it returns whatever identity the keychain holds — on a dev
      machine the "Bridge Dev" code-signing certificate. The generated device certificate has
      therefore never been stored or used, and the Mac's pinned "device fingerprint" has always
      been its code-signing certificate's. Look the certificate up by label (certificate queries
      do respect it) and pair it with its key via `SecIdentityCreateWithCertificate`. Verify a
      test asserts the loaded identity's subject is "Bridge Device", not "Bridge Dev".
- [ ] 7.5 Verify a Mac↔Android file transfer end to end over Tier 3 with TLS on both ends, and
      confirm with a packet capture that the payload bytes are TLS records, not the file.

## 8. Default-deny trust gate and line cap

- [x] 8.1 Android: replace the inline condition in `L2capServer.handlePacket` with the single
      `allow(packetType)` gate from design.md — Decision 3, applied before dispatch. Verify unit
      tests cover each gate branch: pre-identity non-exempt packet dropped, `bridge.identity` and
      `bridge.pair` allowed, identified-but-unpinned dropped, mismatched fingerprint dropped,
      `bridge.ping` from an unidentified peer unanswered.
- [x] 8.2 Android: delete the `catch (_: IllegalStateException) {}` fail-open; an unreadable trust
      store denies. Verify a test asserts a throwing trust store drops the packet.
- [x] 8.3 Android: make `BridgeTrustManager`'s null-`expectedFingerprint` branch throw
      `CertificateException` instead of logging and accepting. Verify a test asserts rejection.
- [x] 8.4 Mac: apply the same gate before dispatch in the L2CAP receive path. Verify unit tests
      cover the same five branches.
- [x] 8.5 Both platforms: replace the unbounded `readLine()` with a 256 KB-capped read that closes
      the connection on breach. Verify a test feeds an oversized line with no newline and asserts
      the connection closes and memory stays bounded; verify a second test that a packet at the
      10 KB out-of-band threshold still passes.

## 9. Mac: device picker

- [x] 9.1 Accumulate discovered peripherals (name, RSSI, peripheral, last-seen) in `BLEManager`
      instead of connecting from `didDiscover`, connecting automatically only when the peripheral
      is the paired device. Verify a unit test asserts no connect is issued while unpaired and
      that the paired device still connects automatically.
- [x] 9.2 Age entries out on a last-seen timer so a device does not flicker between advertising
      intervals. Verify a test asserts an entry survives one missed interval and is removed after
      the timeout.
- [x] 9.3 Render the picker in the status panel with name and signal strength, and connect on
      selection. Verify on device that two advertising phones both appear and only the chosen one
      is connected.
- [x] 9.4 Confirm BR-13's scan watchdog still behaves with the picker open — discovery keeps
      running, no spurious restarts. Verify by reading the log across a picker session.

## 10. Docs

- [ ] 10.1 Amend `README.md`, `docs/PRD.md` ("Protocol and security") and `.claude/CLAUDE.md` to
      describe the per-path encryption model and the outcome of task 1.1. Verify by grepping all
      three plus `openspec/specs/security/` for "mutual TLS" and confirming every remaining
      occurrence refers to the payload path.
- [ ] 10.2 Verify `openspec validate br-4-transport-security-and-pairing` passes and that the
      shipped behaviour matches every delta in `specs/`.

## 11. Device test (exclusive — holds both devices)

Run on MacBook Air M3 + Galaxy S25. Announce before starting; no second `Bridge.app` running.
**Start from a cleared trust store on both ends** — the first-run path is what changed.

- [x] 11.1 Clear both trust stores, then verify both screens show the same six digits before
      anything is pinned (BR-5 criterion 1).
- [x] 11.2 Verify declining on the Mac leaves both trust stores unchanged; repeat declining on the
      phone (BR-5 criterion 2).
- [ ] 11.3 Pair successfully, then reinstall the Android app to force a new certificate and verify
      the connection closes on the mismatch and needs a deliberate re-pair (BR-5 criterion 3).
- [x] 11.4 With the Mac unpaired, verify it lists discovered phones by name and RSSI and waits for
      a choice (BR-5 criterion 4).
- [x] 11.5 Verify a peer that has sent no identity packet gets no packet type processed — inject a
      `bridge.clipboard` before identity and confirm the drop in the log (BR-4 criterion 1).
- [ ] 11.6 Verify an uninitialised trust store drops rather than passes (BR-4 criterion 2).
- [ ] 11.7 Verify a line over the 256 KB cap closes the connection (BR-4 criterion 3).
- [ ] 11.8 Verify docs and specs match the shipped transport (BR-4 criterion 4) — read task 10.1's
      grep output against the device's actual behaviour.
- [ ] 11.9 Verify the regression surface: clipboard both ways, a notification with a reply, and
      battery still work over the gated channel with a paired device.
