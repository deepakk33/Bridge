# file-transfer/wifi-lan Specification

## Purpose
Tier 3 file transfer over Wi-Fi LAN for large files (>=500 KB) when both devices share a network. Service discovery via mDNS/Bonjour (`_bridge._tcp`). Full LAN throughput (100+ MB/s on Wi-Fi 6E). Channel opened per transfer and torn down after.

## Requirements

### Requirement: mDNS service advertisement
The sender advertises a TCP transfer service via mDNS so the receiver can discover it on the local network.

#### Scenario: Android advertises transfer service
- **WHEN** Android initiates a large file transfer and both devices share a network
- **THEN** Android registers an mDNS service `_bridge._tcp` on the LAN with the transfer port in the TXT record

#### Scenario: Mac advertises transfer service
- **WHEN** the Mac initiates a large file transfer and both devices share a network
- **THEN** the Mac registers an mDNS service `_bridge._tcp` via Network.framework with the transfer port

#### Scenario: Service deregistered after transfer
- **WHEN** the file transfer completes or fails
- **THEN** the mDNS service is deregistered within 2 seconds

---

### Requirement: Receiver discovers and connects via TCP
The receiver resolves the mDNS service and opens a TCP connection to the sender's advertised port.

#### Scenario: Mac discovers Android transfer service
- **WHEN** the Mac receives a `bridge.share.request` with `payloadTransferInfo` containing a port
- **THEN** the Mac opens a TCP connection to the Android device on that port within 2 seconds

#### Scenario: Android discovers Mac transfer service
- **WHEN** Android receives a `bridge.share.request` with `payloadTransferInfo` containing a port
- **THEN** Android opens a TCP connection to the Mac on that port within 2 seconds

#### Scenario: Discovery failure
- **WHEN** the TCP connection to the advertised port fails (firewall, network mismatch)
- **THEN** the transfer falls back to Tier 2 (RFCOMM) and notifies the user of the fallback

---

### Requirement: Large file transfer at LAN speed
Files of 500 KB and above transfer over Wi-Fi LAN with throughput matching the local network capability.

#### Scenario: Transfer a 10 MB file
- **WHEN** both devices are on the same Wi-Fi network and a 10 MB file is sent
- **THEN** the transfer completes in under 2 seconds (>5 MB/s minimum)

#### Scenario: Transfer a 100 MB file
- **WHEN** both devices are on the same Wi-Fi 6E network and a 100 MB video is sent
- **THEN** the transfer completes in under 10 seconds

#### Scenario: Transfer integrity
- **WHEN** any file is transferred over Wi-Fi LAN
- **THEN** the received file has an identical SHA-256 checksum to the original

---

### Requirement: Tier 1 BLE link stays free during Tier 3 transfer
Wi-Fi LAN transfer must not interfere with the BLE control channel.

#### Scenario: Clipboard sync during large file transfer
- **WHEN** the user copies text on the Mac while a 50 MB file transfer is in progress over Wi-Fi LAN
- **THEN** the clipboard syncs to the phone within 1 second over the BLE link

---

### Requirement: Mac Info.plist prerequisites
The Mac app must carry the necessary plist keys for local network access and Bonjour.

#### Scenario: Local network permission prompt
- **WHEN** the Mac app first attempts a Wi-Fi LAN transfer
- **THEN** the system prompts for Local Network access (NSLocalNetworkUsageDescription present in Info.plist)

#### Scenario: Bonjour service declared
- **WHEN** the Mac app is built
- **THEN** `NSBonjourServices` in Info.plist includes `_bridge._tcp`

---

### Requirement: File size routing to Tier 3
Files of 500 KB and above are routed to Wi-Fi LAN when a shared network is available.

#### Scenario: Large file sent via Tier 3
- **WHEN** the user sends a 5 MB file and both devices share a network
- **THEN** the transfer uses Tier 3 (Wi-Fi LAN), not Tier 2 (RFCOMM)

#### Scenario: No shared network falls back to Tier 2
- **WHEN** the user sends a 5 MB file but the devices do not share a network
- **THEN** the transfer falls back to Tier 2 (RFCOMM) with a warning about slower speed
