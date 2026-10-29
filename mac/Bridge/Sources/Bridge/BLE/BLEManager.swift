import Combine
@preconcurrency import CoreBluetooth
import Foundation
import os

@MainActor
final class BLEManager: NSObject, ObservableObject {
    @Published var linkState: LinkStateMachine
    @Published var currentLinkState: LinkState = .disconnected
    /// What the user is told. Derived from the transport facts below; every link
    /// indicator reads this, never `currentLinkState`.
    @Published private(set) var linkHealth: LinkHealth = .disconnected
    @Published private(set) var lastSeenText: String?
    @Published var connectedDeviceName: String?
    /// nil until the phone reports it this connection — the Mac never invents a count.
    @Published var mirroringState: MirroringState?

    let pairingManager = PairingManager()
    let pingWatchdog = PingWatchdog()
    let notificationManager = NotificationManager.shared
    let clipboardMonitor = ClipboardMonitor()
    let fileReceiver = FileReceiveController()

    private var centralManager: CBCentralManager!
    private var discoveredPeripheral: CBPeripheral?
    private var psmValue: CBL2CAPPSM?
    private var l2capChannel: L2CAPChannel?

    private var peerDeviceId: String?
    private var peerFingerprint: String?
    private var peripheralConnected = false
    private var isVerified = false
    private var lastDisconnect: DisconnectReason?
    private var healthTimer: Timer?
    private var loggedHealth: LinkHealth?
    /// Our own record that a scan was requested. `CBCentralManager.isScanning` reports this
    /// app's request, not the daemon's behaviour — it stayed true throughout the 2026-10-27
    /// failure, where another process's `stop scan` ended this app's scan silently.
    private var isScanning = false
    private var searchingSince: Date?
    private var scanWatchdog: Timer?
    private var deviceId: String
    private var pairingStateCancellable: AnyCancellable?

    private static let logger = Logger(subsystem: "com.bridge.mac", category: "BLEManager")

    override init() {
        linkState = LinkStateMachine()

        deviceId = UserDefaults.standard.string(forKey: "com.bridge.mac.deviceId")
            ?? {
                let id = UUID().uuidString.lowercased()
                UserDefaults.standard.set(id, forKey: "com.bridge.mac.deviceId")
                return id
            }()

        super.init()

        pairingStateCancellable = pairingManager.objectWillChange.sink { [weak self] in
            self?.objectWillChange.send()
            // objectWillChange fires before the trust store is updated, so re-read on the
            // next turn of the run loop.
            Task { @MainActor in self?.evaluateVerification() }
        }

        centralManager = CBCentralManager(delegate: self, queue: .main)

        pairingManager.onPairConfirmationPending = { [weak self] deviceName in
            self?.notificationManager.postPairConfirmationPending(deviceName: deviceName)
        }

        pairingManager.configure(localDeviceId: deviceId) { [weak self] packet in
            self?.sendPacket(packet)
        }
        notificationManager.configure { [weak self] packet in
            self?.sendPacket(packet) ?? false
        }

        Task {
            await clipboardMonitor.configure { [weak self] packet in
                self?.sendPacket(packet)
            }
            await clipboardMonitor.start()
        }

        healthTimer = Timer.scheduledTimer(withTimeInterval: Self.healthTick, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.refreshHealth() }
        }
    }

    /// How often the health is recomputed. Bounds the gap between a threshold being
    /// crossed and the user being told; without it the only recompute was the 30s
    /// watchdog poll against a 90s timeout, so a dead link read "Connected" for 2 minutes.
    private static let healthTick: TimeInterval = 10

    func startScanning() {
        // BridgeTests is hosted in Bridge.app, so a test run is a second instance. Scanning from
        // it would stop the real app's scan when the test host quits (BR-14).
        guard !SingleInstanceGuard.isUnderTest(ProcessInfo.processInfo.environment) else { return }
        guard centralManager.state == .poweredOn else {
            Self.logger.warning("Cannot scan: BT not powered on")
            return
        }
        isScanning = true
        // When the *search* began, not this attempt: a watchdog restart must not reset the
        // clock, or the state never ages out of .searching and "Out of range" is unreachable.
        if searchingSince == nil { searchingSince = Date() }
        transitionLink(to: .connecting)
        Self.logger.info("Scanning for Bridge service...")
        centralManager.scanForPeripherals(
            withServices: [BridgeConstants.serviceUUID],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
        )
        scheduleScanWatchdog()
    }

    /// Core Bluetooth never tells a central that its scan stopped, so the only way to notice
    /// is to time how long it has produced nothing.
    private func scheduleScanWatchdog() {
        scanWatchdog?.invalidate()
        scanWatchdog = Timer.scheduledTimer(
            withTimeInterval: LinkHealthEvaluator.scanWatchdogPeriod, repeats: false
        ) { [weak self] _ in
            Task { @MainActor in self?.restartStalledScan() }
        }
    }

    /// Stops the timer, but deliberately leaves `searchingSince` alone: connecting to a
    /// peripheral we just discovered is still part of the same search, and clearing it here
    /// made the panel flash "Out of range" for the length of the connect — observed on
    /// hardware 2026-10-28, 676ms between discovery and connection.
    private func stopScanWatchdog() {
        scanWatchdog?.invalidate()
        scanWatchdog = nil
        isScanning = false
    }

    private func restartStalledScan() {
        guard isScanning, centralManager.state == .poweredOn else { return }
        // Reconnecting a peripheral that is still connected resets the ping watchdog's
        // silence clock, which would turn a dead phone service back into "Verifying…".
        // Its silence is that watchdog's business; stay armed for when it does drop.
        guard !peripheralConnected else {
            scheduleScanWatchdog()
            return
        }
        Self.logger.info("Scan found nothing in \(Int(LinkHealthEvaluator.scanWatchdogPeriod))s — restarting")
        centralManager.stopScan()
        isScanning = false
        if connectToSystemHeldPeripheral() { return }
        startScanning()
    }

    /// A peripheral macOS already holds does not advertise to us, so scanning alone can never
    /// find it. This is Core Bluetooth's intended way to recover such a link.
    @discardableResult
    private func connectToSystemHeldPeripheral() -> Bool {
        let held = centralManager.retrieveConnectedPeripherals(
            withServices: [BridgeConstants.serviceUUID]
        )
        guard let peripheral = held.first else { return false }
        Self.logger.info("System already holds \(peripheral.name ?? "unnamed") — connecting without a scan")
        connectToPeripheral(peripheral)
        return true
    }

    func disconnect() {
        lastDisconnect = .local
        pingWatchdog.stop()
        stopScanWatchdog()
        searchingSince = nil
        if let peripheral = discoveredPeripheral {
            centralManager.cancelPeripheralConnection(peripheral)
        }
        l2capChannel?.close()
        l2capChannel = nil
        discoveredPeripheral = nil
        connectedDeviceName = nil
        mirroringState = nil
        peerDeviceId = nil
        peerFingerprint = nil
        peripheralConnected = false
        isVerified = false
        transitionLink(to: .disconnected)
    }

    @discardableResult
    func sendPacket(_ packet: BridgePacket) -> Bool {
        guard let channel = l2capChannel else {
            Self.logger.warning("sendPacket failed: no l2capChannel for \(packet.type)")
            return false
        }
        guard isVerified || Self.preVerificationPacketTypes.contains(packet.type) else {
            Self.logger.warning("sendPacket failed: peer not verified for \(packet.type)")
            return false
        }
        return channel.send(packet)
    }

    /// The only packets worth sending to a peer we have not yet verified: the two that
    /// make verification happen, plus the heartbeat — a ping carries no data, and without
    /// it the peer's own watchdog tears the link down while a pair prompt is unanswered.
    private static let preVerificationPacketTypes: Set<String> = [
        "bridge.identity", "bridge.pair", "bridge.ping"
    ]

    private func transitionLink(to newState: LinkState) {
        linkState.transition(to: newState)
        currentLinkState = linkState.state
        refreshHealth()
    }

    private func refreshHealth() {
        let lastSeen = pingWatchdog.lastSeen
        switch centralManager?.state {
        case .some(.unknown), .some(.resetting), .none:
            // Core Bluetooth has not reported yet. "Bluetooth off" would be a guess, and a
            // red dot with settings guidance is the worst possible guess to show.
            linkHealth = .disconnected
        case .some(let state):
            linkHealth = LinkHealthEvaluator.evaluate(
                lastSeen: lastSeen,
                bleConnected: peripheralConnected,
                bluetoothOn: state == .poweredOn,
                isVerified: isVerified,
                lastDisconnect: lastDisconnect,
                searchingSince: searchingSince
            )
        }
        lastSeenText = (linkHealth == .connected || lastSeen == nil)
            ? nil
            : LinkHealthEvaluator.lastSeenText(lastSeen: lastSeen)

        if linkHealth != loggedHealth {
            loggedHealth = linkHealth
            Self.logger.info("Link health: \(String(describing: self.linkHealth), privacy: .public)")
        }
    }

    /// The peer is verified when it has told us who it is and the trust store agrees.
    /// Only then does the Mac claim a connected link, as Android already does.
    private func evaluateVerification() {
        let verified: Bool
        if let id = peerDeviceId, let fingerprint = peerFingerprint {
            verified = TrustStore.shared.isTrusted(deviceId: id, fingerprint: fingerprint)
        } else {
            verified = false
        }
        guard verified != isVerified else { return }
        isVerified = verified

        if verified {
            transitionLink(to: .connected)
            Task { @MainActor in
                sendPacket(await clipboardMonitor.makeConnectPacket())
            }
        } else if l2capChannel != nil {
            transitionLink(to: .connecting)
        } else {
            refreshHealth()
        }
    }

    // MARK: - Connection flow

    private func connectToPeripheral(_ peripheral: CBPeripheral) {
        centralManager.stopScan()
        stopScanWatchdog()
        if let old = discoveredPeripheral, old !== peripheral {
            centralManager.cancelPeripheralConnection(old)
        }
        discoveredPeripheral = peripheral
        peripheral.delegate = self
        transitionLink(to: .connecting)
        Self.logger.info("Connecting to \(peripheral.name ?? "unknown")...")
        centralManager.connect(peripheral, options: nil)
    }

    private func handleDisconnect() {
        pingWatchdog.stop()
        l2capChannel?.close()
        l2capChannel = nil
        psmValue = nil
        connectedDeviceName = nil
        mirroringState = nil
        peerDeviceId = nil
        peerFingerprint = nil
        peripheralConnected = false
        isVerified = false
        pairingManager.reset()

        guard centralManager.state == .poweredOn else { return }

        transitionLink(to: .connecting)

        if let peripheral = discoveredPeripheral {
            Self.logger.info("Disconnected. Pending reconnect...")
            centralManager.connect(peripheral, options: nil)
        }
        discoveredPeripheral = nil
        startScanning()
    }

    private func handleReceivedPacket(_ packet: BridgePacket) {
        pingWatchdog.packetReceived()
        refreshHealth()

        if peerDeviceId != nil, peerFingerprint != nil, !isVerified,
           !Self.preVerificationPacketTypes.contains(packet.type) {
            Self.logger.warning("Dropping packet from untrusted device: \(packet.type)")
            return
        }

        switch packet.type {
        case "bridge.ping":
            PingHandler().handle(packet)
        case "bridge.identity":
            handleIdentityPacket(packet)
        case "bridge.pair":
            pairingManager.handlePairPacket(packet)
        case "bridge.notification":
            notificationManager.handleNotificationPacket(packet)
        case "bridge.battery":
            notificationManager.handleBatteryPacket(packet)
        case "bridge.clipboard":
            Task { await clipboardMonitor.handleClipboardPacket(packet) }
        case "bridge.clipboard.connect":
            Task { await clipboardMonitor.handleClipboardConnectPacket(packet) }
        case "bridge.share.request":
            handleShareRequest(packet)
        case "bridge.notification.request":
            notificationManager.handleNotificationRequestPacket(packet)
        case "bridge.notification.filter":
            handleNotificationFilterPacket(packet)
        case "bridge.notification.reply":
            Self.logger.info("Received \(packet.type) — phone-bound, nothing to do here")
        default:
            Self.logger.info("Unhandled packet type: \(packet.type)")
        }
    }

    private func handleNotificationFilterPacket(_ packet: BridgePacket) {
        guard let allowed = packet.body["allowed"] as? Int,
              let muted = packet.body["muted"] as? Bool else { return }
        let seen = packet.body["seen"] as? Int ?? 0
        mirroringState = MirroringState(allowed: allowed, seen: seen, muted: muted)
    }

    private func handleShareRequest(_ packet: BridgePacket) {
        let filename = packet.body["filename"] as? String ?? "file"
        Self.logger.info("Incoming file '\(filename)' payloadSize=\(packet.payloadSize) port=\(packet.payloadPort ?? -1)")
        NotificationCenter.default.post(
            name: .bridgeShareRequestReceived,
            object: nil,
            userInfo: [
                "filename": filename,
                "payloadSize": packet.payloadSize,
                "port": packet.payloadPort ?? -1
            ]
        )
        // Fetch the payload over the out-of-band channel (mDNS + TCP) and save it.
        fileReceiver.receive(
            filename: filename,
            payloadSize: Int(packet.payloadSize),
            port: packet.payloadPort,
            host: packet.payloadHost
        )
    }

    private func handleIdentityPacket(_ packet: BridgePacket) {
        guard let id = packet.body["deviceId"] as? String,
              let name = packet.body["deviceName"] as? String,
              let fp = packet.body["certificateFingerprint"] as? String else {
            Self.logger.warning("Invalid identity packet")
            return
        }
        peerDeviceId = id
        peerFingerprint = fp
        connectedDeviceName = name
        pairingManager.setPeerIdentity(deviceId: id, deviceName: name, fingerprint: fp)
        Self.logger.info("Peer identity: \(name) [\(id)]")
        evaluateVerification()
    }

    private func sendIdentity() async {
        do {
            let fingerprint = try await CertificateManager.shared.getFingerprint()
            let capabilities = [
                "bridge.ping", "bridge.pair",
                "bridge.notification", "bridge.notification.reply",
                "bridge.notification.request", "bridge.notification.filter",
                "bridge.battery",
                "bridge.clipboard", "bridge.clipboard.connect"
            ]
            let packet = BridgePacket.identity(
                deviceId: deviceId,
                deviceName: Host.current().localizedName ?? "Mac",
                deviceType: "desktop",
                protocolVersion: 1,
                certificateFingerprint: fingerprint,
                incomingCapabilities: capabilities,
                outgoingCapabilities: capabilities
            )
            sendPacket(packet)
            Self.logger.info("Sent identity packet")
        } catch {
            Self.logger.error("Failed to send identity: \(error.localizedDescription)")
        }
    }

    private func startWatchdog() {
        pingWatchdog.start(
            sendPing: { [weak self] in
                self?.sendPacket(BridgePacket.ping())
            },
            onTimeout: { [weak self] in
                Self.logger.warning("Watchdog timeout — closing channel")
                self?.l2capChannel?.close()
                self?.l2capChannel = nil
                self?.isVerified = false
                self?.transitionLink(to: .connecting)
                self?.linkState.recordLastSeen()
                if let peripheral = self?.discoveredPeripheral {
                    self?.centralManager.connect(peripheral, options: nil)
                }
                self?.startScanning()
            }
        )
    }
}

// MARK: - Disconnect reason

extension BLEManager {
    /// A force-stopped Android app stops advertising exactly as a departed phone does,
    /// so the code Core Bluetooth attaches to the disconnect is the only thing that
    /// tells "the service died" apart from "the phone walked away".
    static func disconnectReason(from error: Error?) -> DisconnectReason {
        guard let error else { return .unknown }
        let nsError = error as NSError
        guard nsError.domain == CBErrorDomain else { return .unknown }
        switch CBError.Code(rawValue: nsError.code) {
        case .connectionTimeout: return .supervisionTimeout
        case .peripheralDisconnected: return .peerTerminated
        default: return .unknown
        }
    }
}

// MARK: - CBCentralManagerDelegate

extension BLEManager: CBCentralManagerDelegate {
    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        MainActor.assumeIsolated {
            switch central.state {
            case .poweredOn:
                Self.logger.info("Bluetooth powered on")
                if !connectToSystemHeldPeripheral() { startScanning() }
            case .poweredOff:
                Self.logger.warning("Bluetooth powered off")
                stopScanWatchdog()
                searchingSince = nil
                transitionLink(to: .bluetoothOff)
                connectedDeviceName = nil
                mirroringState = nil
            case .unauthorized:
                Self.logger.error("Bluetooth unauthorized")
                stopScanWatchdog()
                searchingSince = nil
                transitionLink(to: .bluetoothOff)
            case .unsupported:
                Self.logger.error("Bluetooth unsupported")
                stopScanWatchdog()
                searchingSince = nil
                transitionLink(to: .bluetoothOff)
            default:
                Self.logger.info("Bluetooth state: \(central.state.rawValue)")
            }
        }
    }

    nonisolated func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        MainActor.assumeIsolated {
            Self.logger.info("Discovered \(peripheral.name ?? "unnamed") RSSI: \(RSSI)")
            connectToPeripheral(peripheral)
        }
    }

    nonisolated func centralManager(
        _ central: CBCentralManager,
        didConnect peripheral: CBPeripheral
    ) {
        MainActor.assumeIsolated {
            Self.logger.info("Connected to \(peripheral.name ?? "unnamed")")
            peripheralConnected = true
            lastDisconnect = nil
            searchingSince = nil
            // Otherwise the previous session's gap is measured against this one and a
            // fresh reconnect reads as stale before the first packet lands.
            pingWatchdog.resetClock()
            connectedDeviceName = peripheral.name
            refreshHealth()
            peripheral.discoverServices([BridgeConstants.serviceUUID])
        }
    }

    nonisolated func centralManager(
        _ central: CBCentralManager,
        didFailToConnect peripheral: CBPeripheral,
        error: Error?
    ) {
        MainActor.assumeIsolated {
            Self.logger.error("Failed to connect: \(error?.localizedDescription ?? "unknown")")
            lastDisconnect = Self.disconnectReason(from: error)
            handleDisconnect()
        }
    }

    nonisolated func centralManager(
        _ central: CBCentralManager,
        didDisconnectPeripheral peripheral: CBPeripheral,
        error: Error?
    ) {
        MainActor.assumeIsolated {
            if let error {
                Self.logger.warning("Disconnected: \(error.localizedDescription)")
            } else {
                Self.logger.info("Disconnected cleanly")
            }
            if lastDisconnect != .local {
                lastDisconnect = Self.disconnectReason(from: error)
            }
            Self.logger.info("Disconnect reason: \(String(describing: self.lastDisconnect), privacy: .public)")
            handleDisconnect()
        }
    }
}

// MARK: - CBPeripheralDelegate

extension BLEManager: CBPeripheralDelegate {
    nonisolated func peripheral(
        _ peripheral: CBPeripheral,
        didDiscoverServices error: Error?
    ) {
        MainActor.assumeIsolated {
            if let error {
                Self.logger.error("Service discovery failed: \(error.localizedDescription)")
                abandon(peripheral)
                return
            }
            guard let service = peripheral.services?.first(where: {
                $0.uuid == BridgeConstants.serviceUUID
            }) else {
                // Reachable when macOS handed us a peripheral it still holds but whose Bridge
                // service is gone. Without dropping it we would sit on "Verifying…" forever,
                // because connecting cancelled the scan watchdog.
                Self.logger.error("Bridge service not found — dropping the peripheral and scanning again")
                abandon(peripheral)
                return
            }
            peripheral.discoverCharacteristics(
                [BridgeConstants.identityCharacteristicUUID, BridgeConstants.psmCharacteristicUUID],
                for: service
            )
        }
    }

    nonisolated func peripheral(
        _ peripheral: CBPeripheral,
        didDiscoverCharacteristicsFor service: CBService,
        error: Error?
    ) {
        MainActor.assumeIsolated {
            if let error {
                Self.logger.error("Characteristic discovery failed: \(error.localizedDescription)")
                return
            }
            guard let characteristics = service.characteristics else { return }
            for characteristic in characteristics {
                if characteristic.uuid == BridgeConstants.identityCharacteristicUUID ||
                   characteristic.uuid == BridgeConstants.psmCharacteristicUUID {
                    peripheral.readValue(for: characteristic)
                }
            }
        }
    }

    nonisolated func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateValueFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        MainActor.assumeIsolated {
            if let error {
                Self.logger.error("Read characteristic failed: \(error.localizedDescription)")
                return
            }

            if characteristic.uuid == BridgeConstants.identityCharacteristicUUID {
                if let data = characteristic.value, let identity = String(data: data, encoding: .utf8) {
                    Self.logger.info("Device identity: \(identity)")
                    if let jsonData = identity.data(using: .utf8),
                       let json = try? JSONSerialization.jsonObject(with: jsonData) as? [String: Any],
                       let deviceName = json["deviceName"] as? String {
                        connectedDeviceName = deviceName
                    }
                }
            } else if characteristic.uuid == BridgeConstants.psmCharacteristicUUID {
                if let data = characteristic.value,
                   let psmString = String(data: data, encoding: .utf8),
                   let psmInt = UInt16(psmString) {
                    Self.logger.info("L2CAP PSM: \(psmInt)")
                    psmValue = CBL2CAPPSM(psmInt)
                    openL2CAPChannel(on: peripheral)
                }
            }
        }
    }

    /// Give up on a peripheral we cannot use and go back to looking. Without this, a
    /// connection that never yields the Bridge service is a dead end: it cancelled the scan
    /// and the scan watchdog when it connected.
    private func abandon(_ peripheral: CBPeripheral) {
        centralManager.cancelPeripheralConnection(peripheral)
        if discoveredPeripheral === peripheral { discoveredPeripheral = nil }
        peripheralConnected = false
        startScanning()
    }

    private func openL2CAPChannel(on peripheral: CBPeripheral) {
        guard let psm = psmValue else { return }
        Self.logger.info("Opening L2CAP channel PSM \(psm)...")
        peripheral.openL2CAPChannel(psm)
    }

    nonisolated func peripheral(
        _ peripheral: CBPeripheral,
        didOpen channel: CBL2CAPChannel?,
        error: Error?
    ) {
        MainActor.assumeIsolated {
            if let error {
                Self.logger.error("L2CAP open failed: \(error.localizedDescription)")
                return
            }
            guard let channel else {
                Self.logger.error("L2CAP channel nil")
                return
            }
            Self.logger.info("L2CAP channel opened")
            let wrapper = L2CAPChannel(channel: channel)
            wrapper.onPacketReceived = { [weak self] packet in
                Task { @MainActor in
                    self?.handleReceivedPacket(packet)
                }
            }
            l2capChannel = wrapper
            // An open socket is not a working link — the peer is unverified until its
            // identity arrives and the trust store agrees. Android has always said this.
            transitionLink(to: .connecting)

            Task {
                await sendIdentity()
                startWatchdog()
            }
        }
    }
}
