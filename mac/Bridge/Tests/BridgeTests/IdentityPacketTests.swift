import XCTest
@testable import Bridge

final class IdentityPacketTests: XCTestCase {

    private static var testVectorsDir: URL {
        let thisFile = URL(fileURLWithPath: #filePath)
        return thisFile
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("protocol/test-vectors")
    }

    func testDeserializeIdentityVector() throws {
        let url = Self.testVectorsDir.appendingPathComponent("identity-packet.json")
        let data = try Data(contentsOf: url)
        let json = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        let validCases = json["valid"] as! [[String: Any]]

        let macCase = validCases.first { ($0["name"] as? String) == "mac_identity" }!
        let packetDict = macCase["packet"] as! [String: Any]
        let packetData = try JSONSerialization.data(withJSONObject: packetDict)
        let packet = try BridgePacket.deserialize(from: packetData)

        XCTAssertEqual(packet.type, "bridge.identity")
        let body = packet.body
        XCTAssertEqual(body["deviceId"] as? String, "550e8400-e29b-41d4-a716-446655440000")
        XCTAssertEqual(body["deviceName"] as? String, "MacBook Air")
        XCTAssertEqual(body["deviceType"] as? String, "desktop")
        XCTAssertEqual(body["protocolVersion"] as? Int, 1)
        XCTAssertEqual(body["certificateFingerprint"] as? String,
                       "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2")
    }

    func testDeserializeIdentitySerialized() throws {
        let url = Self.testVectorsDir.appendingPathComponent("identity-packet.json")
        let data = try Data(contentsOf: url)
        let json = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        let validCases = json["valid"] as! [[String: Any]]

        let macCase = validCases.first { ($0["name"] as? String) == "mac_identity" }!
        guard let serializedString = macCase["serialized"] as? String else {
            return // only mac_identity has serialized form
        }
        let serializedData = serializedString.data(using: .utf8)!
        let packet = try BridgePacket.deserialize(from: serializedData)

        XCTAssertEqual(packet.type, "bridge.identity")
        XCTAssertEqual(packet.body["deviceId"] as? String, "550e8400-e29b-41d4-a716-446655440000")
    }

    func testIdentityWithUnknownFieldsParses() throws {
        let url = Self.testVectorsDir.appendingPathComponent("identity-packet.json")
        let data = try Data(contentsOf: url)
        let json = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        let validCases = json["valid"] as! [[String: Any]]

        let futureCase = validCases.first { ($0["name"] as? String) == "identity_with_unknown_fields" }!
        let packetDict = futureCase["packet"] as! [String: Any]
        let packetData = try JSONSerialization.data(withJSONObject: packetDict)
        let packet = try BridgePacket.deserialize(from: packetData)

        XCTAssertEqual(packet.type, "bridge.identity")
        let body = packet.body
        XCTAssertEqual(body["deviceType"] as? String, "tablet")
    }

    func testIdentityFactoryRoundTrip() throws {
        let packet = BridgePacket.identity(
            deviceId: "test-id",
            deviceName: "Test Mac",
            deviceType: "desktop",
            protocolVersion: 1,
            certificateFingerprint: "abcd1234",
            incomingCapabilities: ["bridge.ping"],
            outgoingCapabilities: ["bridge.ping"]
        )

        XCTAssertEqual(packet.type, "bridge.identity")
        let serialized = try packet.serialize()
        let deserialized = try BridgePacket.deserialize(from: serialized)
        XCTAssertEqual(deserialized.body["deviceId"] as? String, "test-id")
        XCTAssertEqual(deserialized.body["certificateFingerprint"] as? String, "abcd1234")
    }
}
