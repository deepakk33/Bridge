## REMOVED Requirements

### Requirement: ADB grant script grants both permissions
**Reason**: Verified on SM-S931B / Android 16 (API 36) on 2026-10-28. `pm grant com.bridge.app
android.permission.READ_CLIPBOARD_IN_BACKGROUND` fails with `SecurityException: Permission ...
is managed by role`, so neither the USB nor the wireless scenario can succeed. The script's
clipboard half does not even attempt that command — it runs `appops set com.bridge.app
READ_CLIPBOARD allow`, which succeeds only because `READ_CLIPBOARD` already has default mode
`allow` for every app (confirmed against untouched packages and after `appops reset
com.bridge.app`). The appop is not the gate; Android's focused-window rule is. So the script
changes nothing and then prints a success message.
**Migration**: None. `scripts/adb-grant-clipboard.sh` is deleted. Phone-to-Mac clipboard needs
no setup step: the Quick Settings tile and the in-app send control work on a fresh install, as
`clipboard/sync` now states.

### Requirement: ADB grant verification check
**Reason**: The check it performs reports the `READ_CLIPBOARD` appop, whose default is `allow`,
so `--check` prints "Clipboard background access granted. Full sync active." on every install
including one where nothing was ever granted. A verification that cannot fail is worse than no
verification — it is a green light for a state the app never reaches.
**Migration**: None. The script is deleted. The Android clipboard card is the honest report of
the sync state, per "Clipboard card reflects the current sync state" in `clipboard/sync`.

### Requirement: Grant survives reboot but not factory reset
**Reason**: Describes the persistence of a grant that cannot be applied in the first place. With
no grant there is no reboot behaviour and no factory-reset behaviour to specify.
**Migration**: None.

### Requirement: Degraded mode without ADB grant
**Reason**: Its substance is correct and worth keeping, but its framing is not: it describes the
manual path as a degradation from a default that Android 16 never permits, conditioned throughout
on a grant that cannot exist. Manual is the only state, not the fallback state.
**Migration**: Restated as "Background phone-to-Mac clipboard is unobtainable on Android 16" in
`clipboard/sync`, which keeps the no-recurring-read guarantee, the Mac-to-phone guarantee and
both manual triggers, and drops the conditioning on a grant.

### Requirement: Clipboard card reflects the current sync state
**Reason**: Added by BR-9 and still entirely correct, but it is a `clipboard/sync` requirement
that was filed under `clipboard/adb-grant` because that capability owned the degraded-mode copy
at the time. The capability is being deleted; the requirement must not go with it.
**Migration**: Moved verbatim into `clipboard/sync`, less its "Background watcher running, UI
shows full sync" scenario, which specifies a state no Android 16 build can enter.
