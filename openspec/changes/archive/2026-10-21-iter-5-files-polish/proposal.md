## Why

File transfer completes the v1 feature set. The PRD identifies file movement as medium-value but essential: "Files move both ways without unlocking the phone or opening a browser." This iteration also adds the robustness and observability features needed for daily use -- the permission status panel, enhanced watchdog, and integration tests. After this iteration, Bridge is a complete daily-use tool.

## What Changes

**Protocol packets implemented:**
- `bridge.share.request` (both directions) -- filename, fileSize, plus `payloadSize` and `payloadTransferInfo` for out-of-band transfer (PRD: "Large payloads go out of band").

**File transfer -- Tier 2 (Bluetooth Classic RFCOMM):**
- Default transfer path for files under ~500 KB (screenshots, documents, small images).
- Android opens an RFCOMM server socket. Mac connects via `IOBluetoothRFCOMMChannel` or equivalent.
- Throughput: ~140 KB/s (PRD transport ladder). Adequate for small files, painful for video.
- Channel is opened for the transfer and torn down after.

**File transfer -- Tier 3 (Wi-Fi LAN via mDNS/Bonjour):**
- Used for larger files when both devices share a network.
- Sender includes a TCP port in `payloadTransferInfo`. Receiver opens a TCP connection to that port and reads `payloadSize` bytes.
- Service advertised via mDNS (`_bridge._tcp`) -- Mac's Info.plist already carries `NSBonjourServices` from iter-1.
- Throughput: full LAN speed (100+ MB/s on Wi-Fi 6E, PRD: "Target environment").
- Channel opened per transfer, torn down after. Tier 1 BLE link stays free throughout (PRD: "Why bulk is a separate channel").

**Mac changes:**
- Menu bar file drop target: drag a file onto the Bridge menu bar icon to send it to the phone.
- Receive flow: incoming `bridge.share.request` triggers a save dialog or auto-saves to a configured directory.
- **Permission status panel:** shows current state of all TCC grants -- Local Network, Bluetooth, Notifications, Accessibility (if applicable). Checked at launch. Surfaces revoked permissions clearly (PRD: "the Mac menu bar must always show connection state, last packet time, and which permissions are currently held").
- **Enhanced watchdog:** last-seen time displayed in menu bar. If no packet received within timeout, link marked dead. Alert if service appears killed by One UI battery management (PRD risk: "One UI update kills the background service").

**Android changes:**
- Receive flow: incoming `bridge.share.request` saves file and posts a notification with an open action.
- Send flow: share intent receiver -- share a file from any Android app to Bridge, which sends it to the Mac.
- Permission status display in the Bridge settings screen: Notification Access, Battery Optimization exclusion, ADB clipboard grant status.

**Integration tests:**
- End-to-end test suite covering: pairing, notification mirror and reply, clipboard sync both directions, file transfer over Tier 2 and Tier 3.
- Test vectors for all packet types (PRD: "shared test vectors").
- Reconnect stress test: automated walk-away simulation.

## Capabilities

### New Capabilities

- **`file-transfer/bluetooth`** -- File transfer over Bluetooth Classic RFCOMM (Tier 2). Used for files under ~500 KB. Channel opened per transfer, torn down after. No network required.
- **`file-transfer/wifi-lan`** -- File transfer over Wi-Fi LAN (Tier 3) via mDNS service discovery and TCP. Used for larger files when both devices share a network. Full LAN throughput.
- **`file-transfer/payload-channel`** -- Out-of-band payload mechanism from the KDE Connect protocol. `bridge.share.request` carries `payloadSize` and `payloadTransferInfo` (port). Receiver opens a separate connection for the bytes, keeping the control channel (Tier 1) free for notifications and clipboard during transfer.
- **`ui/permission-panel`** -- Mac menu bar panel showing all permission states (Local Network, Bluetooth, Notifications, Accessibility). Android settings screen showing Notification Access, Battery Optimization, ADB grant status. Both check on launch and surface revoked permissions.
- **`reliability/watchdog`** -- Enhanced watchdog with last-seen time in menu bar. Detects silent service death on Android (One UI battery kill). Provides actionable guidance when permissions are lost or service is killed.

### Modified Capabilities

None. Builds on the secure channel and existing packet infrastructure from iter-2 through iter-4.

## Impact

- **Completes v1.** After this iteration, Bridge covers: notifications (mirror + reply), clipboard (bidirectional), file transfer (bidirectional), battery status, and robust connection management. This matches the PRD's v1 feature catalogue.
- **Tier 4 (phone hotspot) is deferred to v2.** The PRD marks it as the "most likely to disappoint" due to the `associate` admin-password question. Tier 2 and Tier 3 cover the common cases; USB cable remains the fallback for large files without a shared network.
- **Permission surface expands:** file transfer over Wi-Fi LAN triggers the Local Network permission prompt on Mac if not already granted. Android share-intent receiver requires no additional permissions.
- **The permission panel is critical for the silent-failure problem** (PRD: "Every one of these presents to the user as nothing happening"). It surfaces TCC revocations from macOS updates and One UI battery kills that would otherwise be invisible.
- **After this iteration, the PRD's success criteria can be fully validated:** notification latency under 2 seconds, clipboard latency under 1 second, both with Wi-Fi off, and the tool does not need manual restart more than once a week.
