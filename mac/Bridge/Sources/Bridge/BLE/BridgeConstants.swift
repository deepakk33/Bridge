@preconcurrency import CoreBluetooth

enum BridgeConstants {
    nonisolated(unsafe) static let serviceUUID = CBUUID(string: "A76E1A00-B5D4-43C8-82BD-0E9F8A7B6C5D")
    nonisolated(unsafe) static let identityCharacteristicUUID = CBUUID(string: "A76E1A01-B5D4-43C8-82BD-0E9F8A7B6C5D")
    nonisolated(unsafe) static let psmCharacteristicUUID = CBUUID(string: "A76E1A02-B5D4-43C8-82BD-0E9F8A7B6C5D")

    /// Bluetooth Classic RFCOMM service UUID for Tier 2 file transfer.
    static let fileTransferUUIDString = "A76E1A03-B5D4-43C8-82BD-0E9F8A7B6C5D"
    static let fileTransferServiceName = "BridgeFileTransfer"

    /// mDNS service type for Tier 3 Wi-Fi LAN file transfer.
    static let fileTransferBonjourType = "_bridge._tcp"
}
