## Why

BR-4 and BR-5 (audit findings `AUD-C` and `AUD-D`) are one hole, not two. The PRD's
"Protocol and security" section promises that acceptance is "**not** automatic", that both
devices display a six-digit code derived from the two certificate fingerprints before either
pins anything, that "every subsequent connection is mutual TLS against that pinned cert", and
that "packets from an unpaired device are dropped before parsing". None of those four things is
true of the shipped code.

Verified on disk, 2026-10-29:

- `TLSWrapper.swift` and `CertificateManager.createSSLContext()` have **zero callers**. Every
  tier is plaintext.
- The six-digit code exists nowhere on either platform — `grep` returns nothing.
- `L2capServer.handlePacket` runs its trust check only `if (peerDeviceId != null &&
  peerFingerprint != null)`, so before an identity packet arrives **every** packet type is
  processed from a stranger, and the check it does run is wrapped in
  `catch (_: IllegalStateException) {}` — an uninitialised trust store fails open.
- `BridgeTrustManager` built with a null `expectedFingerprint` logs the peer's fingerprint and
  **accepts** it (`BridgeTrustManager.kt:47`). Third fail-open; BR-4's scope line does not name it.
- Three auto-accept paths: Android's `pm.onPairRequest` calls `pm.acceptPair()` unconditionally;
  the Mac's `setPeerIdentity` auto-pairs on an empty trust store; and
  `handlePairAcceptOrRequest` stages the confirmation sheet then calls `acceptIncomingPair()` on
  the next line. Its `.paired` branch also re-accepts a *changed* fingerprint.
- `reader.readLine()` is uncapped, so one peer-controlled line can exhaust the heap.

Together: anything in BLE range pairs itself and injects any packet type, with no human
touching either screen, over a channel nothing encrypts.

## What Changes

**The transport decision (BR-4's reserved call, made with the user on 2026-10-29): encrypt per
path, not all-or-nothing.** Reading the code shows exposure and cost run in opposite directions.

- **Tier 3 / payload path gets real mutual TLS with pinned fingerprints.** This is where the
  exposure actually is: `PayloadServer.kt:44` is a plain `java.net.ServerSocket` and the Mac's
  `PayloadServer` is a bare `NWListener(using: .tcp)`, so transferred file bytes cross Wi-Fi in
  the clear, readable by anyone on the LAN. It is also the cheap side —
  `CertificateManager.createSSLContext()` layers directly onto a `java.net.Socket` and finally
  gets its caller, and the Mac swaps `.tcp` for `NWParameters(tls:tcp:)` with
  `sec_protocol_options_set_verify_block` doing the pin.
- **Tier 1 / BLE L2CAP control channel gets link-layer encryption, not TLS.** Attacking it needs
  physical proximity plus sniffer hardware, and TLS there is the expensive option:
  `BluetoothSocket` is not a `java.net.Socket`, so `SSLSocketFactory` cannot wrap it and mutual
  TLS would mean hand-driving an `SSLEngine` buffer/handshake state machine. Instead switch
  `listenUsingInsecureL2capChannel()` to `listenUsingL2capChannel()`. **This is the one item
  carrying real risk** — the secure variant requires the remote device to be bonded, and
  CoreBluetooth has no explicit bond API. A spike task settles it first; if it fails, the
  channel stays plaintext and the docs say so plainly.
- **`TLSWrapper.swift` is deleted.** It is built on Secure Transport (`SSLCreateContext`,
  `SSLHandshake`), deprecated since macOS 10.15, has never run, and busy-spins —
  `while result == errSSLWouldBlock { result = SSLHandshake(ctx) }` against a non-blocking
  stream whose `tlsRead` returns `errSSLWouldBlock` on every empty read. It would peg a core the
  first time it was called. Keeping dead, broken, deprecated crypto is worse than having none.

**Default-deny everywhere (BR-4):**
- Packets from a peer that has not completed identity exchange are dropped before dispatch. Only
  `bridge.identity` and `bridge.pair` are exempt, matching the `certificate-pinning` spec's
  existing "No pinned fingerprint for the device" scenario.
- The `catch (_: IllegalStateException) {}` fail-open goes; an unreadable trust store drops.
- `BridgeTrustManager` with no pinned fingerprint **rejects** instead of logging and accepting.
- `readLine()` is replaced by a length-capped reader; a line over the cap closes the connection.

**Pairing becomes a human decision (BR-5):**
- Both sides compute the six-digit code exactly as the PRD specifies — the two fingerprints
  sorted, concatenated, hashed, reduced to six digits — and display it. Shared test vectors in
  `protocol/` keep the two implementations honest.
- **BREAKING**: all three auto-accept paths removed. Nothing pins without an explicit tap on
  both screens. A device paired by the old build keeps working (its pin is already stored); a
  *new* pairing now requires two humans-worth of taps where it previously required none.
- A fingerprint that does not match the pin closes the connection. It never silently re-pairs.
- An unpaired Mac lists discovered phones by name and RSSI and waits for a choice instead of
  connecting to the first advertiser, as the PRD's "Choosing a device" paragraph already requires.

**Docs are amended to match what ships** — README, `docs/PRD.md`, `.claude/CLAUDE.md` and the
`security/certificate-pinning` spec currently all claim blanket mutual TLS. They will describe
the per-path model instead.

## Capabilities

### New Capabilities
<!-- None. Every behaviour here belongs to a capability that already exists; adding new spec
     directories would split the pairing and trust rules across two places. -->

### Modified Capabilities

- `security/certificate-pinning`: the "Mutual TLS over L2CAP" requirement is replaced. TLS and
  fingerprint pinning move to the out-of-band payload connection; the L2CAP channel is covered
  by link-layer encryption. Adds a requirement that an uninitialised or unreadable trust store
  denies rather than permits.
- `protocol/pairing`: adds the six-digit verification code and its derivation; replaces the
  "User prompted on receiving device" scenario's implicit accept with an explicit two-sided
  confirmation; adds fingerprint-mismatch-closes-connection; removes auto-accept on an empty
  trust store.
- `ble-transport/l2cap-channel`: adds default-deny before identity exchange, the read-line
  length cap, and the secure-listen upgrade (with the documented plaintext fallback).
- `ble-transport/discovery`: adds the unpaired-Mac device picker (name + RSSI, waits for a
  choice) and the paired-Mac rule that other advertisers are ignored.
- `file-transfer/payload-channel`: the payload TCP connection is mutual TLS with the peer's
  pinned fingerprint; an unpinned or mismatched peer is refused before any bytes move.

## Impact

**Transport tiers**: Tier 1 (BLE L2CAP) — secure listen + default-deny + line cap. Tier 2
(RFCOMM) and Tier 3 (Wi-Fi LAN) — payload TLS, since both route through
`PayloadServer`/`PayloadClient`. Tier 4 (hotspot) inherits Tier 3's behaviour.

**Mac code**: `Security/TLSWrapper.swift` (deleted), `Security/PairingManager.swift`,
`Security/TrustStore.swift`, `Security/CertificateManager.swift`, `BLE/BLEManager.swift`,
`BLE/L2CAPChannel.swift`, `FileTransfer/PayloadServer.swift`, `FileTransfer/PayloadClient.swift`,
`UI/StatusPanel` (picker + code confirmation).

**Android code**: `security/CertificateManager.kt`, `security/BridgeTrustManager.kt`,
`security/PairingManager.kt`, `security/TrustStore.kt`, `ble/L2capServer.kt`,
`filetransfer/PayloadServer.kt`, `filetransfer/PayloadClient.kt`, plus a pairing-confirmation UI.

**Protocol**: no new packet types. `bridge.pair` gains a confirmation step between request and
accept. New shared test vectors in `protocol/` for the six-digit derivation.

**Permissions**: no new grants on either platform. Mac already holds Bluetooth and Local Network
(`NSLocalNetworkUsageDescription` + `NSBonjourServices`); Android already holds the
`connectedDevice` foreground service and BLE advertise. The secure-L2CAP spike may surface a
macOS system Bluetooth pairing prompt, which is a user interaction, not a declared entitlement.

**Docs**: `README.md`, `docs/PRD.md` ("Protocol and security"), `.claude/CLAUDE.md`.

**Risk**: the secure-L2CAP bonding question is the only unknown and is spiked before anything
depends on it. Removing auto-accept means a first-run pairing that silently worked now requires
deliberate taps — intended, but it changes the out-of-box path and needs a device test from a
cleared trust store on both ends.

**PRD**: "Protocol and security" (lines 169–190) and "The transport ladder" (lines 208+).
