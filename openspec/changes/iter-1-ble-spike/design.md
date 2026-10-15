## Context

First iteration. Nothing exists yet. Must prove BLE L2CAP works between Swift (Mac central) and Kotlin (Android peripheral) before building anything on top. See proposal.md for motivation.

Constraints from PRD:
- macOS 26 requires NSBluetoothAlwaysUsageDescription in Info.plist
- Android 16 requires BLUETOOTH_ADVERTISE + BLUETOOTH_CONNECT runtime permissions
- Foreground service must be type `connectedDevice` and started while app is in foreground
- Self-signed code signing certificate keeps TCC grants stable across rebuilds

## Goals / Non-Goals

**Goals:**
- Xcode project that builds a menu bar-only Mac app with Core Bluetooth central
- Gradle project that builds an Android app with BLE peripheral in a foreground service
- L2CAP channel carrying newline-delimited JSON both ways
- Measured throughput (target: >30 KB/s)
- Validated auto-reconnect via pending connect (walk away, come back)
- Foundation for all future iterations

**Non-Goals:**
- No encryption or pairing (iteration 2)
- No real packet handlers beyond ping
- No UI beyond basic menu bar icon (Mac) and foreground notification (Android)
- No Wi-Fi or Bluetooth Classic transport

## Decisions

### 1. Mac as BLE central, phone as peripheral
**Choice:** Mac scans and connects; phone advertises.
**Why:** CBCentralManager's `connect()` has no timeout — it stays pending until the peripheral appears. This gives free auto-reconnect with no timer code. The phone's BluetoothLeAdvertiser naturally restarts on disconnect.
**Alternative:** Phone as central — rejected because Android's connect has a timeout and requires explicit retry logic.

### 2. L2CAP over GATT notifications
**Choice:** Use L2CAP CoC (Connection-oriented Channel) for data, GATT only for discovery (identity + PSM).
**Why:** L2CAP gives a stream socket with larger MTU (~500+ bytes vs GATT's 512 limit), no characteristic write overhead, and better throughput. GATT notifications would require chunking every message.
**Alternative:** GATT notify/indicate — rejected for throughput reasons, but kept for the identity characteristic which is read once.

### 3. Single Bridge service UUID
**Choice:** One service UUID for discovery, with characteristics for identity and L2CAP PSM.
**Why:** Simple. One scan filter, one service. The PSM characteristic is read once to know where to open the L2CAP channel.
**Alternative:** Multiple services — unnecessary complexity at this stage.

### 4. JSON codec hand-rolled, not generated
**Choice:** Manual JSON serialization with Foundation (Mac) and kotlinx.serialization (Android).
**Why:** 10 packet types don't justify a schema compiler. Both platforms have excellent JSON support. Shared test vectors in `protocol/` ensure interop.

### 5. Monorepo with shared protocol test vectors
**Choice:** `protocol/test-vectors/` contains JSON files with expected packet encodings that both platforms' test suites load.
**Why:** Cross-platform correctness without a shared language. Each side parses the test vectors independently.

## Risks / Trade-offs

| Risk | Mitigation |
|------|-----------|
| L2CAP throughput below 30 KB/s | Measure first. If too slow, clipboard might need compression or GATT fallback |
| Android L2CAP API (createL2capChannel) buggy on One UI | Test on actual S25 hardware in spike, not emulator |
| Pending connect doesn't work reliably | Test 10x walk-away-and-back. If unreliable, fall back to manual reconnect timer |
| Self-signed certificate rejected by macOS code signing | Verify in Keychain Access before building the full app |
