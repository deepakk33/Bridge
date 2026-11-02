## MODIFIED Requirements

### Requirement: Reconnect exchanges bridge.clipboard.connect with timestamp
On every reconnect, both sides send a `bridge.clipboard.connect` packet carrying the
epoch-millisecond timestamp of when that side's clipboard was last set.

The Mac SHALL include its current pasteboard text in that packet. The phone SHALL NOT: its packet
carries the timestamp alone, with no `content` field, because a reconnect is not a user asking for
a send (see "Phone-to-Mac clipboard sends are user-initiated"). A receiver that finds no `content`
SHALL ignore the packet rather than treat it as an empty clipboard.

Platform constraint: this makes the reconnect exchange one-directional in effect. Mac→phone
convergence after a gap is unchanged; phone→Mac convergence after a gap requires the user to tap
the tile or the in-app send control.

#### Scenario: Mac reconnects and has newer clipboard
- **WHEN** the Mac reconnects and sends `bridge.clipboard.connect` with content and timestamp T1,
  and Android's clipboard was last set at T2 where T2 < T1
- **THEN** Android overwrites its clipboard with the Mac's content

#### Scenario: Android reconnects and has newer clipboard
- **WHEN** Android reconnects and its clipboard is newer than the Mac's
- **THEN** Android's `bridge.clipboard.connect` carries its timestamp and no `content`, the Mac's
  pasteboard is left alone, and the clip reaches the Mac only when the user taps the tile or the
  in-app send control

#### Scenario: A connect packet without content is ignored, not treated as empty
- **WHEN** a `bridge.clipboard.connect` packet arrives with a newer timestamp and no `content`
  field
- **THEN** the receiver leaves its clipboard unchanged, and does not clear it

#### Scenario: Equal timestamps on reconnect
- **WHEN** both sides send `bridge.clipboard.connect` with the same timestamp (or timestamps
  within 100ms)
- **THEN** neither side overwrites its clipboard (tie goes to no-op)

#### Scenario: Reconnect does not cause ping-pong
- **WHEN** Mac and Android reconnect and exchange `bridge.clipboard.connect`
- **THEN** at most one side writes to its clipboard; writing does not trigger a second
  `bridge.clipboard` packet because the duplicate-content suppression prevents echo

#### Scenario: Stale clipboard with zero timestamp
- **WHEN** one side sends `bridge.clipboard.connect` with timestamp 0 (unknown when clipboard was
  set)
- **THEN** the receiver ignores that content regardless of its own timestamp

---

### Requirement: Phone-to-Mac clipboard sends are user-initiated
A phone-to-Mac send SHALL occur only when the user asks for it: the in-app send control or the
Quick Settings tile. The app SHALL NOT send the clipboard as a side effect of being opened or
brought to the foreground, SHALL NOT send it as a side effect of the link being re-established,
and SHALL NOT send a clip that Bridge itself wrote from a Mac packet.

There is no exception. The `bridge.clipboard.connect` packet the phone sends on reconnect carries
a timestamp and no clipboard text, so re-establishing the link moves no clip phone-ward.

#### Scenario: Opening the app sends nothing
- **WHEN** the user opens or returns to the Bridge app while connected, having copied
  something on the phone
- **THEN** no `bridge.clipboard` packet is sent, and the Mac clipboard is unchanged

#### Scenario: Explicit send works
- **WHEN** the user taps the in-app send control or the Quick Settings tile
- **THEN** the current clipboard text is sent to the Mac and the Mac can paste it

#### Scenario: Change originated from Mac is not echoed
- **WHEN** the clipboard change was caused by receiving a `bridge.clipboard` packet from the Mac
  — Bridge itself wrote the clip — and a send is then triggered
- **THEN** no `bridge.clipboard` packet carrying that same clip is sent back to the Mac

#### Scenario: Reconnect still exchanges clips
- **WHEN** the link is re-established
- **THEN** the phone's `bridge.clipboard.connect` packet carries its timestamp and no `content`
  field, and the Mac pasteboard is unchanged by the reconnect

#### Scenario: Incoming Mac clip still lands on focus
- **WHEN** the app regains focus and a Mac clip is pending from while it was backgrounded
- **THEN** that clip is written to the phone clipboard, unchanged by this requirement
