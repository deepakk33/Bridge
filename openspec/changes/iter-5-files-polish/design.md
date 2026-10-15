## Context

Iterations 1 through 4 delivered the BLE link, secure pairing, notification mirror and reply, and bidirectional clipboard. All core features work. This iteration adds file transfer (the last v1 feature) and the robustness layer needed for daily use: a permission status panel and an enhanced watchdog. After this iteration, Bridge is a complete daily-use tool that meets every PRD success criterion.

Constraints from PRD:
- macOS 26 requires NSLocalNetworkUsageDescription + NSBonjourServices for LAN access (silent denial otherwise)
- Android 16 foreground service type `connectedDevice` must be started while app is in foreground
- Self-signed code signing certificate keeps TCC grants stable across rebuilds
- One UI battery management will kill background services aggressively — watchdog must detect this
- Out-of-band payload transfer is required for any bulk data (PRD: "A large file on the tier 1 link would block notifications behind it for minutes at BLE speeds")

## Goals / Non-Goals

**Goals:**
- Bidirectional file transfer over Bluetooth Classic (Tier 2) and Wi-Fi LAN (Tier 3)
- Out-of-band payload channel that keeps Tier 1 BLE free during transfers
- Automatic file size routing: <500 KB to Tier 2, >=500 KB to Tier 3 with Tier 2 fallback
- Mac menu bar file drop target for low-friction sending
- Android share intent receiver for sending files from any app
- Permission status panel on both platforms
- Enhanced watchdog with heartbeat ping and last-seen time
- Integration test suite covering the full v1 feature set

**Non-Goals:**
- Tier 4 (phone hotspot) — deferred to v2, most likely to disappoint due to `associate` admin-password question
- File transfer resume or chunked retry — transfers are short enough that restart-from-zero is acceptable
- File transfer history or queue UI — single transfer at a time, sequential if multiple files
- Encrypted payload channel over LAN — devices are on a trusted local network and paired via mutual TLS

## Decisions

### 1. Tier 2 (Bluetooth Classic RFCOMM) for small files
**Choice:** Files under 500 KB transfer over Bluetooth Classic, not Wi-Fi.
**Why:** No network required. Works anywhere the two devices are in proximity, including on a plane or train. At ~140 KB/s a 500 KB file takes ~3.5 seconds, which is acceptable. The common case — a screenshot, a small document, a photo — fits comfortably. RFCOMM is a stream socket (no BLE MTU fragmentation), so the implementation is straightforward read/write.
**Alternative:** All files over Wi-Fi LAN — rejected because it requires a shared network and would make file transfer unavailable in the most common Bridge scenario (phone on mobile data, Mac on Wi-Fi).

### 2. mDNS/Bonjour for Tier 3 service discovery
**Choice:** The sender advertises `_bridge._tcp` via mDNS. The receiver discovers it and connects.
**Why:** Both platforms have native mDNS support (Network.framework on Mac, NsdManager on Android). The Mac's Info.plist already declares `NSBonjourServices` with `_bridge._tcp` from iter-1. mDNS is zero-configuration — no IP address exchange needed. This is exactly how AirDrop and Quick Share discover transfer endpoints.
**Alternative:** Exchange IP addresses over the BLE control channel — workable but fragile (IP can change, NAT complications). mDNS handles all of this transparently.

### 3. File drop target in Mac menu bar
**Choice:** The user drags a file onto the Bridge menu bar icon to send it to the phone.
**Why:** Lowest friction path. No file picker dialog, no separate window. The menu bar icon is always visible. macOS supports drop targets on NSStatusBarButton natively. This matches the mental model: "drop it on Bridge, it goes to the phone."
**Alternative:** File picker via the dropdown menu — kept as a secondary path, but the drop target is primary.

### 4. Watchdog with last-seen time
**Choice:** Heartbeat ping every 30 seconds when idle. Last-seen time in menu bar. Dead after 90 seconds of silence.
**Why:** Silent failures are the #1 risk (PRD: "Every one of these presents to the user as nothing happening"). The last-seen time is the single most important piece of observability. If the user glances at the menu bar and sees "Last seen: 12m ago," they know something is wrong before they notice a missing notification. The 30-second heartbeat is cheap (one JSON line) and the 3x timeout avoids false positives from momentary BLE hiccups.
**Alternative:** Only detect on BLE disconnect — misses the case where BLE stays connected but the Android service was killed by One UI battery management (the packet stream stops but the link does not drop).

### 5. Out-of-band payload channel via payloadSize + payloadTransferInfo
**Choice:** `bridge.share.request` carries `payloadSize` (byte count) and `payloadTransferInfo` (JSON object with `port`). The sender listens on that TCP port. The receiver opens a connection and reads exactly `payloadSize` bytes.
**Why:** This is the KDE Connect protocol's payload mechanism. It keeps the control channel (Tier 1 BLE) completely free during file transfer, so a notification arriving mid-copy is delivered instantly. The sender binds an ephemeral port, includes it in the packet, and the receiver connects. One TCP connection per file, torn down after. Sequential transfers (no concurrent connections) for simplicity.
**How it works:**
1. Sender binds a TCP server socket on an ephemeral port.
2. Sender sends `bridge.share.request` with `filename`, `payloadSize`, and `payloadTransferInfo: {"port": <port>}` over the control channel.
3. Receiver receives the request, opens a TCP connection to the sender's IP on the advertised port.
4. Receiver reads exactly `payloadSize` bytes from the TCP stream.
5. Both sides close the TCP connection.
6. Sender closes the server socket.

### 6. File size routing: 500 KB threshold
**Choice:** Files under 500 KB (512000 bytes) route to Tier 2 (RFCOMM). Files of 500 KB and above route to Tier 3 (Wi-Fi LAN) when available, falling back to Tier 2 otherwise.
**Why:** 500 KB at 140 KB/s takes ~3.5 seconds, which is the upper bound of acceptable for a "quick share." Above that, the user expects speed, and Tier 3 delivers 100+ MB/s. The threshold is a constant, not a setting — no reason to make the user think about it.
**Fallback:** When Tier 3 is unavailable (no shared network), large files fall back to Tier 2 with a warning. A 10 MB file over RFCOMM takes ~71 seconds — painful but functional. USB cable remains the real fallback for video.

### 7. Permission panel shows all TCC states
**Choice:** A dedicated section in the Mac menu bar dropdown showing every permission Bridge needs, with current grant status and fix buttons.
**Why:** The PRD calls out that "macOS point release resets TCC grants" is a medium-likelihood risk with silent impact. A permission panel checked at launch catches these immediately. The panel shows Bluetooth, Local Network, and Notifications — each with granted/denied/not-requested status and a direct link to the relevant System Settings pane.
**Android equivalent:** The Bridge settings screen shows Notification Access, Battery Optimization exclusion, and ADB clipboard grant status, with buttons to open the relevant system settings.

## Risks / Trade-offs

| Risk | Mitigation |
|------|-----------|
| RFCOMM throughput lower than expected on S25 | Measure in first task; threshold is adjustable constant |
| mDNS discovery slow or unreliable on some routers | Fallback to IP exchange over BLE control channel; test on home network |
| macOS Local Network permission silently denied | Probe on first launch (already in iter-1 Info.plist); permission panel catches post-update revocations |
| One UI kills service during file transfer | Watchdog detects within 90s; user guidance to set Unrestricted battery mode |
| Large file over Tier 2 fallback frustrates user | Clear warning with estimated time; suggest USB cable for >10 MB without Wi-Fi |
| Concurrent transfers cause resource exhaustion | Sequential transfers only; queue additional files |
