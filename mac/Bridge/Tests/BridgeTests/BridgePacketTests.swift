import XCTest
@testable import Bridge

final class BridgePacketTests: XCTestCase {

    // MARK: - Test vector paths

    /// Compute the repo root from this source file's location.
    private static var repoRoot: URL {
        // #filePath -> .../Bridge/mac/Bridge/Tests/BridgeTests/BridgePacketTests.swift
        // Repo root is 4 directories up from the file.
        let thisFile = URL(fileURLWithPath: #filePath)
        return thisFile
            .deletingLastPathComponent() // BridgeTests/
            .deletingLastPathComponent() // Tests/
            .deletingLastPathComponent() // Bridge/
            .deletingLastPathComponent() // mac/
            .deletingLastPathComponent() // Bridge (repo root)
    }

    private static var testVectorsDir: URL {
        repoRoot.appendingPathComponent("protocol/test-vectors")
    }

    // MARK: - Ping test vector

    func testDeserializePingVector() throws {
        let url = Self.testVectorsDir.appendingPathComponent("ping.json")
        let data = try Data(contentsOf: url)
        let json = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        let packetDict = json["packet"] as! [String: Any]

        // Serialize the packet dict back to JSON data to feed into deserialize
        let packetData = try JSONSerialization.data(withJSONObject: packetDict)
        let packet = try BridgePacket.deserialize(from: packetData)

        XCTAssertEqual(packet.id, 1_728_000_000_000)
        XCTAssertEqual(packet.type, "bridge.ping")
        XCTAssertTrue(packet.body.isEmpty)
    }

    func testDeserializePingSerialized() throws {
        let url = Self.testVectorsDir.appendingPathComponent("ping.json")
        let data = try Data(contentsOf: url)
        let json = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        let serializedString = json["serialized"] as! String
        let serializedData = serializedString.data(using: .utf8)!

        let packet = try BridgePacket.deserialize(from: serializedData)

        XCTAssertEqual(packet.id, 1_728_000_000_000)
        XCTAssertEqual(packet.type, "bridge.ping")
        XCTAssertTrue(packet.body.isEmpty)
    }

    // MARK: - Malformed test vectors

    func testMalformedVectors() throws {
        let url = Self.testVectorsDir.appendingPathComponent("malformed.json")
        let data = try Data(contentsOf: url)
        let json = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        let cases = json["cases"] as! [[String: Any]]

        for testCase in cases {
            let name = testCase["name"] as! String
            let input = testCase["input"] as! String
            let inputData = input.data(using: .utf8)!

            XCTAssertThrowsError(
                try BridgePacket.deserialize(from: inputData),
                "Expected \(name) to throw"
            )
        }
    }

    // MARK: - Round-trip

    func testRoundTrip() throws {
        let original = BridgePacket(id: 1_700_000_000_000, type: "bridge.test", body: ["key": "value"])
        let serialized = try original.serialize()
        let deserialized = try BridgePacket.deserialize(from: serialized)

        XCTAssertEqual(deserialized.id, original.id)
        XCTAssertEqual(deserialized.type, original.type)
        XCTAssertEqual(deserialized.body["key"] as? String, "value")
    }

    func testSerializeEndsWithNewline() throws {
        let packet = BridgePacket.ping()
        let data = try packet.serialize()
        XCTAssertEqual(data.last, 0x0A, "Serialized packet must end with newline")
    }

    // MARK: - Ping factory

    func testPingFactory() throws {
        let before = Int64(Date().timeIntervalSince1970 * 1000)
        let packet = BridgePacket.ping()
        let after = Int64(Date().timeIntervalSince1970 * 1000)

        XCTAssertEqual(packet.type, "bridge.ping")
        XCTAssertTrue(packet.body.isEmpty)
        XCTAssertGreaterThanOrEqual(packet.id, before)
        XCTAssertLessThanOrEqual(packet.id, after)
    }

    // MARK: - Serialization format

    func testSerializedMatchesVector() throws {
        let url = Self.testVectorsDir.appendingPathComponent("ping.json")
        let data = try Data(contentsOf: url)
        let json = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        let expectedSerialized = json["serialized"] as! String

        let packet = BridgePacket(id: 1_728_000_000_000, type: "bridge.ping", body: [:])
        let serialized = try packet.serialize()
        let serializedString = String(data: serialized, encoding: .utf8)!

        XCTAssertEqual(serializedString, expectedSerialized)
    }

    // MARK: - Edge cases

    func testDeserializeWithoutTrailingNewline() throws {
        let jsonString = "{\"id\":1728000000000,\"type\":\"bridge.ping\",\"body\":{}}"
        let data = jsonString.data(using: .utf8)!
        let packet = try BridgePacket.deserialize(from: data)
        XCTAssertEqual(packet.type, "bridge.ping")
    }

    func testEmptyDataThrows() {
        XCTAssertThrowsError(try BridgePacket.deserialize(from: Data()))
    }
}
