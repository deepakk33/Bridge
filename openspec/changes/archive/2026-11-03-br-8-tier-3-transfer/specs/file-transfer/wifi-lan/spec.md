## ADDED Requirements

### Requirement: Tier 3 delivers a byte-identical file in both directions

A file sent over Tier 3 SHALL arrive with contents identical to the source, verified by checksum,
Mac→phone and phone→Mac, on real hardware. The mutual-TLS handshake SHALL complete against the
connected peer's pinned certificate before any payload byte is transferred.

Platform note: both devices on the same LAN. macOS gates local network access behind a user
permission; the Mac SHALL NOT report a Tier 3 transfer as possible when that access is absent, and
SHALL say what is wrong rather than failing as a timeout.

#### Scenario: Mac to phone

- **WHEN** a file above the Tier 3 threshold is sent from the Mac and accepted on the phone
- **THEN** the file arrives on the phone and its checksum equals the source's

#### Scenario: Phone to Mac

- **WHEN** a file above the Tier 3 threshold is sent from the phone and accepted on the Mac
- **THEN** the file arrives on the Mac and its checksum equals the source's

#### Scenario: Two devices pinned

- **WHEN** the trust store holds more than one pinned device and a transfer runs
- **THEN** the handshake verifies against the connected peer's pin, not against an arbitrary
  entry in the store

#### Scenario: Local network access is unavailable on the Mac

- **WHEN** the Mac cannot use the local network
- **THEN** the failure is reported as that, not as a transfer timeout

---

### Requirement: An incoming file is announced before it is written

The receiving side SHALL announce an incoming file — its name and size — and offer a way to
decline, before the file is written to disk. Declining SHALL leave nothing behind.

#### Scenario: A file is offered

- **WHEN** a share request arrives
- **THEN** the user sees the name and size and can decline before anything is written

#### Scenario: The user declines

- **WHEN** the user declines an offered file
- **THEN** no file is written and no payload is fetched. The sender is not told: its payload
  window closes on its own timeout rather than at once, which is accepted deliberately so that
  declining needs no new packet type

---

### Requirement: A received filename cannot escape the download directory

The receiving side SHALL reduce an incoming filename to a basename and SHALL reject or flatten any
path separator or parent reference it contains. A received file SHALL always land inside the
download directory.

This applies to the name as the peer sends it, on both platforms. The name is peer-controlled
input and is not trusted.

#### Scenario: A filename containing a parent reference

- **WHEN** a peer sends a file named `../../.zshenv`
- **THEN** it lands inside the download directory under a flattened name, or is refused — and
  nothing outside that directory is created or overwritten

#### Scenario: An ordinary filename

- **WHEN** a peer sends `report.pdf`
- **THEN** it lands in the download directory as `report.pdf`, de-duplicated if a file of that
  name already exists
