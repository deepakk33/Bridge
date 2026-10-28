## ADDED Requirements

### Requirement: Phone-to-Mac clipboard text is capped at 10 KB
Every phone-to-Mac clipboard send SHALL carry at most 10 240 bytes of UTF-8 text in the
`bridge.clipboard` (or `bridge.clipboard.connect`) packet, so that a large clip cannot
occupy the BLE control channel long enough to delay pings, notifications or file metadata.
Text longer than the cap SHALL be truncated on a character boundary — never splitting a
UTF-8 sequence or a surrogate pair — and the truncation SHALL be reported to the user. The
cap applies to every phone-to-Mac path: the background poll, the in-app send button, the
Quick Settings tile, and the clip offered on reconnect.

#### Scenario: Clip within the cap is sent whole
- **WHEN** the user triggers a phone-to-Mac send and the clipboard text is 10 240 bytes of
  UTF-8 or fewer
- **THEN** the full text is sent unchanged and nothing reports a truncation

#### Scenario: Oversized clip is truncated and the user is told
- **WHEN** the user triggers a phone-to-Mac send and the clipboard text exceeds 10 240 bytes
  of UTF-8
- **THEN** the packet carries the leading portion of the text that fits within 10 240 bytes,
  the Mac can paste that portion, and the Android clipboard card states that the clip was
  truncated and gives the original size

#### Scenario: Truncation never splits a character
- **WHEN** the 10 240-byte boundary falls inside a multi-byte character or a surrogate pair
- **THEN** the send is cut at the preceding character boundary, and the text the Mac receives
  decodes without a replacement character

#### Scenario: Oversized clip does not stall the control channel
- **WHEN** an oversized clip is sent and a ping or notification packet follows it
- **THEN** that following packet is delivered within the link's normal latency, because no
  single clipboard line exceeds the cap

---

### Requirement: Phone-to-Mac clipboard sends are user-initiated or grant-backed
The Android app SHALL NOT send the clipboard to the Mac as a side effect of being opened or
brought to the foreground. A phone-to-Mac send SHALL occur only when the user asks for it —
the in-app send control or the Quick Settings tile — or when the ADB grant is active and the
background clipboard watcher observes a change the user made.

#### Scenario: Opening the app sends nothing
- **WHEN** the user opens or returns to the Bridge app while connected, having copied
  something on the phone
- **THEN** no `bridge.clipboard` packet is sent, and the Mac clipboard is unchanged

#### Scenario: Explicit send works
- **WHEN** the user taps the in-app send control or the Quick Settings tile
- **THEN** the current clipboard text is sent to the Mac and the Mac can paste it

#### Scenario: Incoming Mac clip still lands on focus
- **WHEN** the app regains focus and a Mac clip is pending from while it was backgrounded
- **THEN** the pending clip is still written to the phone clipboard — removing the outbound
  send does not disable the inbound flush

### Requirement: Android 16 background clipboard access is gated on the ADB grant
On Android 16 (One UI 8.5) the clipboard service denies reads and writes to the Bridge
service while it is not the focused window, unless the `READ_CLIPBOARD_IN_BACKGROUND` ADB
grant is active. Without that grant automatic background sync is unavailable, so phone-to-Mac
sync is manual. The app SHALL NOT run a background clipboard watcher when the grant is
absent, since such a watcher can only read nothing while still costing battery.

#### Scenario: No watcher without the grant
- **WHEN** the Bridge foreground service starts and the `READ_CLIPBOARD_IN_BACKGROUND` grant
  is absent
- **THEN** no recurring clipboard read is scheduled, and the reason is logged once

#### Scenario: Watcher runs with the grant
- **WHEN** the Bridge foreground service starts and the grant is active
- **THEN** the background clipboard watcher runs and a clip copied with the app backgrounded
  reaches the Mac

#### Scenario: Background clipboard read denied
- **WHEN** the backgrounded Bridge service reads the clipboard on Android 16 without the grant
- **THEN** the read is denied by the OS and no `bridge.clipboard` packet is sent

#### Scenario: Quick Settings tile pushes phone to Mac
- **WHEN** the user taps the Bridge Quick Settings tile
- **THEN** the tile (a focused context) reads the clipboard and sends `bridge.clipboard` to the Mac

## REMOVED Requirements

### Requirement: Android 16 blocks automatic background clipboard access
**Reason**: Two of its statements are no longer true. It described the background poll as
unconditional when the OS denies it outright without the ADB grant, and its "Opening the app
sends the current phone clip" scenario made window focus a send trigger — focus is not
consent, and that path shipped whatever was on the clipboard to the Mac over a channel that
is plaintext until BR-4, with nothing on screen asking first. Replaced by "Android 16
background clipboard access is gated on the ADB grant" above, which keeps the two scenarios
that are still true, plus "Phone-to-Mac clipboard sends are user-initiated or grant-backed".
**Migration**: For phone-to-Mac without the grant, use the in-app "Send Clipboard to Mac"
control or the Quick Settings tile — both already exist and are unchanged. With the ADB grant
active, a copy made on the phone still reaches the Mac with no tap.
