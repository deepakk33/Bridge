## Context

See `proposal.md` — *Why*. What shapes the approach:

`PermissionRows.of` is already a pure, Context-free builder with its own unit test, and
`PermissionStatusChecker` already separates the two Context-dependent checks from it.
`PermissionItem.detail` already exists as a nullable field with no reader; for Battery
Optimization it already holds consequence-shaped text ("Set Bridge to Unrestricted so One UI does
not kill the service.").

`MainActivity` renders the list with `joinToString("\n")` into one monospace `TextView`, owns one
hard-coded `batteryOptButton`, and reports a denied runtime permission by writing into `macName`.

`LinkState` already supplies `promptTitle`, `promptBody` and `actionLabel` per state, consumed
today only by the `onResume` `AlertDialog` and already unit-tested. `refreshLinkUi()` already runs
on every resume and on every link-state callback, so inline guidance needs no new trigger.

The screen is Android Views with Material Components, inside a `NestedScrollView`. No Compose.

## Goals / Non-Goals

**Goals**

- The permission section is a list of rows, each stating what it costs and carrying its own
  action.
- Every runtime permission denial has a route back from inside the app.
- Link guidance appears once, where the state is shown, and never as a modal.
- The permission model stays a pure Kotlin value type, unit-testable without a Context.

**Non-Goals**

- Any Mac change, protocol change or packet change.
- Jetpack Compose, a RecyclerView adapter, or any new dependency.
- Resurrecting the ADB clipboard grant in any form — BR-15 removed it and
  `ui/permission-panel` forbids it.
- Changing deny-by-default notification filtering, or anything about what the permissions do once
  granted.

## Decisions

### 1. Rows are inflated into a `LinearLayout`, not driven by a RecyclerView

Four to five rows, a fixed set, no scrolling of their own, nothing to recycle. A
`view_permission_row.xml` (dot, name, consequence, button) inflated once per `PermissionItem`
into a container is less code than an adapter plus a ViewHolder, and the screen already sits
inside a `NestedScrollView`.

*Alternative rejected:* RecyclerView — an adapter, a ViewHolder and a DiffUtil for five static
rows.

### 2. `PermissionItem.detail` becomes the consequence line; the action becomes data

`detail` stops being nullable and dead and carries the one-line consequence each row shows. A new
`PermissionAction` — a pure Kotlin sealed type in `com.bridge.permissions`, holding no Context
and importing nothing beyond `Manifest` — says what the row's button does:

| Action | Button | `MainActivity` does |
|---|---|---|
| `NotificationAccess` | Enable | `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS` |
| `BatteryOptimization` | Allow | `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` |
| `Runtime(permissions)` | Grant | request, or app settings — decision 3 |
| `None` | — | granted rows show no button |

`PermissionRows.of` keeps its shape — statuses in, rows out — so `PermissionRowsTest` extends
rather than being rewritten. Every `Context` and `Intent` stays in `MainActivity`; the
permissions package keeps none.

*Alternative rejected:* a second parallel field for consequence text, leaving `detail` dead. The
field exists and this is what it is for.

### 3. The request-or-settings decision is a pure function over two booleans

`shouldShowRequestPermissionRationale` returns false both *before* the first prompt and *after* a
permanent denial, so it cannot tell them apart alone. A `has_asked_<permission>` boolean in
`SharedPreferences`, written when a request is launched, supplies the second input:

```kotlin
fun resolve(hasAsked: Boolean, showRationale: Boolean): RuntimeRecovery =
    if (hasAsked && !showRationale) OPEN_APP_SETTINGS else REQUEST
```

Pure, two inputs, four cases, unit-tested without Android. `MainActivity` reads the preference,
calls `shouldShowRequestPermissionRationale`, and acts on the verdict.

*Alternative rejected:* always opening app settings for a denied row. It makes the common
single-denial case needlessly slow and strands the user in Settings instead of in Bridge.

### 4. The resume modal's copy moves onto the status card, keeping a dismiss

`maybePromptLinkIssue`, `syncLinkPrompt`, `applyPromptText` and the `linkPrompt` field are
deleted along with their `onResume`/`onPause` calls. `LinkState.promptBody` and `actionLabel` are
unchanged and feed a guidance `TextView` plus an action `Button` on the status card, `GONE` when
`!state.needsAttention`, rendered from the existing `refreshLinkUi()`. A dismiss control hides it
for this screen instance; any subsequent state change clears the dismissal, so the line cannot be
permanently silenced into a lie. `promptTitle` loses its only caller and is deleted with its test.

*Alternative rejected:* keeping the dialog for `BLUETOOTH_OFF` alone. One rule is easier to trust
than an exception, and the inline line carries the same button.

### 5. Denied permissions stop writing into `macName`

`permissionLauncher`'s failure branch no longer sets `macName.text`; it calls
`updatePermissionStatus()`, and the denial appears where every other permission state appears.
`macName` goes back to being a function of link state alone, which is what every other code path
already assumes.

### 6. Granted rows show no button

A granted row has nothing left to do, and a button that re-opens a settings screen for something
already granted is noise. This also keeps the Android screen consistent in spirit with the Mac
panel's rule — the Mac hides granted rows entirely because its panel is transient; the Android
screen is opened deliberately, so it keeps the row and drops the action.

## Flow — a denied runtime permission, recovered

```
first launch ──► permissionLauncher.launch(missing)
                 write has_asked_<p> = true
                 │
       denied ───┴─► updatePermissionStatus()        (macName untouched — decision 5)
                     row: red dot, "Bluetooth",
                          "Bridge cannot reach your Mac without it", [Grant]
                     │
          [Grant] ───┴─► RuntimeRecovery.resolve(hasAsked = true, showRationale = ?)
                            REQUEST ──────────► system prompt
                                                  granted ──► onResume →
                                                              updatePermissionStatus()
                                                              + startBridgeService()
                            OPEN_APP_SETTINGS ─► ACTION_APPLICATION_DETAILS_SETTINGS
                                                  return ──► onResume → same refresh
```

## Risks / Trade-offs

- **`has_asked_<permission>` is absent on existing installs** → `resolve` reads that as "never
  asked" and offers a prompt. For a user who granted at first launch the row is not shown at all;
  for one who denied, the cost is at most one prompt that Android may decline to show, after
  which the next tap resolves to app settings. Safe in both directions.
- **A dismissed guidance line could hide a real problem** → Dismissal is per screen instance and
  is cleared on any state change, so it cannot outlive the condition it describes.
- **Five rows make the screen longer** → It is inside a `NestedScrollView` already, and the rows
  replace a block of text that was the same length and unreadable.
- **The runtime rows show as granted for almost every user, almost always** → Accepted: that is
  the point of decision 6 (no button) and it is what makes a denial visible when it does happen.
  It is a deliberate departure from the Mac panel's hide-when-granted rule, and
  `ui/permission-panel` states the reason.
- **BR-10's "adb command is reachable from the UI" criterion cannot be met** → BR-15 deleted the
  grant, the script and the capability. Recorded as dropped in `proposal.md` and in `tasks.md`,
  and to be marked `[~]` in `docs/BACKLOG.md` at wrap-up, as BR-2 and BR-6 did for theirs.

## Migration Plan

None required. No persisted format changes beyond the new `has_asked_<permission>` boolean, whose
absence is handled above. No protocol or packet change, so an updated phone and an un-updated Mac
interoperate exactly as before, and the APK can be rolled back on its own.
