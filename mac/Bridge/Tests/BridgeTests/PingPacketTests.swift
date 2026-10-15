import XCTest
@testable import Bridge

final class PingPacketTests: XCTestCase {

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

    func testDeserializePingPacketVector() throws {
        let url = Self.testVectorsDir.appendingPathComponent("ping-packet.json")
        let data = try Data(contentsOf: url)
        let json = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        let validCases = json["valid"] as! [[String: Any]]

        for testCase in validCases {
            let name = testCase["name"] as! String
            let serializedString = testCase["serialized"] as! String
            let serializedData = serializedString.data(using: .utf8)!
            let packet = try BridgePacket.deserialize(from: serializedData)
            XCTAssertEqual(packet.type, "bridge.ping", "\(name) should be bridge.ping")
            XCTAssertTrue(packet.body.isEmpty, "\(name) should have empty body")
        }
    }

    func testPingRoundTrip() throws {
        let packet = BridgePacket.ping()
        let data = try packet.serialize()
        let deserialized = try BridgePacket.deserialize(from: data)
        XCTAssertEqual(deserialized.type, "bridge.ping")
        XCTAssertTrue(deserialized.body.isEmpty)
    }
}
