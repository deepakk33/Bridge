## ADDED Requirements

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
