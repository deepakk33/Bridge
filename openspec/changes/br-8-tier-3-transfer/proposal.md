## Why

Tier 3 — the Wi-Fi LAN payload path — has never completed a transfer on hardware, and the same
code path has four unguarded security holes. They are one item because they are one file set:
splitting them means editing `FileReceiveController`, `PayloadServer`/`PayloadClient` and
`PayloadTLS` twice and running the expensive two-device test twice. Nothing about the hardening
can be verified while the handshake fails.

**Broken.** Confirmed again during BR-16's device test, 2026-11-02: the Mac sent a valid
`bridge.share.request`, the phone reached `FileReceiver: Receiving br16-tier3.txt (640031 bytes)
from 192.168.0.143:54334`, opened TCP, and then died in
`SSLHandshakeException: Read error … I/O error during system call` — an EOF during the handshake.
Identical failure before and after a BLE restart, so it is not BR-16's lost handler. BR-19's
earlier attempts failed the same way in both directions, with the Mac reporting
`PayloadServer timed out after 30s`. The Mac's TLS verify block **never logs**, so the connection
dies before certificates are exchanged: this is protocol- or platform-level setup, not pinning.

**Unsafe.** `FileReceiveController` passes the peer's filename straight to
`appendingPathComponent`, so `../../.zshenv` escapes `~/Downloads`. `payloadSize` is unbounded.
`PayloadServer`'s own comment says it serves "the first connection that arrives", so on a shared
network any LAN host reaching the port inside the window gets the file. Both sides write without
announcing, and a failed receive on the Mac is logged and nothing more.

PRD: Tier 3 (Wi-Fi LAN out-of-band payload), mutual TLS with pinned self-signed certificates.

## What Changes

- **Diagnose before fixing.** The first task rules macOS **Local Network** permission in or out,
  because `PermissionChecker.setLocalNetwork(_:)` has no callers and the UI row reports
  "Not Requested" whatever the truth is — it misdirected the first pass of this diagnosis. Only
  then is the TLS handshake instrumented on both sides. The fix follows the finding; this proposal
  does not pre-commit to a cause.
- **Tier 3 completes in both directions**, with the received bytes checksum-equal to the sent
  bytes.
- **Incoming filenames are reduced to a basename**; anything with a path separator is flattened or
  refused. A received file never lands outside the download directory.
- **`payloadSize` is bounded** and a payload declaring more than the cap is refused before a byte
  is written or a port is opened.
- **The payload port serves only the paired peer** that asked for the transfer, not whoever
  connects first.
- **An incoming file is announced before it is written, with a way to decline**, and a failed
  receive tells the user instead of only the log.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `file-transfer/payload-channel`: the handshake must complete against a pinned peer on real
  hardware; the port must serve only that peer; `payloadSize` must be bounded; a failed receive
  must be surfaced.
- `file-transfer/wifi-lan`: Tier 3 must deliver a byte-identical file in both directions, and an
  incoming file must be announced before it is written, with a decline.

## Impact

**Mac:** `FileTransfer/FileReceiveController.swift` (filename, size cap, announce, failure
surfacing), `FileTransfer/PayloadServer.swift` (peer-restricted serving),
`FileTransfer/PayloadClient.swift`, `FileTransfer/PayloadTLS.swift` (handshake instrumentation),
and `UI/` for the announce/decline affordance.

**Android:** `filetransfer/FileReceiver.kt`, `filetransfer/PayloadServer.kt` (already sets
`needClientAuth`), `filetransfer/PayloadClient.kt`, `filetransfer/PayloadTls.kt`,
`security/CertificateManager.kt` (`createSSLContext` pins `TLSv1.2`).

**Transport tiers:** Tier 3 is the subject. Tier 1 (BLE control) carries the `share.request` and is
untouched. Tier 2 is out of scope, but note it is also broken: `TierRouter` routes anything under
512 KB to Tier 2 with `port: nil`, which the receiver rejects as "missing payload info" — recorded,
not fixed here.

**Permissions:** macOS **Local Network** is the prime suspect and must be ruled in or out.
`NSLocalNetworkUsageDescription` and `NSBonjourServices` already exist. If the fix requires the
permission to be requested and reported properly, the *reporting* belongs to BR-11 — this item
takes only what Tier 3 needs to work, and says so.

**Dependencies:** none added. BR-16 shipped, so the `share.request` now reliably reaches the
phone's handler — the precondition this item was blocked on.
