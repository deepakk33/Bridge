# file-transfer/bluetooth Specification

## Purpose
Tier 2 file transfer over Bluetooth Classic RFCOMM for small files under 500 KB. No network required — works anywhere the two devices are in proximity. Channel opened per transfer and torn down after, keeping Tier 1 BLE free for notifications and clipboard.

## Requirements

### Requirement: Android opens RFCOMM server socket
The Android side listens for incoming Bluetooth Classic connections on a known Bridge UUID so the Mac can connect for file transfer.

#### Scenario: Service ready for transfer
- **WHEN** the Bridge foreground service is running and a file transfer is requested
- **THEN** Android opens a BluetoothServerSocket via `listenUsingRfcommWithServiceRecord` with the Bridge file transfer UUID

#### Scenario: Socket cleanup after transfer
- **WHEN** a file transfer completes or fails
- **THEN** the RFCOMM server socket is closed within 2 seconds

---

### Requirement: Mac connects via RFCOMM
The Mac opens a Bluetooth Classic RFCOMM connection to the Android device for outbound file transfers.

#### Scenario: Mac initiates transfer to phone
- **WHEN** the user drops a file onto the menu bar icon or triggers a send
- **THEN** the Mac opens an RFCOMM channel to the Android device using `IOBluetoothDevice.openRFCOMMChannelAsync`

#### Scenario: Connection failure
- **WHEN** the RFCOMM connection fails (device out of range, Bluetooth off)
- **THEN** the Mac displays an error in the menu bar dropdown within 5 seconds and does not retry silently

---

### Requirement: Bidirectional small file transfer
Files under 500 KB transfer over RFCOMM in both directions (Mac to phone, phone to Mac).

#### Scenario: Mac sends file to phone
- **WHEN** the Mac sends a 200 KB file over RFCOMM
- **THEN** the Android side receives the complete file with matching checksum within 5 seconds

#### Scenario: Phone sends file to Mac
- **WHEN** the Android side sends a 100 KB screenshot over RFCOMM
- **THEN** the Mac receives the complete file with matching checksum within 3 seconds

#### Scenario: Transfer progress
- **WHEN** a file transfer is in progress over RFCOMM
- **THEN** both sides can report bytes transferred so far for progress display

---

### Requirement: Tier 1 link stays free during Tier 2 transfer
RFCOMM transfer must not block or degrade the BLE control channel.

#### Scenario: Notification during file transfer
- **WHEN** a notification arrives on the phone while an RFCOMM file transfer is in progress
- **THEN** the notification is mirrored to the Mac within 2 seconds (same latency as without a transfer running)

---

### Requirement: File size routing to Tier 2
Files under 500 KB are routed to Bluetooth Classic RFCOMM by default.

#### Scenario: Small file sent via Tier 2
- **WHEN** the user sends a 300 KB file and Tier 3 (Wi-Fi LAN) is available
- **THEN** the transfer uses Tier 2 (RFCOMM), not Tier 3

#### Scenario: Tier 3 unavailable fallback
- **WHEN** the user sends a 2 MB file but Tier 3 is unavailable (no shared network)
- **THEN** the transfer falls back to Tier 2 (RFCOMM) with a warning that it will be slow (~14 seconds at 140 KB/s)
