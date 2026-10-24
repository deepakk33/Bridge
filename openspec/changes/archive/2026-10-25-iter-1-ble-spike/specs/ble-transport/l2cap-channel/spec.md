## Purpose
Bidirectional L2CAP channel between Mac and Android for streaming JSON packets over BLE.

## ADDED Requirements

### Requirement: Android publishes L2CAP PSM
The Android peripheral opens an L2CAP server socket and publishes its PSM (Protocol/Service Multiplexer) via a GATT characteristic so the Mac central can read it.

#### Scenario: Foreground service starts
- **WHEN** the foreground service starts
- **THEN** an L2CAP server socket is opened and its PSM is written to the Bridge GATT characteristic

#### Scenario: Mac reads PSM and connects
- **WHEN** the Mac reads the PSM characteristic
- **THEN** it opens an L2CAP channel to that PSM within 1 second

---

### Requirement: Bidirectional JSON exchange
Both endpoints send and receive single-line JSON messages terminated by newline (`\n`) over the L2CAP channel.

#### Scenario: Mac sends JSON line
- **WHEN** the Mac writes `{"id":1234,"type":"bridge.ping","body":{}}\n` to the channel
- **THEN** the Android side receives and parses the complete JSON object

#### Scenario: Android sends JSON line
- **WHEN** Android writes `{"id":5678,"type":"bridge.ping","body":{}}\n` to the channel
- **THEN** the Mac side receives and parses the complete JSON object

#### Scenario: Rapid exchange
- **WHEN** 100 ping packets are sent in quick succession from each side
- **THEN** all 200 packets are received and parsed correctly (no corruption, no loss)

---

### Requirement: Throughput measurement
The spike must measure actual L2CAP throughput to validate that clipboard sync (~1KB) will feel instant.

#### Scenario: Throughput test
- **WHEN** one side sends a 50KB payload as consecutive JSON lines
- **THEN** throughput is logged in KB/s and must exceed 30 KB/s for the spike to pass

---

### Requirement: Reconnect via pending connect
The Mac issues a CBCentralManager connect that has no timeout. When the phone walks out of range and returns, the system reconnects without application code.

#### Scenario: Phone leaves range
- **WHEN** the phone moves out of BLE range
- **THEN** the Mac detects disconnection within 30 seconds

#### Scenario: Phone returns to range
- **WHEN** the phone returns to BLE range after being away
- **THEN** the connection re-establishes automatically without any user action or app-side reconnect timer
