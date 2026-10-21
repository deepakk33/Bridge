import XCTest
@testable import Bridge

final class ClipboardPacketTests: XCTestCase {

    private static var repoRoot: URL {
        let thisFile = URL(fileURLWithPath: #filePath)
        return thisFile
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
    }

    private static var testVectorsDir: URL {
        repoRoot.appendingPathComponent("protocol/test-vectors")
    }

    // MARK: - bridge.clipboard

    func testDeserializeClipboardVectors() throws {
        let url = Self.testVectorsDir.appendingPathComponent("clipboard.json")
        let data = try Data(contentsOf: url)
        let json = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        let valid = json["valid"] as! [[String: Any]]

        for vector in valid {
            let name = vector["name"] as! String
            let packetDict = vector["packet"] as! [String: Any]
            let expected = vector["expectedFields"] as! [String: Any]

            let packetData = try JSONSerialization.data(withJSONObject: packetDict)
            let packet = try BridgePacket.deserialize(from: packetData)

            XCTAssertEqual(packet.type, "bridge.clipboard", "Failed for \(name)")
            XCTAssertEqual(
                packet.body["content"] as? String,
                expected["content"] as? String,
                "content mismatch for \(name)"
            )
        }
    }

    func testClipboardFactory() throws {
        let packet = BridgePacket.clipboard(content: "hello world")
        XCTAssertEqual(packet.type, "bridge.clipboard")
        XCTAssertEqual(packet.body["content"] as? String, "hello world")
        XCTAssertTrue(packet.id > 0)
    }

    func testClipboardRoundTrip() throws {
        let original = BridgePacket.clipboard(content: "round trip test 🎉")
        let serialized = try original.serialize()
        let deserialized = try BridgePacket.deserialize(from: serialized)

        XCTAssertEqual(deserialized.type, "bridge.clipboard")
        XCTAssertEqual(deserialized.body["content"] as? String, "round trip test 🎉")
    }

    // MARK: - bridge.clipboard.connect

    func testDeserializeClipboardConnectVectors() throws {
        let url = Self.testVectorsDir.appendingPathComponent("clipboard-connect.json")
        let data = try Data(contentsOf: url)
        let json = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        let valid = json["valid"] as! [[String: Any]]

        for vector in valid {
            let name = vector["name"] as! String
            let packetDict = vector["packet"] as! [String: Any]
            let expected = vector["expectedFields"] as! [String: Any]

            let packetData = try JSONSerialization.data(withJSONObject: packetDict)
            let packet = try BridgePacket.deserialize(from: packetData)

            XCTAssertEqual(packet.type, "bridge.clipboard.connect", "Failed for \(name)")
            XCTAssertEqual(
                packet.body["content"] as? String,
                expected["content"] as? String,
                "content mismatch for \(name)"
            )
            let expectedTs = (expected["timestamp"] as? NSNumber)?.int64Value
            let actualTs = (packet.body["timestamp"] as? NSNumber)?.int64Value
            XCTAssertEqual(actualTs, expectedTs, "timestamp mismatch for \(name)")
        }
    }

    func testClipboardConnectFactory() throws {
        let packet = BridgePacket.clipboardConnect(content: "test", timestamp: 1697300000000)
        XCTAssertEqual(packet.type, "bridge.clipboard.connect")
        XCTAssertEqual(packet.body["content"] as? String, "test")
        XCTAssertEqual((packet.body["timestamp"] as? NSNumber)?.int64Value, 1697300000000)
    }

    func testClipboardConnectRoundTrip() throws {
        let original = BridgePacket.clipboardConnect(content: "reconnect text", timestamp: 1697299999500)
        let serialized = try original.serialize()
        let deserialized = try BridgePacket.deserialize(from: serialized)

        XCTAssertEqual(deserialized.type, "bridge.clipboard.connect")
        XCTAssertEqual(deserialized.body["content"] as? String, "reconnect text")
        let ts = (deserialized.body["timestamp"] as? NSNumber)?.int64Value
        XCTAssertEqual(ts, 1697299999500)
    }

    func testClipboardConnectZeroTimestamp() throws {
        let packet = BridgePacket.clipboardConnect(content: "unknown", timestamp: 0)
        XCTAssertEqual((packet.body["timestamp"] as? NSNumber)?.int64Value, 0)

        let serialized = try packet.serialize()
        let rt = try BridgePacket.deserialize(from: serialized)
        let ts = (rt.body["timestamp"] as? NSNumber)?.int64Value
        XCTAssertEqual(ts, 0)
    }
}
