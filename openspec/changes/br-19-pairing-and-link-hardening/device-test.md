# BR-19 device test

MacBook Air M3 (macOS 26.6.2, Debug build from `.build/br19`) + Galaxy S25 (Android 16,
debug APK). Run 2026-11-02, both apps freshly installed, logs captured from
`log stream --predicate 'subsystem == "com.bridge.mac"'` and `adb logcat`.

## Criterion 1 — the link is not dropped by `probeLink` during a pairing that waits on a human

**PASS.**

The Mac's trust store was empty (`defaults read com.bridge.mac …trustedDevices` → `{}`) and it
sat on an unanswered pair confirmation (`PairingManager: Unknown device — awaiting confirmation`,
11:51:32.499) for the whole window below. Link health stayed `verifying`; the peer was never
trusted, which is the state that used to make the probe fatal.

A probe was forced at 11:53:59–11:54:01 by backgrounding and re-foregrounding the phone app
(`onResume → requestHandshake → probeLink`). Heartbeats land on a `:02.5` cadence, so the
11:54:01.885 ping is the probe and not the heartbeat:

```
11:54:01.885  Mac    Read 52 bytes → PingHandler: Received ping (id: 1793600641913)
11:54:01.976  Phone  PingHandler: Received ping reply id=1793600641886
```

The reply reached the phone 91 ms after the probe was sent, against a 3000 ms budget. No
`Probe: no response in 3000ms — closing dead link` line followed, and the link stayed up.

The link was continuously open from 11:51:32.5 to past 11:54:02 — over 2.5 minutes with the
confirmation unanswered. Before this change the Mac sent nothing in reply and the probe closed
the socket after 3 s, every time.

The sheet was still answerable after 7.5 minutes open: at 11:59:06.735 the Mac logged
`PairingManager: Accepted locally, awaiting <peer>` for the confirmation raised at 11:51:32.499,
and the phone raised its own prompt 84 ms later. Nothing about the long wait had staled it.

## Criterion 3 (part) — the six digits agree across the two screens

**PASS, by eye.** With the Mac's confirmation and the phone's confirmation both on screen at
11:59:06, the operator confirmed the same six digits on both devices. Not readable from the
logs, which print the fingerprint but never the derived code (by design — the code is never
transmitted and never logged).

## Observed, outside BR-19's scope — the 30-second pairing timeout is short for a human

A pairing where the operator compared the digits across two physical screens before answering
hit `Peer did not answer in time — giving up locally` at 11:59:38.743, 32 s after accepting on
the Mac. BR-5 sets that timeout and BR-19 does not touch it, but a human cross-checking six
digits on a second device is the normal case, not a slow one. Worth a backlog item.

## Criterion 4 (task 9.4) — ping cadence, no storm

**PASS.** Four complete cycles, exactly 30 s apart, one ping and one reply in each direction:

```
11:52:02.483 Mac recv ping 1793600522519   11:52:02.568 Phone recv reply 1793600522483
11:52:02.598 Phone recv ping 1793600522524 11:52:02.571 Mac recv reply 1793600522598
11:52:32.483 / 11:52:32.575 / 11:52:32.599 / 11:52:32.573
11:53:02.485 / 11:53:02.577 / 11:53:02.630 / 11:53:02.605
11:54:02.486 / 11:54:02.577 / 11:54:02.602 / 11:54:02.576
```

The loop guard holds on real hardware in both directions: every `Received ping reply` line is
terminal — no packet follows it. Wire sizes confirm the two shapes: a plain ping reads as 52
bytes, a reply carrying `replyTo` as 75.

## Observations (not BR-19 regressions)

- **Asymmetric trust is reachable and is not surfaced.** The phone still had the Mac pinned
  (`PairingManager: Already paired with Deepak's MacBook Air`, 11:51:32.605) while the Mac's
  trust store was empty. The phone reported `Connected(verified=true)` and pushed
  `bridge.clipboard.connect`, `bridge.battery` and `bridge.notification.filter`; the Mac dropped
  every one as untrusted. Neither side tells the user why nothing works. Worth a backlog item.
- **The scan watchdog is not fed by the device-picker path.** With an empty trust store
  `BLEManager.swift:585` deliberately offers a choice instead of connecting, but the BR-13 scan
  watchdog still logged `Scan found nothing in 20s — restarting` and `Link health: outOfRange`
  while 109 `Discovered` lines for that same phone were in the log. The Mac reports "Out of
  range" about a phone it can see and is listing. Pre-existing; BR-19 does not touch discovery.
- **`SingleInstanceGuard` does not hold across two bundle paths.** `/Applications/Bridge.app`
  (pid 8898) started at 11:53:48 and ran alongside the DerivedData build (pid 6254) until killed
  by hand at 11:55:40 — no handover was logged for it, where pid 6304 at 11:48:02 did log
  `Bridge is already running (pid 6254); handing over and exiting`. So the guard caught a second
  copy of the *same* bundle and missed a different one. Two instances is precisely what BR-14
  exists to prevent, and what breaks BLE scanning for the survivor. BR-19 does not touch
  `SingleInstanceGuard`; this is a BR-14 gap this test setup exposed. Worth a backlog item.

  It did not contaminate the evidence above: pid 8898 never had an L2CAP channel
  (`sendPacket failed: no l2capChannel`), so every packet logged came from pid 6254, and
  killing 8898 left the link and the heartbeat undisturbed (pings continued at 11:56:02).

- **The two-instance hazard is real and bit this run.** After both Mac instances had been
  quit, every subsequently launched Bridge process failed to scan: no `Bluetooth powered on`
  line, zero `Discovered` lines, and `Link health: disconnected` ten seconds in — the initial
  `LinkState` value, because `startScanning()` is only reached from
  `centralManagerDidUpdateState` on `poweredOn`, which never fired. Two restarts (pids 12024 and
  12588) behaved identically while system Bluetooth reported `State: On`. This matches the
  `stop scan` / `updateScanParams:YES` poisoning described in CLAUDE.md, and recovery needed a
  Bluetooth power cycle. It follows directly from the `SingleInstanceGuard` gap above.

## Not run — reported as untested, not as passed

- **Task 9.2 — two devices pinned, a file each way. UNTESTED.** Both devices were eventually
  put on one subnet (phone 192.168.0.6, Mac 192.168.0.143, ping clean) and a decoy pin
  `00000000-dead-beef-0000-decoy0000001` was added to the Mac trust store — chosen because it
  sorts ahead of the real device id, so the old `keys.first` behaviour would have picked it.
  Four Tier 3 attempts were made, Mac→phone and phone→Mac, with a 900 KB payload.

  **Every attempt failed at the TLS layer, in both directions, with macOS Local Network
  permission ungranted.** Mac→phone: the Mac's `PayloadServer` timed out after 30 s three times
  while the phone reported `SSLHandshakeException: Read error … I/O error during system call`.
  Phone→Mac: the Mac reached `FileReceiveController: Fetching … from …:39045`, then its
  `PayloadClient` timed out after 30 s while the phone's server reported the same `SSLException`.
  macOS gates both inbound and outbound local-network traffic on that permission.

  The failure is independent of the pinning change, on three counts: the Mac's verify block
  never ran (a wrong pin would have logged `Payload peer fingerprint … does not match the pin`,
  and that line never appeared in any attempt); the failure was identical with one pin and with
  two; and it occurs symmetrically in a direction where the Mac is the client and in one where
  it is the server.

  Reaching `Fetching …` does show `pairedPeerParameters(for:)` resolving the sender's `deviceId`
  against a two-entry store without throwing, but that does not discriminate the fix — the old
  code would also have found *a* pin. Only a completed handshake discriminates, and the
  permission prevents one. The fix stays covered by unit tests on both platforms
  (`testTwoPinnedDevicesPinToTheConnectedPeer`, `testAnUnpinnedPeerIsRefusedWhileOthersArePinned`,
  `PayloadTlsTest`), which assert the named peer's pin is used and an unpinned peer refused while
  another device is pinned.

  The fix is covered by unit tests on both platforms (`testTwoPinnedDevicesPinToTheConnectedPeer`,
  `PayloadTlsTest`), which assert the named peer's pin is used and that an unpinned peer is
  refused while another device is pinned. That is not the same as a real handshake.

- **Task 9.3 — the cold-start race, five times. PARTIALLY RUN.** The digit match was confirmed
  (above), but the race itself was never reproduced: the Keychain read won every time, so the
  new deferred-confirmation path (`No local fingerprint yet — holding the confirmation`) never
  appeared in any log. It is covered only by unit tests
  (`testIdentityBeforeLocalFingerprintStillRaisesTheConfirmation`,
  `testHoldingAConfirmationAsksForTheFingerprintAgain`, and the Android equivalents). The
  five-times repeat was not performed.
