# clipboard/sync Specification

## Purpose
Bidirectional clipboard sync between Mac and Android over the secure BLE link. Mac-to-phone requires no special permission. Phone-to-Mac requires an ADB grant (see `clipboard/adb-grant`). On reconnect, both sides exchange `bridge.clipboard.connect` packets with timestamps to prevent ping-pong overwrites.

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

### Requirement: Android clipboard change sends bridge.clipboard to Mac (with ADB grant)
When the Android clipboard changes and the ADB grant is active, Android sends a `bridge.clipboard` packet to the Mac.

#### Scenario: User copies text on phone with ADB grant active
- **WHEN** the user copies text on the phone and ClipboardManager fires onPrimaryClipChanged
- **THEN** Android sends a `bridge.clipboard` packet with the copied text to the Mac within 500ms

#### Scenario: Change originated from Mac ignored
- **WHEN** the clipboard change was caused by receiving a `bridge.clipboard` packet from the Mac (Bridge itself wrote the clip)
- **THEN** no `bridge.clipboard` packet is sent back to the Mac (prevents echo loop)

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

#### Scenario: Android to Mac latency (with ADB grant)
- **WHEN** the user copies text on Android (ADB grant active)
- **THEN** the text is pasteable on the Mac within 1 second

### Requirement: Android 16 blocks automatic background clipboard access
On Android 16 (One UI 8.5) the clipboard service denies reads and writes to the Bridge
service while it is not the focused window. `READ_CLIPBOARD_IN_BACKGROUND` is role-managed
and cannot be granted to a sideloaded app, so automatic background sync is unavailable
without root. Sync is therefore manual.

#### Scenario: Background clipboard read denied
- **WHEN** the backgrounded Bridge service polls the clipboard on Android 16
- **THEN** the read is denied by the OS and no `bridge.clipboard` packet is sent

#### Scenario: Quick Settings tile pushes phone to Mac
- **WHEN** the user taps the Bridge Quick Settings tile
- **THEN** the tile (a focused context) reads the clipboard and sends `bridge.clipboard` to the Mac

#### Scenario: Opening the app sends the current phone clip
- **WHEN** the Bridge app gains window focus
- **THEN** the current clipboard text is read from the Activity context and sent to the Mac

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
