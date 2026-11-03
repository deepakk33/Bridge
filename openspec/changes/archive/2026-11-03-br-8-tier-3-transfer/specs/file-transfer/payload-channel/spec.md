## ADDED Requirements

### Requirement: The payload port serves only the peer that asked for the transfer

The payload listener SHALL serve bytes only to the peer whose pinned certificate matches the
device that requested the transfer over the control channel. A connection from any other host
SHALL receive no payload bytes, whether or not it arrives first.

Serving "the first connection that arrives" is not sufficient on a shared network: the listener is
reachable by every host on it for the duration of the window.

#### Scenario: An unrelated LAN host connects to the payload port

- **WHEN** a host that is not the requesting peer opens a TCP connection to the payload port while
  the transfer window is open
- **THEN** it receives no payload bytes, and the transfer to the real peer still completes

#### Scenario: The requesting peer connects

- **WHEN** the peer that sent the share request connects and presents its pinned certificate
- **THEN** the payload is served to it

---

### Requirement: payloadSize is bounded before any resource is committed

A declared `payloadSize` above the accepted maximum SHALL be refused **before** a port is opened,
a file is created, or a byte is read. A refusal SHALL be reported to the user, not only logged.

#### Scenario: A payload declaring an absurd size

- **WHEN** a `bridge.share.request` arrives declaring a payload far above the cap
- **THEN** it is refused before any bytes are written and before a connection is made, and the
  user is told

#### Scenario: A payload within the cap

- **WHEN** a share request declares a size within the cap
- **THEN** the transfer proceeds as normal

---

### Requirement: A failed receive is surfaced to the user

When a receive fails — handshake, timeout, short read, refused size or declined file — the
receiving side SHALL tell the user that the named file did not arrive. A log line alone is not
sufficient on either platform.

#### Scenario: The payload handshake fails

- **WHEN** the payload connection fails its TLS handshake
- **THEN** the user is told that the named file failed, rather than the failure appearing only in
  the log

#### Scenario: The transfer is cut short

- **WHEN** fewer bytes arrive than declared
- **THEN** no partial file is left in place and the user is told the transfer failed
