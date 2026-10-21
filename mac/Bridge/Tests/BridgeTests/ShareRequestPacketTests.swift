import XCTest
@testable import Bridge

final class ShareRequestPacketTests: XCTestCase {

    private static var testVectorsDir: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("protocol/test-vectors")
    }

    func testDeserializeShareRequestVectors() throws {
        let url = Self.testVectorsDir.appendingPathComponent("share-request.json")
        let data = try Data(contentsOf: url)
        let json = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        let valid = json["valid"] as! [[String: Any]]

        for vector in valid {
            let name = vector["name"] as! String
            let packetDict = vector["packet"] as! [String: Any]
            let expected = vector["expectedFields"] as! [String: Any]

            let packetData = try JSONSerialization.data(withJSONObject: packetDict)
            let packet = try BridgePacket.deserialize(from: packetData)

            XCTAssertEqual(packet.type, "bridge.share.request", "type for \(name)")
            XCTAssertEqual(packet.body["filename"] as? String, expected["filename"] as? String, "filename for \(name)")
            XCTAssertEqual(packet.payloadSize, (expected["payloadSize"] as? NSNumber)?.int64Value, "payloadSize for \(name)")
            XCTAssertEqual(packet.payloadPort, (expected["port"] as? NSNumber)?.intValue, "port for \(name)")
            XCTAssertTrue(packet.hasPayload, "hasPayload for \(name)")
        }
    }

    func testDeserializeNoPayloadVectors() throws {
        let url = Self.testVectorsDir.appendingPathComponent("share-request-no-payload.json")
        let data = try Data(contentsOf: url)
        let json = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        let valid = json["valid"] as! [[String: Any]]

        for vector in valid {
            let name = vector["name"] as! String
            let packetDict = vector["packet"] as! [String: Any]
            let packetData = try JSONSerialization.data(withJSONObject: packetDict)
            let packet = try BridgePacket.deserialize(from: packetData)

            XCTAssertEqual(packet.type, "bridge.share.request", "type for \(name)")
            XCTAssertEqual(packet.payloadSize, 0, "payloadSize for \(name)")
            XCTAssertNil(packet.payloadPort, "port absent for \(name)")
            XCTAssertFalse(packet.hasPayload, "no payload for \(name)")
        }
    }

    func testShareRequestFactoryWithPayload() throws {
        let packet = BridgePacket.shareRequest(filename: "a.png", fileSize: 204800, payloadSize: 204800, port: 40001)
        XCTAssertEqual(packet.type, "bridge.share.request")
        XCTAssertEqual(packet.body["filename"] as? String, "a.png")
        XCTAssertEqual(packet.payloadSize, 204800)
        XCTAssertEqual(packet.payloadPort, 40001)
        XCTAssertTrue(packet.hasPayload)
    }

    func testShareRequestFactoryMetadataOnly() throws {
        let packet = BridgePacket.shareRequest(filename: "empty.txt", fileSize: 0, payloadSize: 0, port: nil)
        XCTAssertEqual(packet.payloadSize, 0)
        XCTAssertNil(packet.payloadPort)
        XCTAssertFalse(packet.hasPayload)
    }

    func testShareRequestRoundTrip() throws {
        let original = BridgePacket.shareRequest(filename: "doc.pdf", fileSize: 512000, payloadSize: 512000, port: 51000)
        let serialized = try original.serialize()
        let rt = try BridgePacket.deserialize(from: serialized)
        XCTAssertEqual(rt.body["filename"] as? String, "doc.pdf")
        XCTAssertEqual(rt.payloadSize, 512000)
        XCTAssertEqual(rt.payloadPort, 51000)
    }
}
