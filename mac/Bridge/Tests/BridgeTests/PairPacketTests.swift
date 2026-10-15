import XCTest
@testable import Bridge

final class PairPacketTests: XCTestCase {

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

    func testDeserializePairRequest() throws {
        let url = Self.testVectorsDir.appendingPathComponent("pair-request.json")
        let data = try Data(contentsOf: url)
        let json = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        let validCases = json["valid"] as! [[String: Any]]

        let requestCase = validCases.first { ($0["name"] as? String) == "pair_request" }!
        let serializedString = requestCase["serialized"] as! String
        let serializedData = serializedString.data(using: .utf8)!
        let packet = try BridgePacket.deserialize(from: serializedData)

        XCTAssertEqual(packet.type, "bridge.pair")
        XCTAssertEqual(packet.body["pair"] as? Bool, true)
    }

    func testDeserializePairReject() throws {
        let url = Self.testVectorsDir.appendingPathComponent("pair-request.json")
        let data = try Data(contentsOf: url)
        let json = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        let validCases = json["valid"] as! [[String: Any]]

        let rejectCase = validCases.first { ($0["name"] as? String) == "pair_reject" }!
        let serializedString = rejectCase["serialized"] as! String
        let serializedData = serializedString.data(using: .utf8)!
        let packet = try BridgePacket.deserialize(from: serializedData)

        XCTAssertEqual(packet.type, "bridge.pair")
        XCTAssertEqual(packet.body["pair"] as? Bool, false)
    }

    func testPairRequestFactory() throws {
        let packet = BridgePacket.pairRequest()
        XCTAssertEqual(packet.type, "bridge.pair")
        XCTAssertEqual(packet.body["pair"] as? Bool, true)

        let serialized = try packet.serialize()
        let deserialized = try BridgePacket.deserialize(from: serialized)
        XCTAssertEqual(deserialized.body["pair"] as? Bool, true)
    }

    func testPairRejectFactory() throws {
        let packet = BridgePacket.pairReject()
        XCTAssertEqual(packet.type, "bridge.pair")
        XCTAssertEqual(packet.body["pair"] as? Bool, false)
    }

    func testPairAcceptFactory() throws {
        let packet = BridgePacket.pairAccept()
        XCTAssertEqual(packet.type, "bridge.pair")
        XCTAssertEqual(packet.body["pair"] as? Bool, true)
    }

    func testAllPairVectorsDeserialize() throws {
        let url = Self.testVectorsDir.appendingPathComponent("pair-request.json")
        let data = try Data(contentsOf: url)
        let json = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        let validCases = json["valid"] as! [[String: Any]]

        for testCase in validCases {
            let name = testCase["name"] as! String
            let serializedString = testCase["serialized"] as! String
            let serializedData = serializedString.data(using: .utf8)!
            XCTAssertNoThrow(
                try BridgePacket.deserialize(from: serializedData),
                "Expected \(name) to parse"
            )
        }
    }
}
