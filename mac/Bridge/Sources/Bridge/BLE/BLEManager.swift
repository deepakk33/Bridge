import Combine
@preconcurrency import CoreBluetooth
import Foundation
import os

@MainActor
final class BLEManager: NSObject, ObservableObject {
    @Published var linkState: LinkStateMachine
    @Published var currentLinkState: LinkState = .disconnected
    @Published var connectedDeviceName: String?

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
    }

    func startScanning() {
        guard centralManager.state == .poweredOn else {
            Self.logger.warning("Cannot scan: BT not powered on")
            return
        }
        transitionLink(to: .connecting)
        Self.logger.info("Scanning for Bridge service...")
        centralManager.scanForPeripherals(
            withServices: [BridgeConstants.serviceUUID],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
        )
    }

    func disconnect() {
        pingWatchdog.stop()
        if let peripheral = discoveredPeripheral {
            centralManager.cancelPeripheralConnection(peripheral)
        }
        l2capChannel?.close()
        l2capChannel = nil
        discoveredPeripheral = nil
        connectedDeviceName = nil
        peerDeviceId = nil
        peerFingerprint = nil
        transitionLink(to: .disconnected)
    }

    @discardableResult
    func sendPacket(_ packet: BridgePacket) -> Bool {
        guard let channel = l2capChannel else {
            Self.logger.warning("sendPacket failed: no l2capChannel for \(packet.type)")
            return false
        }
        channel.send(packet)
        return true
    }

    private func transitionLink(to newState: LinkState) {
        linkState.transition(to: newState)
        currentLinkState = linkState.state
    }

    // MARK: - Connection flow

    private func connectToPeripheral(_ peripheral: CBPeripheral) {
        centralManager.stopScan()
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
        peerDeviceId = nil
        peerFingerprint = nil
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

        if let peerId = peerDeviceId, let peerFp = peerFingerprint {
            let isPaired = TrustStore.shared.isTrusted(deviceId: peerId, fingerprint: peerFp)
            if !isPaired && packet.type != "bridge.identity" && packet.type != "bridge.pair" {
                Self.logger.warning("Dropping packet from untrusted device: \(packet.type)")
                return
            }
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
        case "bridge.notification.reply", "bridge.notification.request":
            Self.logger.info("Received \(packet.type) — not applicable on Mac side")
        default:
            Self.logger.info("Unhandled packet type: \(packet.type)")
        }
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
    }

    private func sendIdentity() async {
        do {
            let fingerprint = try await CertificateManager.shared.getFingerprint()
            let capabilities = [
                "bridge.ping", "bridge.pair",
                "bridge.notification", "bridge.notification.reply",
                "bridge.notification.request", "bridge.battery",
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

// MARK: - CBCentralManagerDelegate

extension BLEManager: CBCentralManagerDelegate {
    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        MainActor.assumeIsolated {
            switch central.state {
            case .poweredOn:
                Self.logger.info("Bluetooth powered on")
                startScanning()
            case .poweredOff:
                Self.logger.warning("Bluetooth powered off")
                transitionLink(to: .bluetoothOff)
                connectedDeviceName = nil
            case .unauthorized:
                Self.logger.error("Bluetooth unauthorized")
                transitionLink(to: .bluetoothOff)
            case .unsupported:
                Self.logger.error("Bluetooth unsupported")
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
            connectedDeviceName = peripheral.name
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
                return
            }
            guard let service = peripheral.services?.first(where: {
                $0.uuid == BridgeConstants.serviceUUID
            }) else {
                Self.logger.error("Bridge service not found")
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
            transitionLink(to: .connected)

            Task {
                await sendIdentity()
                startWatchdog()
                let connectPacket = await clipboardMonitor.makeConnectPacket()
                sendPacket(connectPacket)
            }
        }
    }
}
