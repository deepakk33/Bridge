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

## Finding (in progress, 2026-11-02)

### Ruled out

**The network path is clean.** Plain TCP was proved in both directions with the real devices on
one LAN (Mac `192.168.0.143`, phone `192.168.0.6`): a Python listener on the Mac accepted from the
phone and delivered bytes, and `nc` from the Mac delivered bytes to a listener on the phone. No AP
isolation, no firewall drop, and macOS is not blocking local network traffic for a terminal
process.

**BR-16's device test was not a valid Tier 3 test.** The phone's Wi-Fi was off — `wlan0` had no
address and the only route was `rmnet_data0` (mobile data, `192.0.0.2`) — so it had no path to the
Mac's LAN address at all. That failure says nothing about Tier 3 and is discarded as evidence.
BR-19's attempts, which recorded both devices on `192.168.0.x` with a clean ping, remain valid.

### Reproduced properly

Phone→Mac, 716800 B, both devices on one LAN, driven over adb through `ShareReceiverActivity`:

| time | side | event |
|---|---|---|
| 16:04:16.429 | phone | `Wi-Fi payload server on 192.168.0.6:45995`, `sendPacket: bridge.share.request` |
| 16:04:16.437 | Mac | `Incoming file … payloadSize=716800 port=45995` |
| 16:04:16.457 | Mac | `Fetching … from …:45995` |
| 16:04:17.949 | phone | `SSLHandshakeException: Read error … I/O error during system call` |
| 16:04:47.962 | Mac | `Payload client timed out after 30.000000s` |

### Settled by packet capture, 2026-11-02 16:09

`sudo tcpdump -i en0`, Mac serving (`192.168.0.143:54336`), phone fetching (`192.168.0.6:43586`):

```
16:09:31.978  phone > Mac   [S]            SYN
16:09:31.979  Mac   > phone [S.]           SYN-ACK
16:09:31.987  phone > Mac   [.]            ACK          — TCP established
16:09:31.987  phone > Mac   [P.] len 138   ClientHello
16:09:31.993  Mac   > phone [P.] len 656   server flight
16:09:32.006  phone > Mac   [.] ack 657    phone ACKs the whole flight
16:09:32.028  phone > Mac   [F.]           phone closes — 22 ms later, no TLS alert
16:09:32.028  Mac   > phone [R.]           reset
```

**Ruled out: macOS Local Network authorization.** TCP completes and 656 bytes flow from the Mac
to the phone. Nothing is being blocked. The Privacy & Security pane lists no entry for Bridge,
consistent with the permission never being the obstacle. The earlier `waiting`-state reading came
from the pre-Wi-Fi run and does not describe this one.

**Localised: the phone's TLS client aborts on the Mac's first flight.** It ACKs all 656 bytes,
then closes 22 ms later with a bare FIN and **no TLS alert**. A bare FIN is the application
closing the socket, which is exactly what `PayloadClient.connect()`'s catch block does — so the
phone threw during `startHandshake()` and tore the socket down. That matches the phone-side
`SSLHandshakeException: Read error … I/O error during system call, Success`.

The size of the flights says the two negotiated TLS 1.2: 656 bytes is a TLS 1.2
ServerHello + Certificate + ServerKeyExchange + CertificateRequest + ServerHelloDone with a small
EC certificate, and a 138-byte ClientHello is a restricted TLS 1.2 offer — consistent with
`CertificateManager.createSSLContext` pinning `SSLContext.getInstance("TLSv1.2")`.

The Mac's verify block never logging is now explained rather than mysterious: the Mac never
receives a client certificate, because the phone quits before sending one.

**So the fault is on the phone's side of client authentication**, in responding to the Mac's
`CertificateRequest`. Candidates, in order of likelihood, none yet confirmed:

1. The phone's `KeyManager` finds no acceptable client certificate — the Mac's `CertificateRequest`
   carries acceptable-CA names, and the phone's self-signed certificate matches none of them, so
   conscrypt selects nothing and the handshake fails locally.
2. A signature-algorithm mismatch for `CertificateVerify`: the AndroidKeyStore key is EC P-256
   restricted to `DIGEST_SHA256`, and the Mac's request may not offer a compatible algorithm.
3. The AndroidKeyStore private key cannot sign in this context.

### Root cause — confirmed 2026-11-02 16:28

The phone's AndroidKeyStore private key **cannot sign during a TLS handshake**:

```
java.security.InvalidKeyException: Keystore operation failed
  at android.security.keystore2.AndroidKeyStoreSignatureSpiBase.engineInitSign
  at com.android.org.conscrypt.CryptoUpcalls.signDigestWithPrivateKey
  at com.android.org.conscrypt.CryptoUpcalls.ecSignDigestWithPrivateKey
  at com.android.org.conscrypt.NativeCrypto.ENGINE_SSL_read_direct
```

`CertificateManager.generateCertificate()` authorises the key for SHA-256 only:

```kotlin
.setDigests(KeyProperties.DIGEST_SHA256)
```

Conscrypt does not ask the key to hash-and-sign. It hashes the handshake transcript itself and
asks the key to sign the **raw digest** — `NONEwithECDSA`, via `ecSignDigestWithPrivateKey`.
AndroidKeyStore enforces the authorised digest list and refuses the operation, because
`KeyProperties.DIGEST_NONE` is not among them. The key's authorisations are fixed at generation
and cannot be amended afterwards.

### How this explains every observation

- **Phone as TLS server sends zero bytes** (openssl: "read 0 bytes and written 196 bytes"): it
  fails signing its ServerKeyExchange, so no ServerHello flight is ever produced.
- **Phone as TLS client aborts right after the Mac's `CertificateRequest`** (the packet capture:
  ACKs 656 bytes, then a bare FIN 22 ms later, no alert): it fails signing `CertificateVerify`.
- **The Mac's verify block never logs**: it never receives a client certificate, because the phone
  cannot produce the signature that accompanies one.
- **Both directions fail**, as BR-19 recorded, because both require the phone to sign something.
- **Disabling `needClientAuth` on the phone changed nothing** (it failed in 72 ms rather than
  1.5 s): the phone still has to sign its own ServerKeyExchange, so removing the client-certificate
  demand cannot help.
- **The BLE control channel is unaffected**: it is unencrypted, and pairing compares certificate
  *fingerprints* — reading and hashing a certificate never exercises the private key.

### There are two bugs, not one — confirmed 2026-11-02 17:36

With the signing fix installed and the devices freshly paired, phone→Mac was retried. The
`InvalidKeyException` is gone, and the failure changed:

- phone: `SocketTimeoutException: Poll timed out` at `socketAccept`, exactly 30 s after binding —
  **no TCP connection ever arrived**.
- Mac: `Payload client timed out after 30.000000s`.

So the two directions fail for different reasons:

| direction | who dials out | status |
|---|---|---|
| Mac serves, phone fetches | phone dials out | TCP connects (proved by packet capture); **was** killed by the signing bug, now fixed |
| phone serves, Mac fetches | Mac dials out | TCP never establishes at all |

**Bug 1 — the phone could not sign (fixed).** Covered above.

**Bug 2 — the Mac's outbound connection to a LAN address never establishes (open).** This is what
the earlier Network.framework log described: `waiting` under a satisfied path, cancelled at the
30 s timeout. It is confined to the direction where the Mac dials out, which is exactly what
macOS Local Network authorization gates. The terminal `nc` test does not contradict this: that
permission is per-application, and it proved only that Terminal holds it.

The user reports no `Bridge` entry under Privacy & Security → Local Network, which is consistent
with the request never being raised rather than with it being granted.

**Next test, and it is cheap:** Mac→phone. With bug 1 fixed, the phone dials out (unaffected by
the Mac's permission) and can now sign, so that direction should complete end to end. If it does,
bug 2 is isolated to the Mac's outbound path and nothing else remains in doubt.

### Bug 2 was a second Bridge instance, not Local Network — 2026-11-02 17:39

The Local Network hypothesis above is **wrong**, and the record should say so rather than read as
if it led anywhere. Two Mac instances had been running throughout the whole diagnosis:

```
75040  …/DerivedData/…/Debug/Bridge.app     the build under test
81710  /Applications/Bridge.app             a second, installed copy
```

That is BR-21 exactly — the single-instance guard catches a second copy of the same bundle but
misses `/Applications/Bridge.app` beside a DerivedData build — and it is the hazard `CLAUDE.md`
warns about. Killing the `/Applications` copy and changing nothing else made phone→Mac work on
the next attempt:

```
Mac: Payload client connected
Mac: Receiving …: 4%
Mac: Saved file to …
```

So the Mac's outbound connection was never gated by macOS. It was being broken by the other
instance. Every symptom attributed to Local Network authorization — the `waiting` state under a
satisfied path, the 30 s timeouts, the absent Privacy pane entry — is explained by this instead,
and the pane was empty because the permission was never the obstacle.

**Testing note worth keeping**: this contaminated hours of diagnosis. Check the instance count
before trusting any Mac-side transport result: `pgrep -fc "Bridge.app/Contents/MacOS/Bridge"`.

### Both directions now transfer, verified by checksum

| direction | result |
|---|---|
| Mac→phone, 716800 B | `Served 716800 bytes` / `Saved br8-tier3.bin to Downloads`, sha256 `8b04913d…` on both sides |
| phone→Mac, 716800 B | `Payload client connected` / `Saved file`, sha256 `8b04913d…` on both sides |

Tier 3 works. It needed one code fix (the signing authorisation) and one operational condition
(a single Mac instance), and the second is BR-11's to fix properly.

### The fix, and the thing it breaks

Add `KeyProperties.DIGEST_NONE` to the generated key's authorised digests, keeping
`DIGEST_SHA256`. That is a one-line change, but it only takes effect for a **newly generated**
key: existing keys keep their original authorisations for life.

So the fix requires regenerating the phone's key pair, which changes its certificate fingerprint,
which **invalidates every existing pairing**. Both devices must re-pair once after upgrading.
That is a user-visible migration and is decided with the user, not assumed — see the tasks.

## Open Questions

**How the key regeneration is rolled out.** The fix invalidates existing pairings. Whether the app
detects an old key and regenerates automatically, or tells the user to unpair and re-pair, is a
product decision taken with the user before the fix lands.
