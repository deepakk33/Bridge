## Context

See proposal.md — Why. What is actually known, separated from what is guessed:

**Observed.** 2026-11-02, BR-16's device test: Mac→phone, 640031 B, Tier 3. The phone logged
`FileReceiver: Receiving br16-tier3.txt (640031 bytes) from 192.168.0.143:54334`, so the share
request, the host and the port all arrived intact and the phone opened TCP. The handshake then
failed with `SSLHandshakeException: Read error … I/O error during system call, Success` at
`PayloadClient.connect` → `startHandshake`. That text is an EOF: the peer went away mid-handshake.
Twice, identically, before and after a BLE restart. BR-19 saw the mirror image phone→Mac, with the
Mac reporting `PayloadClient timed out after 30s`, and Mac-serving attempts reporting
`PayloadServer timed out after 30s`.

**The decisive negative.** `PayloadTLS.parameters(identity:pinnedFingerprint:)` installs a
`sec_protocol_options_set_verify_block` that logs on both the no-certificate and the
mismatch path. **It has never logged.** So on the Mac the connection does not reach peer
verification. Combined with the server reporting a timeout rather than a rejection, the Mac's
listener is not completing — plausibly not accepting at all.

**What is set up on each side.** Mac: `NWParameters(tls:tcp:)` with a local identity,
`peer_authentication_required = true`, and the verify block above. Android:
`SSLContext.getInstance("TLSv1.2")` initialised with `KeyManagerFactory` over the AndroidKeyStore
plus a `BridgeTrustManager`; `PayloadServer` sets `needClientAuth = true`; `PayloadClient` layers
`SSLSocket` over an already-connected plain socket and forces `startHandshake()`.

**The confounder named in the backlog.** `PermissionChecker.setLocalNetwork(_:)` has no callers, so
the Mac's Local Network row reads "Not Requested" regardless of the real authorization. macOS gates
local-network listening and connecting behind that permission. A denied permission produces exactly
the observed signature: TCP appears to connect, no data flows, both sides time out, and no
application-level code runs. This misdirected the first diagnosis once already.

## Goals / Non-Goals

**Goals:**

- Establish the cause from evidence before changing TLS code.
- A byte-identical Tier 3 transfer in both directions on hardware.
- Close the four security holes on the same path, in the same device-test pass.

**Non-Goals:**

- Tier 2. It is also broken — `TierRouter` routes anything under 512 KB to Tier 2 with `port: nil`
  and the receiver rejects it as "missing payload info" — but fixing it is not this item. Recorded
  in the proposal.
- Rebuilding the Local Network permission *reporting* UI. That is BR-11. This item takes only what
  Tier 3 needs in order to work, and says where the boundary fell.
- Encrypting the BLE control channel. Untouched since BR-4+BR-5.
- No new transport, no new packet type, no protocol redesign.

## Decisions

### Diagnose first, in a fixed order, and record the finding before writing a fix

The handshake is not yet understood, so the plan does not pre-commit to a cause. Tasks run in this
order, stopping at the first that explains the failure:

1. **Rule Local Network in or out.** Query the real authorization on the Mac rather than the lying
   UI row, and prove the socket behaviour independently of Bridge — a plain listener on the Mac
   reached from the phone, and the reverse. This is first precisely because it has already
   misdirected this diagnosis once, and because every other hypothesis is expensive to test.
2. **Only if the network path is clean, instrument the handshake.** Log on the Mac at accept, and
   enable TLS diagnostics on both sides, so the question "did the listener accept at all" has a
   recorded answer rather than an inference.
3. **Then fix what the evidence names**, and record the cause in the change before the fix lands.

*Alternative considered — change the TLS configuration and retest.* The obvious candidates
(Android pinning `TLSv1.2` against a Network.framework peer; mutual auth under TLS 1.3) are cheap
to try and tempting. Rejected as a starting point: with the verify block never firing, there is no
evidence the failure is in TLS at all, and a change that happens to work without a known cause
leaves the next failure undiagnosable. The permission test is cheaper and falsifiable.

### The payload port is restricted by pinned identity, not by arrival order

Both servers already require a client certificate (`needClientAuth` on Android,
`peer_authentication_required` on the Mac). The hole is that the Mac's verify block pins against
the fingerprint passed in, while the listener still serves whichever connection completes first.
The fix keeps the existing shape — the fingerprint of the peer that asked is already threaded
through `pairedPeerParameters(for:)` — and makes a non-matching peer get nothing, rather than
adding a token scheme.

*Alternative considered — a one-time token on the control channel.* More code, a new field, and it
re-implements at the application layer what the pinned certificate already proves. Only worth it if
the diagnosis shows certificate-based restriction cannot work here.

### Size cap and filename reduction happen at the trust boundary

Both checks belong where the peer's values first enter: the `share.request` handler. The cap is
refused before a port is opened or a file created; the filename is reduced to a basename before it
ever reaches `appendingPathComponent`. Doing either later means a resource is already committed.

The cap's value is a decision the device test should inform, not a constant invented here; the
tasks record it once measured.

### Announce-before-write uses each platform's existing affordance

The Mac already posts a notification after a transfer and the phone already has `TransferNotifier`.
Announcing moves that to before the write and adds a decline, rather than introducing a new UI
surface.

## Risks / Trade-offs

- **The diagnosis may land outside this item's scope** — for example, that Tier 3 cannot work until
  the Local Network permission is requested properly, which is BR-11's reporting work. → The plan
  stops and surfaces the boundary rather than quietly absorbing BR-11; the first task's output is a
  recorded finding, not a fix.
- **Announce-before-write adds a round trip and a way to stall the sender.** → The decline path must
  close the sender's window; a declined file must not leave the Mac's listener open for its full
  timeout.
- **A size cap can refuse a file the user wanted.** → The cap is surfaced with the refusal, so the
  limit is visible rather than silent.
- **The device test is expensive and exclusive**: one window, both devices, no second `Bridge.app`.
  Checksums both directions, so the test cannot be satisfied by "a file appeared".
- **Known trap from BR-16, worth repeating**: each DerivedData build of the Mac app carries its own
  self-signed certificate, so switching build directories makes the phone reject the link on a pin
  mismatch with no pairing prompt — it reads like a hang. Clear
  `shared_prefs/bridge_trust_store.xml` via `run-as` rather than `pm clear`, which keeps the
  runtime permissions.

## Open Questions

None that change the specs, the approach or the task breakdown. The cause of the handshake failure
is unknown, but the first three tasks exist to determine it, and the order they run in is decided.
