# Bridge — Android ↔ Mac Direct Link

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![macOS 15+](https://img.shields.io/badge/macOS-15.0%2B-black.svg)](#mac-app)
[![Android 12+](https://img.shields.io/badge/Android-12%2B%20(API%2031)-3DDC84.svg)](#android-app)
[![Releases](https://img.shields.io/github/v/release/deepakk33/Bridge?include_prereleases&label=release)](https://github.com/deepakk33/Bridge/releases/latest)

**Website: https://bridge-mac-android.vercel.app**

Bridge makes **any Mac and any Android phone** behave as one machine when they are in the
same room. No network, no account, no cloud: **BLE for control, Wi-Fi for bulk**, speaking
the KDE Connect wire format. It mirrors notifications, syncs the clipboard, shares the
battery level, and transfers files over a mutually-authenticated (pinned-cert) link.

Installing the app on both devices is the only setup step — the two find each other over
Bluetooth LE and pair. Nothing device-specific is baked into either build.

```sh
brew install --cask deepakk33/tap/bridge-link      # Mac
# Android: sideload the APK from the latest release
```

- **Mac**: Swift 6, SwiftUI menu-bar app, Core Bluetooth, Network.framework
- **Android**: Kotlin, `connectedDevice` foreground service, `NotificationListenerService`
- **Protocol**: newline-delimited JSON, `bridge.*` packet types, mutual TLS

## What it does

| Feature | Direction | Packets |
|---|---|---|
| Notification mirroring, with inline reply from the Mac | phone → Mac (reply Mac → phone) | `bridge.notification`, `.notification.reply`, `.notification.request` |
| Clipboard sync — **manual on Android 16**, see [limitation](#clipboard--important-limitation-android-16) | both | `bridge.clipboard`, `.clipboard.connect` |
| Battery level in the menu bar | phone → Mac | `bridge.battery` |
| File transfer from the share sheet | both | `bridge.share.request` |
| Pairing, identity, link liveness | both | `bridge.identity`, `.pair`, `.ping` |

Control packets ride a **BLE L2CAP channel** (always on, ~1 m range). File bodies take a
tier: under 500 KB over **Bluetooth RFCOMM**, at or above over **Wi-Fi LAN** (mDNS + TCP)
when both devices share a network — otherwise it falls back to RFCOMM and says so. Both
ends pin each other's certificate; everything is mutually authenticated TLS.

Full requirements and decisions: [docs/PRD.md](docs/PRD.md). What each iteration built:
[docs/ITERATIONS.md](docs/ITERATIONS.md). Specs and change history: [openspec/](openspec/).

## Repo layout

```
mac/        Xcode project (Swift)        — Bridge.xcodeproj, generated from project.yml
android/    Gradle project (Kotlin)      — android/Bridge
protocol/   Shared test vectors + BLE UUIDs (constants.json)
docs/       PRD, BACKLOG, DEVELOPMENT, ITERATIONS, spike notes
openspec/   Specs + archived changes (one per iteration)
scripts/    Build / install / release / cert helpers
packaging/  Homebrew cask (bridge-link.rb)
web/        Landing page (static)
dist/       Built release artifacts (.zip + .apk)
```

## Prerequisites

- **Mac**: macOS 15+, Xcode, [xcodegen](https://github.com/yonaskolb/XcodeGen) (only if you
  need to regenerate the project from `project.yml`).
- **Android**: Android 12+ (API 31) device with USB debugging, Android SDK + `adb` on PATH.

## Mac app

### First-time setup
1. Create the self-signed code-signing cert (once; persists in Keychain):
   ```sh
   scripts/create-signing-cert.sh
   ```
2. Open the project and run:
   ```sh
   open mac/Bridge/Bridge.xcodeproj      # then select the "Bridge" scheme → Run
   ```
   (If `Bridge.xcodeproj` is missing or stale: `cd mac/Bridge && xcodegen generate`.)

The app lives in the **menu bar**, not the Dock.

### Restart
Quit from the menu-bar panel, then relaunch the built app:
```sh
open ~/Library/Developer/Xcode/DerivedData/Bridge-*/Build/Products/Debug/Bridge.app
```

### Reinstall / clean rebuild
This is a local Xcode dev build (not a distributed `.app`). In Xcode: Product → Clean
Build Folder, then Run. A full reset of pairing state:
- Trusted devices: `defaults delete com.bridge.mac trustedDevices` (key
  `com.bridge.mac.trustedDevices` in `UserDefaults`).
- Cert: remove **"Bridge Dev"** from Keychain Access, then re-run `create-signing-cert.sh`.

### Required Mac permissions (TCC)
Grant when prompted, or in System Settings:
- **Bluetooth** (scan/connect), **Local Network** (Wi-Fi file transfer), **Notifications**
  (post mirrored notifications). The unsigned dev build must be allowed under Notifications.

## Android app

### Build + install
```sh
scripts/adb-install.sh          # gradle assembleDebug → adb install -r → launch
# or:
cd android/Bridge && ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Required Android permissions
On first launch, grant when prompted and enable the manual ones:
- **Bluetooth** (advertise/connect) and **Notifications** — in-app prompts.
- **Notification access** — Settings → Notifications → Notification access → enable Bridge
  (the app surfaces a shortcut). Needed to mirror notifications.
- **Battery optimization** — allow Bridge to ignore it (in-app button) so the service is
  not killed.
- **Clipboard Quick Settings tile** — add the "Bridge Clipboard" tile to the quick panel.

## Pairing

Pairing is **Mac-initiated and auto-accepted** — there are no device IDs to configure.

1. Start the Mac app and the Android app; make sure Bluetooth is on both sides.
2. The Mac discovers the advertising phone, connects, and the phone auto-accepts the pair
   handshake. Both sides pin each other's certificate (TrustStore). The Mac panel shows a
   green **Connected**; the phone shows the Mac's name.

### Swapping to a different phone
1. Install and launch the app on the new phone (grant permissions as above).
2. On the Mac menu-bar panel, tap **Unpair** to forget the old phone (otherwise the Mac
   may reconnect to whichever device advertises first — the link is 1:1).
3. The new phone advertises; the Mac connects and auto-pairs it.

### Unpair / re-pair
- **Mac**: menu-bar panel → **Unpair**.
- **Android**: uninstalling clears its trust store; reinstall to pair fresh. In-app, use
  **Reconnect / Pair** to restart advertising and re-run the handshake.

## Clipboard — important limitation (Android 16)

Automatic **background** clipboard sync is **not possible on Android 16 / One UI 8.5
without rooting the phone**. The OS made `READ_CLIPBOARD_IN_BACKGROUND` role-managed, so it
cannot be granted to a sideloaded app — `pm grant` refuses it with a `SecurityException`, and
the `READ_CLIPBOARD` app op is no substitute because every app already has it. Shizuku can't
either; only root. There is no setup step, no script and no adb command: this is the state a
fresh install is in and stays in. Clipboard therefore works **manually**:

- **Phone → Mac**: tap the **Bridge Clipboard** Quick Settings tile, or use the send control
  in the app. Opening the app does **not** send your clipboard — focus is not consent.
- **Mac → Phone**: **open the app** — it applies whatever the Mac last copied (clips
  received while the app was backgrounded are cached and flushed on focus).

## Connection status

Opening the Android app runs a quick **handshake** (ping + 3 s timeout): if the Mac is
gone it drops the stale "Connected", re-advertises, and reconnects. The foreground service
is set to survive being swiped from recents.

## Troubleshooting

- **Phone shows "Connected" but nothing works** → open the app (forces the handshake), or
  tap **Reconnect**. Check Bluetooth is on.
- **Notifications don't mirror** → Notification access not enabled, or the app is filtered
  out (deny-by-default notification filter — allowlist the app in the Notifications screen).
- **No auto clipboard** → expected on Android 16; use the tile / open the app (see above).

## Development

Work is queued in [docs/BACKLOG.md](docs/BACKLOG.md) — one row plus one detail block per
item — and driven end to end by the `/work BR-n` command in Claude Code: clarify → branch
→ OpenSpec proposal → implement test-first → review → archive → push. Three human gates
and nothing else interrupts: **plan** (the spec artifacts), **review** (the diff plus a
real-hardware test), **hand-off** (push). Phase is re-derived from disk every run, so a
half-finished item survives a closed terminal.

Full rules: [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md). Shipped history:
[docs/ITERATIONS.md](docs/ITERATIONS.md) and [openspec/changes/archive/](openspec/changes/archive/).
Requirements and architecture decisions: [docs/PRD.md](docs/PRD.md).

House rules: never commit to `main` (branch `work/<id>-<slug>`); Swift 6 strict
concurrency with no force unwraps; Kotlin coroutines and sealed classes, no `!!`; a
protocol change lands a vector in [protocol/test-vectors/](protocol/test-vectors/) and is
checked on both sides.

### Verify

```sh
xcodebuild -project mac/Bridge/Bridge.xcodeproj -scheme Bridge test
(cd android/Bridge && ./gradlew test assembleDebug)
openspec validate --change <change-name>
```

## Releasing

```sh
scripts/build-release.sh 0.1.2      # → dist/Bridge-0.1.2.zip + dist/bridge-0.1.2.apk, prints the sha256
```

The Mac app is ad-hoc signed; the APK needs `~/.bridge/keystore.properties` (the script
refuses to produce a debug-signed APK). Paste the printed sha256 into
[packaging/homebrew/bridge-link.rb](packaging/homebrew/bridge-link.rb), bump the version
there and in `android/Bridge/app/build.gradle.kts`, then attach both artifacts to a
GitHub release. The cask is served from `deepakk33/tap`.

## Contributing

Issues and pull requests are welcome. Read [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)
first — a change starts as a backlog item and an OpenSpec change, not as a diff. The
website source is in [web/](web/).

## License

[MIT](LICENSE) © deepakk33
