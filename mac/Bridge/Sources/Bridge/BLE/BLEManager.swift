@preconcurrency import CoreBluetooth
import Foundation
import os

enum ConnectionState: String, Sendable {
    case disconnected
    case scanning
    case connecting
    case connected
    case bluetoothOff
}

@MainActor
final class BLEManager: NSObject, ObservableObject {
    @Published var connectionState: ConnectionState = .disconnected
    @Published var connectedDeviceName: String?

    private var centralManager: CBCentralManager!
    private var discoveredPeripheral: CBPeripheral?
    private var psmValue: CBL2CAPPSM?
    private var l2capChannel: L2CAPChannel?

    private static let logger = Logger(
        subsystem: "com.bridge.mac",
        category: "BLEManager"
    )

    override init() {
        super.init()
        centralManager = CBCentralManager(delegate: self, queue: .main)
    }

    func startScanning() {
        guard centralManager.state == .poweredOn else {
            Self.logger.warning("Cannot scan: Bluetooth not powered on (state: \(self.centralManager.state.rawValue))")
            return
        }
        connectionState = .scanning
        Self.logger.info("Scanning for Bridge service...")
        centralManager.scanForPeripherals(
            withServices: [BridgeConstants.serviceUUID],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
        )
    }

    func disconnect() {
        if let peripheral = discoveredPeripheral {
            centralManager.cancelPeripheralConnection(peripheral)
        }
        l2capChannel?.close()
        l2capChannel = nil
        discoveredPeripheral = nil
        connectedDeviceName = nil
        connectionState = .disconnected
    }

    private func connectToPeripheral(_ peripheral: CBPeripheral) {
        centralManager.stopScan()
        if let old = discoveredPeripheral, old !== peripheral {
            centralManager.cancelPeripheralConnection(old)
        }
        discoveredPeripheral = peripheral
        peripheral.delegate = self
        connectionState = .connecting
        Self.logger.info("Connecting to \(peripheral.name ?? "unknown")...")
        centralManager.connect(peripheral, options: nil)
    }

    private func handleDisconnect() {
        l2capChannel?.close()
        l2capChannel = nil
        psmValue = nil
        connectedDeviceName = nil
        connectionState = .disconnected

        guard centralManager.state == .poweredOn else { return }

        if let peripheral = discoveredPeripheral {
            Self.logger.info("Disconnected. Issuing pending connect + scanning for reconnect...")
            centralManager.connect(peripheral, options: nil)
        }
        discoveredPeripheral = nil
        startScanning()
    }

    private func handleReceivedPacket(_ packet: BridgePacket) {
        let pingHandler = PingHandler()
        if pingHandler.handle(packet) {
            return
        }
        Self.logger.info("Unhandled packet type: \(packet.type)")
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
                connectionState = .bluetoothOff
                connectedDeviceName = nil
            case .unauthorized:
                Self.logger.error("Bluetooth unauthorized")
                connectionState = .bluetoothOff
            case .unsupported:
                Self.logger.error("Bluetooth unsupported on this device")
                connectionState = .bluetoothOff
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
                Self.logger.warning("Disconnected with error: \(error.localizedDescription)")
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
            Self.logger.info("Discovered Bridge service")
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
                if characteristic.uuid == BridgeConstants.identityCharacteristicUUID {
                    Self.logger.info("Reading identity characteristic...")
                    peripheral.readValue(for: characteristic)
                } else if characteristic.uuid == BridgeConstants.psmCharacteristicUUID {
                    Self.logger.info("Reading PSM characteristic...")
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
                    } else {
                        connectedDeviceName = identity
                    }
                }
            } else if characteristic.uuid == BridgeConstants.psmCharacteristicUUID {
                if let data = characteristic.value,
                   let psmString = String(data: data, encoding: .utf8),
                   let psmInt = UInt16(psmString) {
                    Self.logger.info("L2CAP PSM: \(psmInt)")
                    psmValue = CBL2CAPPSM(psmInt)
                    openL2CAPChannel(on: peripheral)
                } else {
                    Self.logger.error("Failed to parse PSM value")
                }
            }
        }
    }

    private func openL2CAPChannel(on peripheral: CBPeripheral) {
        guard let psm = psmValue else {
            Self.logger.error("No PSM available to open L2CAP channel")
            return
        }
        Self.logger.info("Opening L2CAP channel with PSM \(psm)...")
        peripheral.openL2CAPChannel(psm)
    }

    nonisolated func peripheral(
        _ peripheral: CBPeripheral,
        didOpen channel: CBL2CAPChannel?,
        error: Error?
    ) {
        MainActor.assumeIsolated {
            if let error {
                Self.logger.error("L2CAP channel open failed: \(error.localizedDescription)")
                return
            }
            guard let channel else {
                Self.logger.error("L2CAP channel is nil")
                return
            }
            Self.logger.info("L2CAP channel opened successfully")
            let wrapper = L2CAPChannel(channel: channel)
            wrapper.onPacketReceived = { [weak self] packet in
                Task { @MainActor in
                    self?.handleReceivedPacket(packet)
                }
            }
            l2capChannel = wrapper
            connectionState = .connected

            wrapper.send(BridgePacket.ping())
            Self.logger.info("Sent initial ping")

            wrapper.runThroughputTest()
        }
    }
}
