# Android ↔ Mac Link — Requirements & Project Definition

Oct 13, 2026 · 

## Why this gap exists

The Android↔Mac gap is commercial, not technical. Every shipping phone-to-desktop link exists because one vendor owns both ends or needs the pairing to defend a platform.

Microsoft builds Phone Link because it sells no phone, so tying Android to Windows protects Windows. Samsung reciprocates with a privileged Link to Windows system service baked into One UI. Apple builds Continuity because it owns macOS and iOS, and the friction of leaving is the point. Nobody owns both Android and macOS, and neither Google nor Apple gains from building the bridge for a rival.

The radios are the same on both sides: Bluetooth LE for discovery and wake, Wi-Fi for payload, mDNS for service advertisement. Apple's Continuity adds UWB on newer hardware for ranging, but no core feature depends on it. What is genuinely missing is a published protocol and a maintainer, not silicon.

For a solo build that changes the calculus favourably. A personal tool serves one phone and one Mac, both known, both under the builder's control. It can require developer mode, skip App Store review, skip Play Store policy, and hard-code assumptions that a commercial product could never make.

## Goals, non-goals, success criteria

The goal is one tool, call it **Bridge**, that makes the S25 and the MacBook Air behave like two halves of one machine whenever they are in the same room, with no network, no account and nothing in the middle.

**In scope**

1. Phone notifications appear in macOS Notification Center, with reply-from-Mac where the notification supports it.
2. Clipboard syncs both ways, text first.
3. Files move both ways without unlocking the phone or opening a browser.
4. Phone screen mirrors to the Mac with keyboard and mouse control.
5. Phone battery, signal and Do Not Disturb state visible on the Mac.
6. Incoming call shown on the Mac, with mute and reject.

**Explicitly out of scope**

- Multi-user support. One person only, though the phone itself may be replaced, so device identity is never hard-coded.
- App Store or Play Store distribution, and therefore the review constraints that come with it.
- A cloud relay, or any server to run, host or pay for.
- iMessage-style history sync or a message archive on the Mac.
- Anything requiring root, custom ROM or an unlocked bootloader on the S25.

**Success criteria**

Bridge succeeds if, after a cold boot of both devices and no manual step, a notification arriving on the phone appears on the Mac in under two seconds, and a copied string is pasteable on the other device in under one second. Both must hold with the Wi-Fi off on both devices. It fails if it needs to be restarted by hand more than once a week, or if walking to the kitchen and back requires any action to restore the link.

## Target environment

Both devices are modern enough that no capability is blocked by hardware. Every constraint below is a software policy.

|  | MacBook Air M3 | Galaxy S25 5G |
| --- | --- | --- |
| OS | macOS 26.6.2 | Android 16, One UI 8.5 |
| API surface | Swift 6, Network.framework, AppKit | API level 36, Kotlin |
| Kernel | Darwin / XNU | Linux 6.6.98 (GKI) |
| Wi-Fi | Wi-Fi 6E, AWDL peer-to-peer | Wi-Fi 7, Wi-Fi Direct and Wi-Fi Aware |
| Bluetooth | 5.3, LE + Core Bluetooth | 5.4, LE + BluetoothGatt |
| Wired | USB-C, USB 3 | USB-C, USB 3.2 Gen 1, ADB over USB and TCP |
| Hard blocker | Unsigned code needs manual Gatekeeper override | Sideload needs developer options enabled |

**What the baseline enables**

Wi-Fi 6E on the Mac and Wi-Fi 7 on the phone mean a same-band LAN transfer sustains well over 100 MB/s in practice, so file sync and screen mirroring are both bandwidth-comfortable. Bluetooth LE on both ends covers presence detection and wake without keeping a Wi-Fi socket hot.

**What the baseline blocks**

macOS 26 will not let an unsigned app reach LAN hosts silently. Android 16 will not let a background service read the clipboard. Neither restriction has a hardware cause, and both have documented workarounds covered in the constraint sections below.

## Prior art, and what to take from it

Nothing needs to be invented. Two projects between them cover almost the whole feature list, and the right move is to adopt their protocols rather than design new ones.

| Project | What it solves | Mac client state | Take or leave |
| --- | --- | --- | --- |
| [KDE Connect](https://valent.andyholmes.ca/documentation/protocol.html) | Notifications, clipboard, files, SMS, battery, media, remote input | Exists, rough on macOS | **Take the protocol**, write own clients |
| [scrcpy](https://alternativeto.net/software/kde-connect/?platform=mac) | Screen mirror and control over USB or Wi-Fi, no root | Mature, runs on macOS | **Take as a subprocess**, do not reimplement |
| [LocalSend](https://alternativeto.net/software/kde-connect) | Encrypted LAN file transfer, no account | Mature, cross-platform | Leave, KDE Connect share covers it |
| rquickshare / NearDrop | Quick Share reimplemented for desktop | Receive works, send flaky | Leave, reverse-engineered and fragile |
| Sefirah, Soduto | Phone Link style clients for other desktops | Soduto is macOS-only and stale | Leave, read for reference |
| MacDroid, QuickDrop | Commercial file transfer | Shipping, paid | Leave |

**The decision this forces**

Bridge should speak the KDE Connect wire protocol rather than a private one. That protocol is public, stable at version 7 to 8, and already has a battle-tested Android implementation to crib permission handling from. It covers notification, notification.reply, clipboard, share.request, battery, telephony, mpris and mousepad as named packet types, which maps almost exactly onto the goals above.

Screen mirroring stays outside the protocol. scrcpy already does it well over ADB, and reimplementing a low-latency H.264 pipeline is a project of its own. Bridge should launch and supervise scrcpy, not replace it.

## Android 16 constraints on the S25

Four platform rules shape the phone-side design. None is fatal, each costs a design decision.

**Foreground service type is mandatory and policed.** Since Android 14 every foreground service must declare its type and hold the matching permission, and [Android 16 adds that background jobs started from a foreground service must obey their own runtime quotas](https://developer.android.com/develop/background-work/services/fgs/changes). Bridge's persistent socket should run as `connectedDevice`, which is the honest type and avoids the six-hour `dataSync` timeout. The service must be started while the app is in the foreground; starting it from a `BOOT_COMPLETED` receiver throws `ForegroundServiceStartNotAllowedException`.

**Clipboard is read-blocked in the background.** Since Android 10 only the app holding input focus, or the current default IME, can read the clipboard. A background service calling `ClipboardManager.getPrimaryClip()` gets nothing. Three ways out, in descending order of ugliness:

1. Grant `READ_LOGS` plus `android.permission.READ_CLIPBOARD_IN_BACKGROUND` over ADB once, via `pm grant`. Survives reboots, dies on factory reset.
2. Ship a tiny no-op IME and make it the active keyboard. Works, but it hijacks typing.
3. Push-only from the Mac, and on the phone sync the clipboard when the Bridge app is foregrounded or from a quick-settings tile tap.

Option 1 is right for a personal build. Mac-to-phone direction has no restriction at all.

**Notification access is a special permission.** `NotificationListenerService` needs the user to enable Bridge under Notification access in settings. It is granted by hand, once, and it survives updates. It delivers title, text, app name, actions and the `RemoteInput` key needed for reply-from-Mac, which is exactly the `requestReplyId` field in the KDE Connect notification packet.

**Doze and One UI battery management will kill the socket.** Stock Doze is survivable with a foreground service. Samsung's additional battery optimisation is more aggressive, so Bridge must be set to Unrestricted under battery usage, excluded from Sleeping apps, and must hold a partial wake lock only while a transfer is live. A BLE advertisement from the Mac is the cheap way to wake the phone's socket instead of keeping it hot.

**SMS is the one to think twice about.** Reading and sending SMS needs the default-SMS role or the `READ_SMS` and `SEND_SMS` permissions. It is sideload-only territory and will trip Samsung's permission warnings. It stays out of v1.

## macOS 26 constraints on the MacBook Air

The Mac side has fewer constraints but one that silently breaks everything if missed.

**Local Network permission is the trap.** macOS denies LAN access to apps that do not declare it, and the denial is silent. A missing `NSLocalNetworkUsageDescription` means [the permission prompt never fires, the app never appears under Privacy and Security, and there is no way for the user to grant it](https://github.com/getpaseo/paseo/issues/2880). On macOS 26 this bites even apps that previously worked: [Alacritty had to add the key for 26.7, where local network calls fail without it even when permissions look granted in settings](https://github.com/alacritty/alacritty/pull/9056). Bridge's Info.plist must carry both `NSLocalNetworkUsageDescription` and `NSBonjourServices` listing `_bridge._tcp`, and should [fire a probe on first launch so the prompt appears before the user touches anything](https://github.com/jerinjoy/scanimate/issues/10).

**Signing, without paying Apple.** The Developer Program is not needed. Create a self-signed code signing certificate once in Keychain Access, set it to expire in ten years, and sign every build with it. TCC grants are keyed to the code signature, so as long as that certificate stays the same, Local Network and Notification permissions survive rebuilds and reboots. The seven-day expiry trap applies to free Apple ID provisioning profiles for iOS, not to a self-signed macOS app run on the machine that built it. The only cost is a one-time right-click Open to clear Gatekeeper on first launch, and the fact that the app cannot be handed to anyone else without them doing the same.

**Lifecycle.** Register the background component with `SMAppService` rather than a hand-written LaunchAgent plist. It gives launch-at-login, visibility in Login Items, and a supported restart policy.

**Permission prompts to expect, in order:** Local Network on first discovery, Bluetooth on first BLE scan, Notifications to post mirrored notifications, and Accessibility only if Bridge ever injects keystrokes outside scrcpy's own window. Each is a one-time grant, but each can be revoked by a system update, so Bridge needs a status panel that shows which are currently held.

**What macOS will not allow.** There is no API to read another app's clipboard history, no way to post a notification that looks like it came from the originating app, and no supported way to inject input into another process without Accessibility. None of these block the goal list.

## Feature catalogue

Twelve candidate features, rated. Build effort is in developer-days assuming evenings and weekends; maintenance is the risk that an OS update breaks it.

| Feature | Value | Build effort | Maintenance risk | Verdict |
| --- | --- | --- | --- | --- |
| BLE link, pairing, auto-reconnect | Foundational | 5 d | Medium | v1 core |
| Notification mirror, phone to Mac | High | 3 d | Low | v1 |
| Notification reply from Mac | High | 2 d | Low | v1 |
| Clipboard, Mac to phone | High | 1 d | Low | v1 |
| Clipboard, phone to Mac | High | 2 d | Medium, needs ADB grant | v1 |
| File send over Bluetooth | Medium | 2 d | Low | v1 |
| Battery and signal on Mac | Medium | 1 d | Low | v1 |
| File send over Wi-Fi LAN | Medium | 2 d | Low | v1 |
| File send over phone hotspot | Medium | 3 d | Medium, CoreWLAN prompts | v2 |
| Incoming call alert and mute | Medium | 2 d | Medium | v2 |
| Media control from Mac | Low | 2 d | Low | v2 |
| Screen mirror and control | High, but LAN only | 1 d as a shortcut | Medium | Cut, launch scrcpy by hand |
| SMS read and send | Medium | 6 d | High, role and policy | Out |
| Phone app windows on Mac | High | Very high | Very high | Out, needs Samsung privilege |

**The two that get cut, and why**

SMS is cut not because it is hard but because the cost is concentrated in permission theatre rather than code, and a repliable notification already covers the actual need: a WhatsApp or Messages notification can be answered from the Mac through `RemoteInput` without any SMS permission at all.

Screen mirroring is cut, and the network answer is what decides it. Mirroring runs over ADB, ADB needs a direct network path, and the phone is frequently on mobile data while the Mac is on office Wi-Fi. A wrapper would therefore be unavailable in most of the moments it might be wanted, which is a poor return on three weeks. The replacement is a menu bar item that shells out to scrcpy when both devices are on the same Wi-Fi, which is one day of work and honest about its limits.

Running individual phone apps in separate Mac windows, the way Phone Link does on Samsung, is genuinely out of reach. That feature depends on a privileged Samsung system service shipped inside One UI with permissions no sideloaded app can obtain.

**Effort total for v1:** roughly 18 developer-days, which on evenings realistically means seven to nine weeks. The overlay transport adds two days to the original estimate; cutting the mirroring wrapper removes three from what would have been v2.

## Architecture

&#91;embedded content: Bridge architecture · 10 components, 4 channels\]

One Bluetooth LE link stays up between the two devices and carries everything that is small: presence, notifications, clipboard, control. Wi-Fi is borrowed only when a payload needs it, and released afterwards. Nothing routes through a network the two devices do not both sit on, and nothing leaves them at all.

**Process model on the Mac.** A single app, registered through `SMAppService` so it starts at login and restarts if it dies. The menu bar item is the whole UI: connection state, last packet time, permission status, and a file drop target. No separate daemon, because a second process doubles the TCC surface for no gain at this scale.

**Process model on the phone.** One foreground service of type `connectedDevice` owns the socket. `NotificationListenerService` runs in the same process and hands packets to the link manager directly. The clipboard bridge degrades to Mac-to-phone only when the ADB grant is absent, which matters because the phone may be replaced and the grant will not come with it.

**Why bulk is a separate channel.** A large file on the tier 1 link would block notifications behind it for minutes at BLE speeds. So the sender names a tier and a destination in the packet, the receiver opens that channel, the bytes move, and the channel closes. Tier 1 stays free throughout, which is the whole point.

## Protocol and security

Bridge adopts the KDE Connect wire format rather than inventing one. It is documented, proven across three platforms, and the packet set already matches the feature list.

**The envelope.** Every message is a single line of JSON, newline-delimited, with three fields: `id` holding a millisecond epoch timestamp, `type` holding a string like `bridge.clipboard`, and `body` holding the parameters. Packets carry no delivery guarantee and no response guarantee, so every handler is written to be idempotent and to tolerate a packet it did not expect.

**Large payloads go out of band.** A packet that carries bulk data sets `payloadSize` and `payloadTransferInfo`, the latter holding a port the sender is listening on. The receiver opens a second TCP connection to that port and reads exactly that many bytes. This keeps the control channel responsive during a large file transfer, which matters when a notification arrives mid-copy.

**Discovery and handshake.** The phone advertises a Bridge service UUID from its foreground service. The Mac scans for that UUID, connects, reads an identity characteristic carrying `deviceId`, `deviceName`, `deviceType` and two capability arrays, then opens an L2CAP channel for the session. Everything after that is the same newline-delimited JSON regardless of which tier is carrying it, which is what keeps the higher tiers simple: they are just another stream.

**Identity and trust.** Each device generates a self-signed certificate once and keeps it forever. Pairing is a `bridge.pair` packet with `pair: true`, answered with `true` to accept or `false` to reject, timing out after 30 seconds by convention. On acceptance each side pins the other's certificate fingerprint. Every subsequent connection is mutual TLS against that pinned cert, so there is no password, no account, and no server. Packets from an unpaired device are dropped before parsing.

**Packet types for v1**

| Type | Direction | Carries |
| --- | --- | --- |
| `bridge.identity` | Both | Device id, name, type, capabilities, tcpPort |
| `bridge.pair` | Both | Pair accept or reject |
| `bridge.notification` | Phone to Mac | id, appName, title, text, actions, requestReplyId |
| `bridge.notification.reply` | Mac to phone | requestReplyId, message |
| `bridge.notification.request` | Mac to phone | Cancel a notification by id |
| `bridge.clipboard` | Both | Text content |
| `bridge.clipboard.connect` | Both | Content plus timestamp, sent on connect |
| `bridge.share.request` | Both | filename, plus payload transfer info |
| `bridge.battery` | Phone to Mac | currentCharge, isCharging |
| `bridge.ping` | Both | Liveness check |

**Versioning.** The identity packet carries a `protocolVersion` integer. Capability arrays, not the version number, decide what a peer may send, so adding a packet type never requires a version bump on both ends at once.

**The clipboard timestamp rule matters.** On connect, each side sends its clipboard with a timestamp, and the receiver ignores content whose timestamp is zero or older than its own. Without this rule two devices reconnecting will ping-pong stale clipboard contents indefinitely.

## The transport ladder

The phone is always about a metre from the Mac. That single fact removes the need for any third party, because at that distance the two devices can talk radio to radio with no router, no internet and nobody in the middle. This replaces the overlay design entirely.

It is also how AirDrop and Quick Share actually work. Google's own clients discover and handshake over Bluetooth LE, then upgrade: to Wi-Fi LAN when both devices share a network, and when they do not, [one device hosts a Wi-Fi Direct group or hotspot that the other joins, so large files move at Wi-Fi speed with no router and no internet anywhere in sight](https://github.com/ignotusbucius/open-quickshare). Nearby Connections calls this a [bandwidth upgrade, with a handler per medium that produces the SSID, password and port for a SoftAP and accepts the upgraded connection](https://github.com/kidfromjupiter/nearby/wiki/Bandwidth-Upgrade). Bridge copies the pattern.

**Four tiers, chosen automatically.** The link never drops to a lower tier than it needs, and never climbs higher than the payload justifies.

| Tier | Medium | Throughput | Carries | Needs |
| --- | --- | --- | --- | --- |
| 1 | BLE GATT + L2CAP channel | 50 to 100 KB/s | Presence, notifications, clipboard, control | Proximity only |
| 2 | Bluetooth Classic RFCOMM | \~140 KB/s | Small files, images | Proximity only |
| 3 | Wi-Fi LAN | Full speed | Large files, everything | A shared network |
| 4 | Phone hotspot, joined by the Mac | Full speed | Large files with no shared network | User says yes |

Tier 1 is the resident link and is up essentially all the time. Tiers 2 to 4 are opened for a payload and torn down after. The [observed RFCOMM rate of around 140 KB/s](https://github.com/kyujin-cho/Bada/issues/256) makes tier 2 fine for a screenshot and painful for a video, which is what tiers 3 and 4 are for.

&#91;embedded content: Behaviour in all four combinations of range and network\]

**The bottom-right quadrant is a design change.** Tier 3 was originally only for bulk. But if the phone is in the next room on the same Wi-Fi, Bluetooth is out of range while the LAN is perfectly reachable, so there is no reason for Bridge to go dark. Wi-Fi LAN therefore doubles as a control fallback: when the BLE link drops and both devices are on one network, notifications and clipboard keep flowing over mDNS and TCP, and the moment Bluetooth reconnects the session moves back down to tier 1.

This costs almost nothing, because tier 3 has to exist for files anyway. It just gets a second job. The one thing it cannot recover is proximity itself, so anything that should only happen when the phone is physically present stays tied to the BLE link.

**Mac on Ethernet counts as the same network.** A wired Mac and a phone on the house Wi-Fi sit behind one router, so mDNS and TCP work between them normally. That case lands in the top-right quadrant, not the top-left, and it is the best of all of them: Bluetooth for control, wired Wi-Fi for bulk, and no radio contention between the two.

**Losing range and getting it back.** The Mac is the BLE central and the phone is the peripheral. The Mac issues a connect that has no timeout, so when the phone walks out of range the link drops and the request simply stays pending; the moment the phone is back, the system reconnects without Bridge doing anything. The phone keeps advertising from its foreground service whenever it is unpaired or disconnected. There is no polling loop to write and no reconnect timer to tune, which is the main reason to put the Mac in the central role rather than the phone.

The menu bar shows three states and nothing else: connected, out of range, and Bluetooth off. Anything queued while out of range is dropped rather than replayed, because a notification that arrives twenty minutes late is noise.

**Two macOS limits shape tier 4.** First, the Mac cannot be the hotspot. CoreWLAN once exposed `startHostAPModeWithSSID` and no longer does, so [there is no supported way to put a Mac's Wi-Fi interface into host AP mode](https://developer.apple.com/forums/thread/759297). The phone must host, using `startLocalOnlyHotspot`, which creates a hotspot with no internet access intended exactly for [communication between co-located devices](https://developer.android.com/reference/android/net/wifi/WifiManager). Android 16 adds a configuration variant, which lets the SSID stay constant so the Mac treats it as a known network.

Second, the Mac joins with [`CWInterface.associate(to:password:)`, which blocks for the duration of the association and may require an administrator password](<https://developer.apple.com/tutorials/data/documentation/corewlan/cwinterface/associate(to:password:).md>). The network must already be visible to associate to it, so the order matters: the phone raises the hotspot, announces the credentials over the tier 1 link, waits for the Mac to see the SSID in a scan, and only then does the Mac associate. Because the MacBook Air has one Wi-Fi radio, joining the hotspot means leaving the current network, so tier 4 is never automatic. It is a button, with a warning that Wi-Fi will drop, and the Mac rejoins its previous network when the transfer finishes.

**What this buys.** No account, no third party, no coordination server, no metadata leaving the two devices, and nothing to pay for. It also works with both radios off the internet entirely, on a train or on a plane. The only thing given up is reach: if the phone is left at home, Bridge is simply offline, which is the correct behaviour for a tool whose whole premise is that the phone is in your pocket.

## Technology stack

Native on both ends. The tempting shortcut, a Node or Electron daemon on the Mac, is the wrong call here and worth saying plainly.

| Layer | Choice | Why |
| --- | --- | --- |
| Mac app | Swift 6, SwiftUI menu bar app | Needs Notification Center, TCC prompts, `SMAppService`, Core Bluetooth. All Swift-first. |
| Mac tier 1 | Core Bluetooth central, L2CAP channel | Pending connect gives free auto-reconnect on range |
| Mac tier 3 and 4 | Network.framework plus CoreWLAN | Native TLS and Bonjour; `associate` joins the phone hotspot |
| Android app | Kotlin, no UI framework beyond a settings screen | `NotificationListenerService` and foreground services are Kotlin-native |
| Android tier 1 | `BluetoothLeAdvertiser`, `createL2capChannel` | Peripheral role, advertises whenever disconnected |
| Android tier 4 | `startLocalOnlyHotspot` | Purpose-built for co-located devices, no internet attached |
| Shared protocol | JSON, hand-rolled codecs both sides | Ten packet types does not justify a schema compiler |
| Signing | Self-signed certificate from Keychain Access | Keeps TCC grants stable across rebuilds at no cost |
| Mirroring | scrcpy launched by hand from a menu bar item | Cut as a built feature, kept as a shortcut |
| Build | Xcode for Mac, Gradle for Android, one repo | Two apps, one protocol doc, shared test vectors |

**Why not Node on the Mac.** The existing Node and TypeScript fluency makes an Electron or Node daemon look like the fast path, and for raw socket handling it would be. But the Mac side is mostly permission plumbing, not networking: Notification Center posting, TCC grants, Login Items registration, and BLE scanning all need native bindings or fragile shims. An Electron shell also inherits the Local Network permission problem described above, where the Info.plist key has to be injected into every helper bundle. Swift removes a whole category of problem in exchange for a learning curve that is genuinely worth paying here.

**Where the existing strengths do apply.** The protocol work is design and JSON handling, directly transferable. A Node-based test harness that impersonates each end, replays recorded packet sequences, and fuzzes malformed packets is a good use of familiar tools and will save real time during Phase 1.

**Learning curve, now confirmed as acceptable.** Swift concurrency and SwiftUI are perhaps two weekends to productive. Kotlin from a TypeScript background is a weekend. Neither is the hard part. The hard part is Android's permission model and macOS's TCC behaviour, and no language choice avoids that.

## Phasing

&#91;embedded content: Bridge roadmap, 5 phases and 4 gates\]

Each phase ends in something that works on its own, so stopping early still leaves a useful tool rather than a half-built one.

**Phase 1, Spikes.** Three questions, one evening each, all on Bluetooth. Can a Swift central open an L2CAP channel to a Kotlin peripheral and pass a JSON line both ways? Does a pending connect actually re-establish itself when you carry the phone out of the room and back, with no code on your part? And how many kilobytes a second does that channel really sustain? The third answer decides whether clipboard sync feels instant or sluggish, and it is the one most likely to disappoint.

**Phase 2, Link layer.** Pairing, certificate pinning over the L2CAP stream, ping, the watchdog, and the status panel showing connected, out of range, or Bluetooth off. The gate is the kitchen test: walk away until the Mac says out of range, come back, and watch it reconnect without touching anything. Run it ten times. Nothing else gets built until that is boring.

**Phase 3, Notifications.** The first phase that delivers daily value. Mirror notifications to Notification Center, wire `RemoteInput` so replies work from the Mac, and add battery and signal to the menu bar. If the project stalls here, it has still paid for itself.

**Phase 4, Clipboard and files.** Mac to phone clipboard first, since it needs no special permission, then phone to Mac once the ADB grant is scripted. Files go over Bluetooth by default and over the LAN when both devices happen to share one, which covers everything short of a video.

**Phase 5, Extras.** Drawn dashed because none of it is load-bearing. The hotspot tier is the interesting one and also the most likely to disappoint, since it depends on whether `associate` prompts for an admin password every time. Call alerts and the scrcpy shortcut are small. Decide after phase 4, once you know how often you actually move a file big enough to need Wi-Fi.

## Risks and what breaks

The real risk in this project is not building it, it is that it quietly stops working three months later and the cause is unobvious.

| Risk | Likelihood | Impact | Mitigation |
| --- | --- | --- | --- |
| One UI update kills the background service | High | Notifications stop silently | Heartbeat watchdog; Mac shows last-seen time in menu bar |
| BLE throughput below estimate | Medium | Clipboard feels sluggish | Measure in the spike; L2CAP rather than GATT notifications |
| BLE link drops and does not re-establish | Medium | Everything stops, silently | Pending connect rather than a reconnect loop; out-of-range state shown |
| `associate` needs an admin password each time | Medium | Tier 4 becomes unusable | Verify in the spike; fall back to tier 2 or a USB cable for large files |
| Hotspot SSID changes every time | Medium | Mac re-prompts on every transfer | Android 16 configuration variant pins the SSID; verify the permission it needs |
| ADB clipboard grant lost when the phone is replaced | High | Phone-to-Mac clipboard dies | Scripted re-grant in the repo; feature degrades rather than failing |
| macOS point release resets TCC grants | Medium | Bluetooth or Notifications denied | Permission status panel in the Mac app, checked at launch |
| Bluetooth battery drain on the phone | Medium | Tool gets uninstalled | Long connection interval when idle; measure at end of phase 2 |
| Certificate pinning breaks after a reinstall | Medium | Silent connection refusal | Clear unpair-and-repair flow, surfaced in the UI, not buried |
| Interest fades before v1 | High | Half-built tool | Phase 3 ships something useful standalone, see phasing |

**The failure mode that matters most.** Every one of these presents to the user as nothing happening. A notification that does not arrive looks identical to no notification having been sent. So observability is not a nice-to-have in this project: the Mac menu bar must always show connection state, last packet time, and which permissions are currently held. Build that in Phase 1, not as polish later.

**Scope creep is the second risk.** The catalogue deliberately cuts SMS, per-app windows and the mirroring wrapper. All three will feel tempting around week five, when the core works and the appetite is high. The v1 definition exists to be held to.

## Decisions taken

All five are answered. The questions are kept below as they were asked, with your replies attached to them; the table after records what each answer changed in the design.

1. **Apple Developer Program, yes or no?** 99 USD a year buys notarization and an app that survives reboots without weekly re-signing. Free signing works but expires every seven days. This decision shapes how the Mac app is distributed to itself.
2. **Is screen mirroring actually wanted, or just interesting?** It is the single largest chunk of v2 effort. If the honest use is notifications and clipboard, mirroring can be dropped entirely and scrcpy run by hand on the rare occasion it is needed.
3. **How much Swift appetite is there?** The native recommendation above assumes willingness to learn Swift properly. If the answer is no, the fallback is a Go or Rust headless daemon on the Mac with a thin Swift menu bar shell, which is more moving parts but less Swift.
4. **Is the phone ever on a different network from the Mac?** The whole design assumes same-LAN. If cross-network is wanted, that adds a relay or Tailscale dependency and changes the security model substantially.
5. **Does this stay private, or get published?** A personal tool can hard-code device IDs and require ADB grants. A published one cannot. Deciding now avoids rewriting the pairing flow later.

| Question | Answer | What changed |
| --- | --- | --- |
| Developer Program | No spend | Self-signed Keychain certificate instead; no weekly re-signing, app cannot be shared |
| Screen mirroring | Your call | Cut as a built feature; menu bar shortcut to scrcpy, same Wi-Fi only |
| Swift appetite | Fine with Swift | Native Swift confirmed; the Go daemon fallback is dropped from the plan |
| Networks | Rarely the same one | Overlay added, then removed again once proximity was established as constant |
| Phone always within a metre | Yes | Largest change. Bluetooth LE becomes the resident link; no network, no third party |
| Private or public | Private now, phone may change | No hard-coded device IDs; clipboard degrades when the ADB grant is missing |

**Proximity is what collapsed the design.** The network answer first pushed Bridge toward a WireGuard overlay and a third party. Establishing that the phone is always within a metre removed that need entirely: at that distance the radios reach each other directly, which is exactly how AirDrop and Quick Share work and why neither needs a server. The result is simpler, cheaper, more private and slightly faster to build than the overlay version.

**First move.** Install Tailscale on both devices and prove a JSON line crosses from the phone on mobile data to the Mac on office Wi-Fi. That single experiment de-risks more of this plan than anything else, and it costs an evening.
