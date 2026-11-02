## ADDED Requirements

### Requirement: Packet handlers survive a peripheral rebuild

Platform constraint: Android only. The phone is the BLE peripheral, and it rebuilds that
peripheral whenever the Bluetooth adapter is cycled or the service restarts its link.

The set of packet handlers the Android service registers SHALL be a property of the running
service, not of any one peripheral or channel instance. Rebuilding the BLE peripheral, the
advertiser, or the L2CAP server SHALL NOT change which packet types the phone acts on, and
SHALL NOT require the service to register anything a second time.

This covers every packet type the phone accepts from the Mac over the control channel:
`bridge.notification.reply`, `bridge.notification.request`, `bridge.clipboard`,
`bridge.clipboard.connect`, `bridge.share.request`, `bridge.pair`, and the link-level
pair-confirmation and connect events.

#### Scenario: Bluetooth is turned off and on again

- **WHEN** the Bluetooth adapter is cycled and the service rebuilds its BLE peripheral
- **THEN** a `bridge.share.request` received after the rebuild starts a file receive, exactly as
  one received before the rebuild would have

#### Scenario: Every handler, not just the one the restart path remembered

- **WHEN** the peripheral has been rebuilt
- **THEN** `bridge.notification.reply`, `bridge.notification.request`, `bridge.clipboard`,
  `bridge.clipboard.connect` and `bridge.share.request` each reach the same handler they reached
  before the rebuild

#### Scenario: Pairing still prompts after a rebuild

- **WHEN** an unpaired Mac connects after the peripheral has been rebuilt
- **THEN** the six-digit confirmation is raised and the pairing notification is posted, as on a
  first connection

---

### Requirement: A received packet that reaches no handler is logged

Platform constraint: Android only.

When the phone receives a packet of a type it declares in its identity capabilities, and the
trust gate admits it, but no handler is registered for it, the phone SHALL log a warning naming
the packet type. It SHALL NOT discard the packet without a trace.

An unknown packet type — one the phone does not declare — SHALL continue to be logged as such.

#### Scenario: A known type with no handler registered

- **WHEN** a `bridge.share.request` passes the trust gate and no share-request handler is
  registered
- **THEN** a warning naming `bridge.share.request` is logged, so the drop is visible in logcat

#### Scenario: An unknown packet type

- **WHEN** a packet arrives whose type the phone does not declare in its capabilities
- **THEN** a warning naming that type is logged, unchanged from today's behaviour
