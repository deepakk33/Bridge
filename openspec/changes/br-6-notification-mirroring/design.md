## Context

See `proposal.md` — Why. The design-relevant state of the code:

- `BridgeNotificationListener.onNotificationRemoved` only prunes the reply cache. It never sends anything.
- `BridgePacket.notificationRequest(cancel:)` exists on Android with zero callers; the Mac already *sends* the
  same type from `NotificationManager` when the user dismisses on the Mac.
- `BLEManager.handlePacket` routes `bridge.notification.request` to a log line that says it is "not applicable
  on Mac side". That line is the whole of F1's Mac half.
- `getCachedAction` is `replyCache.remove(...)`. `onNotificationRemoved` already evicts by notification key, so
  eviction exists in the right place and the `remove` in the getter is pure duplication with a bug attached.
- `replyCache` is a `LinkedHashMap` in access order with `removeEldestEntry` at 200 — an LRU. Reading with
  `get` keeps access-order recency working as intended; `remove` defeats it.
- `NotificationFilterStore` writes optimistically to a `StateFlow` then persists fire-and-forget.
  `NotificationFilterActivity` already has an `emptyHint` TextView shown when the seen list is empty.
- `FilterState` is a pure, Android-free data class with unit tests on the JVM — the natural home for a bulk
  allow operation.

## Goals / Non-Goals

**Goals:**

- Fix the three defects that are each a line or two (`remove` → `get`, the missing send, the missing Mac
  handler, `<queries>`) without restructuring anything around them.
- Make the deny-by-default choice visible and reversible in one tap, without weakening it.
- Keep the notification hot path synchronous and allocation-light; it runs on every notification on the phone.

**Non-Goals:**

- Not changing the gate rule. `shouldForward` stays `!masterMute && pkg in allowed`.
- Not adding an ack for `bridge.notification.reply`. AUD-B4 wants one; it belongs with BR-3's send-failure
  work, and adding it here would mean a second round trip for every reply.
- Not reworking filter row heights or wrapping, which AUD-F notes in passing.
- Not touching clipboard, which shares the listener's process but nothing else here.

## Decisions

### D1 — Dismissal reuses `bridge.notification.request`, in the other direction

`onNotificationRemoved` sends `BridgePacket.notificationRequest(cancel = sbn.key)`; the Mac routes that type to
`NotificationManager.withdraw(id:)`, which calls
`UNUserNotificationCenter.removeDeliveredNotifications(withIdentifiers:)`.

The Mac already posts with `sbn.key` as the notification identifier (the `id` field of `bridge.notification`),
so the identifier needed to withdraw is the one already on both sides. No new field, no mapping table.

Alternative considered: a distinct `bridge.notification.dismissed` type for the phone → Mac direction. Rejected
— the semantics are identical ("this notification is gone, drop it"), and one type with one meaning in both
directions is less to keep in step across two codebases.

**Loop prevention.** The Mac withdrawing a notification must not trigger its own dismissal send. The Mac's
existing send is driven by `userNotificationCenter(_:didReceive:)` — a *user* action on the banner — not by
programmatic removal, so `removeDeliveredNotifications` does not call back into it. This is asserted in the
spec and checked by hand on device rather than left to assumption.

```
phone                                   Mac
  │                                      │
  │ user clears notification             │
  │ onNotificationRemoved(sbn)           │
  │   ├─ evict replyCache by sbn.key     │
  │   └─ notificationRequest(cancel) ───▶│ handlePacket("bridge.notification.request")
  │                                      │   └─ removeDeliveredNotifications([id])
  │                                      │        (programmatic — no didReceive, no send back)
```

Degradation: link down → the packet is dropped, matching this capability's existing "a late notification is
noise" rule. The Mac keeps a stale copy until the user dismisses it. Stated in the spec rather than hidden.

### D2 — `getCachedAction` reads without consuming

`replyCache.remove(id)` → `replyCache[id]`. Eviction already happens in `onNotificationRemoved`, keyed by
notification, which is the correct lifetime: a RemoteInput is valid while its notification is live.

Alternative considered: keep `remove` and re-insert the entry after a successful send. Rejected — it is the same
behaviour with a race between the two operations, and it leaves a window where a concurrent reply misses.

Note this strictly widens what the cache allows, so the 200-entry LRU bound still holds and the existing
eviction test still applies.

### D3 — `<queries>` with explicit intent filters, not `QUERY_ALL_PACKAGES`

The manifest declares visibility of packages that can post notifications rather than requesting the broad
permission. `QUERY_ALL_PACKAGES` is Play-policy restricted, and the existing spec already requires the list be
built without it.

The app only ever resolves labels for packages that have *already posted a notification to this listener*, and
a `NotificationListenerService` can resolve those. The narrow declaration is both sufficient and honest about
what the app looks at.

Failure path is unchanged and already correct: `getApplicationLabel` throwing `NameNotFoundException` falls back
to the package name. After this change that fallback should be rare rather than the norm.

### D4 — A dedicated `bridge.notification.filter` packet for the count

Body: the number of allowed apps, the number of seen apps, and the master-mute flag. Sent by the phone when a
connection is established and whenever the filter state changes.

Alternatives considered:

- *Fold it into `bridge.identity`.* Rejected: identity answers "which device is this" and is sent once per
  connection; the count changes on a toggle. Re-sending identity to announce a filter change would make the
  packet mean two things.
- *Have the Mac ask.* Rejected: a request/response pair where a push suffices, and it would leave the Mac's
  display stale between polls.
- *Derive it on the Mac from traffic.* Not possible — the Mac cannot tell "no apps allowed" from "allowed apps
  are quiet", which is precisely the confusion this item exists to end.

Both `isMuted` and the counts travel, so the Mac can distinguish *paused* from *nothing allowed* from
*3 apps mirroring* without inferring. The Mac's own state starts as unknown and is only set by a received
packet, so it never shows an invented number — including against an older phone that never sends the type.

Backward compatibility: an older Mac logs the unknown type and carries on (its `default` branch already does
this); an older phone simply never sends it and the Mac reports unknown. Neither combination loses
notification mirroring, which matters because distribution is `brew upgrade` plus a manual APK sideload and
version skew is the normal state (BR-12).

### D5 — "Enable all" is a bulk transition on `FilterState`

`FilterState.withAllAllowed(from: Set<String>)` returns a copy with `allowed = allowed + seen`, and the store
persists once. Doing it as a pure transition on the Android-free model keeps it unit-testable on the JVM beside
the existing `withAllowed` tests, and one persist beats N.

The button is offered only when the seen set is non-empty, and the empty state distinguishes two cases the user
experiences very differently: *apps have notified but none are enabled* (actionable now) and *nothing has
notified yet* (nothing to do). The existing `emptyHint` TextView covers the second case today; it gains the
first.

### D6 — Where the count renders

Android: the notifications card in `activity_main.xml` already has a subtitle TextView under its heading; it
carries the count. Mac: a line in `StatusPanel`'s status block, which BR-2 left as a fixed-width panel with
room for it.

The strings follow the audit's suggestion — `Mirroring 3 apps` / `Paused` / `No apps on yet` — and go in
`strings.xml` on Android rather than inline, which is the convention the project already follows and which
BR-9 is separately fixing elsewhere.

## Risks / Trade-offs

- **The dismissal send runs on every notification removal, on the listener's callback thread.** → It is one
  small packet built from fields already in hand, and `packetSender` is the same path `onNotificationPosted`
  already uses. No I/O is added to the callback.
- **Clearing all notifications at once sends a burst of packets.** → Each is a few dozen bytes over the BLE
  control channel; well under the PRD's 10 KB rule. If this proves noisy on device, batching is a later
  refinement, not a reason to hold the fix. Called out so the device test looks for it.
- **`<queries>` changes what the app can see, which is a privacy-adjacent manifest change.** → It is narrower
  than `QUERY_ALL_PACKAGES` and is what the existing spec already asks for. The app resolves labels only for
  apps that have posted to the listener.
- **A new packet type is one more thing to keep in step across two codebases** — exactly BR-12's complaint
  about version skew. → It is added to `protocol/constants.json` with a test vector exercised on both sides,
  and both ends degrade to "unknown" rather than failing.
- **"Enable all" could mirror an app the user would not have chosen**, since it enables everything seen. → It
  is an explicit, labelled action rather than a default, every app remains individually toggleable afterwards,
  and the screen says what it will do before the tap.

## Migration Plan

No persisted-state migration, by decision: `allowed` is not seeded from `seen` on upgrade, so an existing
install behaves exactly as a new one and nothing starts mirroring because an APK was installed. Persisted keys
are unchanged.

Rollback is reverting the branch and rebuilding both apps. The only cross-version concern is the new packet
type, and both ends ignore what they do not understand.
