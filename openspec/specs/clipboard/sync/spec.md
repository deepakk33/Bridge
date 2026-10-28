# clipboard/sync Specification

## Purpose
Bidirectional clipboard sync between Mac and Android over the secure BLE link. Mac-to-phone requires no special permission. Phone-to-Mac is manual and always will be on Android 16: the permission that would allow a background read is role-managed and cannot be held by a sideloaded build, so the Quick Settings tile and the in-app send control are the only triggers. On reconnect, both sides exchange `bridge.clipboard.connect` packets with timestamps to prevent ping-pong overwrites.

## Requirements

### Requirement: Mac clipboard change sends bridge.clipboard to Android
When the Mac clipboard content changes, the Mac sends a `bridge.clipboard` packet containing the text to Android.

#### Scenario: User copies text on Mac
- **WHEN** the user copies text on the Mac and the clipboard changeCount increments
- **THEN** the Mac sends a `bridge.clipboard` packet with the copied text to Android within 500ms

#### Scenario: Non-text clipboard content ignored
- **WHEN** the user copies an image or file on the Mac (no public.utf8-plain-text representation)
- **THEN** no `bridge.clipboard` packet is sent

#### Scenario: Clipboard unchanged
- **WHEN** the polling cycle fires but the NSPasteboard changeCount has not incremented
- **THEN** no packet is sent

---

### Requirement: Android receives bridge.clipboard and writes to local clipboard
When Android receives a `bridge.clipboard` packet from the Mac, it writes the text to the local ClipboardManager.

#### Scenario: Mac copies text, phone can paste
- **WHEN** Android receives a `bridge.clipboard` packet with text content
- **THEN** ClipboardManager.setPrimaryClip is called with the text, and the user can paste it in any app

#### Scenario: Duplicate content suppressed
- **WHEN** Android receives a `bridge.clipboard` packet whose text matches the current local clipboard
- **THEN** the local clipboard is not rewritten (avoids triggering a local change event that would echo back)

---

### Requirement: Mac receives bridge.clipboard and writes to local clipboard
When the Mac receives a `bridge.clipboard` packet from Android, it writes the text to NSPasteboard.

#### Scenario: Phone copies text, Mac can paste
- **WHEN** the Mac receives a `bridge.clipboard` packet with text content
- **THEN** the text is written to NSPasteboard generalPasteboard and the user can paste it in any Mac app

#### Scenario: Duplicate content suppressed on Mac
- **WHEN** the Mac receives a `bridge.clipboard` packet whose text matches the current NSPasteboard content
- **THEN** the local pasteboard is not rewritten (avoids triggering a change-count increment that would echo back)

---

### Requirement: Reconnect exchanges bridge.clipboard.connect with timestamp
On every reconnect, both sides send a `bridge.clipboard.connect` packet carrying the current clipboard text and the epoch-millisecond timestamp of when that clipboard was last set.

#### Scenario: Mac reconnects and has newer clipboard
- **WHEN** the Mac reconnects and sends `bridge.clipboard.connect` with timestamp T1, and Android's `bridge.clipboard.connect` has timestamp T2 where T2 < T1
- **THEN** Android overwrites its clipboard with the Mac's content

#### Scenario: Android reconnects and has newer clipboard
- **WHEN** Android reconnects and sends `bridge.clipboard.connect` with timestamp T1, and Mac's `bridge.clipboard.connect` has timestamp T2 where T2 < T1
- **THEN** the Mac overwrites its pasteboard with Android's content

#### Scenario: Equal timestamps on reconnect
- **WHEN** both sides send `bridge.clipboard.connect` with the same timestamp (or timestamps within 100ms)
- **THEN** neither side overwrites its clipboard (tie goes to no-op)

#### Scenario: Reconnect does not cause ping-pong
- **WHEN** Mac and Android reconnect and exchange `bridge.clipboard.connect`
- **THEN** at most one side writes to its clipboard; writing does not trigger a second `bridge.clipboard` packet because the duplicate-content suppression prevents echo

#### Scenario: Stale clipboard with zero timestamp
- **WHEN** one side sends `bridge.clipboard.connect` with timestamp 0 (unknown when clipboard was set)
- **THEN** the receiver ignores that content regardless of its own timestamp

---

### Requirement: Latency under one second
Clipboard sync meets the PRD success criteria of under one second end-to-end.

#### Scenario: Mac to Android latency
- **WHEN** the user copies text on the Mac
- **THEN** the text is pasteable on Android within 1 second

#### Scenario: Android to Mac latency
- **WHEN** the user copies text on Android and taps the Quick Settings tile or the in-app
  send control
- **THEN** the text is pasteable on the Mac within 1 second of that tap

### Requirement: Deferred Mac-to-phone clip is flushed on focus
When a `bridge.clipboard` packet arrives from the Mac while the app is backgrounded, the
write is denied by the OS. The content is cached and re-applied from the Activity context
the next time the app has focus.

#### Scenario: Mac copies while phone app is backgrounded
- **WHEN** the Mac sends a clip while the Bridge app is not focused
- **THEN** the content is cached as pending and the local write is deferred

#### Scenario: Pending clip applied on next focus
- **WHEN** the Bridge app regains window focus and a pending Mac clip exists
- **THEN** the pending clip is written to the local clipboard and cleared

#### Scenario: No pending clip
- **WHEN** the app regains focus and no pending Mac clip exists
- **THEN** nothing is written

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

### Requirement: Background phone-to-Mac clipboard is unobtainable on Android 16
On Android 16 (One UI 8.5) only the focused window or the active IME may read the clipboard.
The permission that lifts that rule, `READ_CLIPBOARD_IN_BACKGROUND`, is role-managed: `pm grant`
refuses it with `SecurityException: Permission ... is managed by role`, and no `appops` mode
substitutes for it. A sideloaded Bridge build therefore SHALL NOT claim, offer, or depend on
background clipboard reads. Phone-to-Mac sync is manual, permanently and by platform design, and
the app SHALL NOT schedule a recurring clipboard read, since such a read can only return nothing
while still costing battery. Mac-to-phone sync is unaffected: writing to the phone's own
clipboard from a received packet needs no permission.

#### Scenario: No recurring read is ever scheduled
- **WHEN** the Bridge foreground service starts on Android 16
- **THEN** no recurring clipboard read is scheduled for the lifetime of the service, and the
  reason is logged once

#### Scenario: Mac-to-phone is unaffected
- **WHEN** the Mac clipboard changes while the Bridge app is backgrounded
- **THEN** the phone's clipboard is updated from the received packet as normal

#### Scenario: Backgrounded phone sends nothing
- **WHEN** the user copies text on the phone while Bridge is in the background
- **THEN** the OS denies the read, and no `bridge.clipboard` packet is sent

#### Scenario: A new phone needs no setup step
- **WHEN** the user installs Bridge on a new phone
- **THEN** both phone-to-Mac triggers work immediately, with no grant, script, adb command or
  setup step anywhere in the product

#### Scenario: Nothing instructs the user to obtain the grant
- **WHEN** the user looks anywhere Bridge can speak to them — the permission list, the clipboard
  card, the repository scripts, the README or the specs
- **THEN** no text names an adb command, a grant or a script for background clipboard, because
  none can succeed

---

### Requirement: Phone-to-Mac clipboard sends are user-initiated
A phone-to-Mac send SHALL occur only when the user asks for it: the in-app send control or the
Quick Settings tile. The app SHALL NOT send the clipboard as a side effect of being opened or
brought to the foreground, and SHALL NOT send a clip that Bridge itself wrote from a Mac packet.

The one exception is the `bridge.clipboard.connect` packet required by "Reconnect exchanges
bridge.clipboard.connect with timestamp", which carries the phone's current clip on every
connection and is therefore not user-initiated. Whether that exchange should be gated the same
way is an open question, tracked as BR-16; this requirement does not resolve it.

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
- **THEN** the phone sends its current clip in a `bridge.clipboard.connect` packet as the
  reconnect requirement specifies, capped per the 10 KB rule — this change does not alter
  that behaviour

#### Scenario: Incoming Mac clip still lands on focus
- **WHEN** the app regains focus and a Mac clip is pending from while it was backgrounded
- **THEN** the pending clip is still written to the phone clipboard — removing the outbound
  send does not disable the inbound flush

---

### Requirement: Clipboard card reflects the current sync state
The Android app clearly communicates the clipboard sync state. The clipboard card SHALL
reflect the current state rather than a fixed message, and SHALL use its warning colour only
for a condition the user can act on — a down link, or a clip that had to be truncated. Manual
sync is the permanent, correct state on Android 16, so it SHALL NOT be shown as a warning: a
colour that never clears trains the user to ignore the honest ones. All of the card's text
SHALL come from string resources, and it SHALL NOT instruct the user to run a command the OS
refuses.

#### Scenario: No background watcher, UI explains manual sync
- **WHEN** the Bridge app starts and no background clipboard watcher is running
- **THEN** the clipboard card states that sync is manual, names the two phone-to-Mac triggers,
  gives the OS reason it is manual, and renders in the normal text colour with no warning

#### Scenario: Link down is distinguished from manual sync
- **WHEN** the link to the Mac is down
- **THEN** the card says the link is down rather than describing the sync mode, in the warning colour

#### Scenario: Truncated clip is reported on the card
- **WHEN** the most recent phone-to-Mac send was truncated at the 10 KB cap
- **THEN** the card states that the clip was truncated and gives the original size, in the
  warning colour, until a later send succeeds whole

#### Scenario: A new phone needs no setup to reach the manual state
- **WHEN** the user sets up Bridge on a new phone
- **THEN** the app shows the manual-sync card, and the two phone-to-Mac triggers work with no
  grant, script or setup step
