@preconcurrency import CoreBluetooth

enum BridgeConstants {
    nonisolated(unsafe) static let serviceUUID = CBUUID(string: "A76E1A00-B5D4-43C8-82BD-0E9F8A7B6C5D")
    nonisolated(unsafe) static let identityCharacteristicUUID = CBUUID(string: "A76E1A01-B5D4-43C8-82BD-0E9F8A7B6C5D")
    nonisolated(unsafe) static let psmCharacteristicUUID = CBUUID(string: "A76E1A02-B5D4-43C8-82BD-0E9F8A7B6C5D")
}
