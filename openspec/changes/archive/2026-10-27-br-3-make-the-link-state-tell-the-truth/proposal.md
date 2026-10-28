## Why

The Mac's link display is wrong in four separate ways, and all four hide exactly the failure
the PRD calls the product's #1 risk — *"Every one of these presents to the user as nothing
happening."* A phone on the desk whose Bridge service One UI has killed renders as **"Out of
range"**; a silent drop leaves **"Connected"** and a live battery percentage on screen for up
to 120 s; every `sendPacket` reports success, so the "Reply could not be delivered" notification
that was written for precisely that window can never fire; and the Mac says **"Connected"** the
instant an L2CAP socket opens, before identity, before any trust check, while Android refuses
to say the same word until the peer is verified.

`LinkHealth` and its `guidance(for:)` copy already exist, already carry the right words, and
have zero callers. This change wires the truth that is already written to the screen.

PRD: §Risks ("silent failure"), §Observability, §Goal 1 (resident link). Transport tier: Tier 1
(BLE L2CAP) only. No new permissions on either platform.

## What Changes

- Publish a `linkHealth` value off `BLEManager`, derived from Bluetooth power, peripheral
  connection state, peer verification and last-packet time, and make `StatusPanel` and the menu
  bar icon switch on it instead of on the raw four-case `LinkState`.
- Distinguish a dead phone service from a phone that walked away: packets stopped while the BLE
  peripheral is still connected, or a remote-terminated disconnect, reads as **"Not responding"**
  with the restart guidance; a supervision-timeout disconnect reads as **"Out of range"**.
- Add a `.verifying` health state so an open-but-unverified socket is never reported as
  connected, matching Android's `LinkState.resolve`. **BREAKING** (user-visible): the Mac no
  longer shows "Connected" at channel-open time.
- Re-evaluate health on a short tick (10 s) so a silent death surfaces as stale within ~60 s
  rather than waiting on the 90 s watchdog's 30 s poll. Ping/stale/dead thresholds themselves
  (30/60/90 s) are unchanged, so no protocol or battery behaviour changes.
- Make sending report the truth: `L2CAPChannel.send` returns whether the bytes actually reached
  the stream (handling partial writes), and `BLEManager.sendPacket` returns false when there is
  no channel, the stream refuses the write, or the peer is not verified — so
  `postDeliveryFailure()` fires for the case it was written for.
- Suppress the battery reading and the mirroring summary while health is `.dead`, `.stale` or
  `.verifying` — a stale number read as live is the same lie in a smaller font.

## Capabilities

### New Capabilities
- `link/health`: the Mac's honest classification of the link — the states, what distinguishes
  them, what each one tells the user to do, and the rule that the Mac never renders peer data it
  cannot currently vouch for.

### Modified Capabilities
- `ui/mac-status-panel`: "Three connection states in menu bar" is replaced — the panel and the
  menu bar icon render the full health set (connected, verifying, stale, not responding, out of
  range, Bluetooth off, disconnected) and show the guidance line for a problem state.
- `reliability/watchdog`: adds the discrimination rule between a dead service and an out-of-range
  phone (the BLE disconnect event alone is not sufficient — the existing spec assumed it was),
  and the re-evaluation cadence that bounds how long a stale "Connected" can be shown.
- `link/reconnect`: the link state machine reaches `connected` only after the peer is verified
  against the trust store, not when the L2CAP channel opens.
- `ble-transport/l2cap-channel`: a send reports whether the bytes were written; a failed or
  partial write is a failure, not a silent log line.

## Impact

Mac only — no Android or protocol code changes, no new packet types, no wire-format change.

- `mac/Bridge/Sources/Bridge/BLE/LinkHealth.swift` — add `.verifying`, add disconnect-reason
  input to `evaluate`, extend `guidance(for:)`.
- `mac/Bridge/Sources/Bridge/BLE/BLEManager.swift` — publish `linkHealth`, track
  `peripheralConnected` / `isVerified` / last disconnect reason, gate `.connected` on
  verification, return real send results, drive the re-evaluation tick.
- `mac/Bridge/Sources/Bridge/BLE/L2CAPChannel.swift` — `send` returns `Bool`, `writeToStream`
  loops on partial writes.
- `mac/Bridge/Sources/Bridge/UI/StatusPanel.swift`, `BridgeApp.swift` — switch on health, render
  guidance, hide unvouched data.
- `mac/Bridge/Tests/BridgeTests/` — unit tests for the evaluator's new inputs and for the send
  result; existing `LinkStateMachine` tests keep passing (the machine is unchanged apart from
  when `.connected` is entered).

No new dependencies. No Mac TCC grants beyond the existing Bluetooth and Notifications. No
Android permissions.
