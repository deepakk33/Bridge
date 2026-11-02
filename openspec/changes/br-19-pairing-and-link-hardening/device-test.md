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
- A second Bridge instance (pid 8898) briefly logged `Link health: outOfRange` at 11:53:51
  before exiting. BR-14's guard handled it, but the instance got far enough to evaluate health.

## Not yet run

- Task 9.2 — two devices pinned, file transfer each way.
- Task 9.3 — cold-start race between the identity packet and the Keychain read, five times.
