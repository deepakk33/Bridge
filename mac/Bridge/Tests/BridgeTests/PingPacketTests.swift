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

    func testReplyVectorCarriesReplyTo() throws {
        let url = Self.testVectorsDir.appendingPathComponent("ping-packet.json")
        let data = try Data(contentsOf: url)
        let json = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        let replyCases = json["reply"] as! [[String: Any]]
        XCTAssertFalse(replyCases.isEmpty, "the reply vectors should not be empty")

        for testCase in replyCases {
            let name = testCase["name"] as! String
            let expectedReplyTo = (testCase["replyTo"] as! NSNumber).int64Value
            let serializedData = (testCase["serialized"] as! String).data(using: .utf8)!
            let packet = try BridgePacket.deserialize(from: serializedData)
            XCTAssertEqual(packet.type, "bridge.ping", "\(name) should be bridge.ping")
            XCTAssertEqual(
                (packet.body["replyTo"] as? NSNumber)?.int64Value, expectedReplyTo,
                "\(name) should carry replyTo"
            )
        }
    }

    func testHandlerAnswersAPlainPing() throws {
        let ping = BridgePacket(id: 1_728_000_020_000, type: "bridge.ping", body: [:])
        let reply = try XCTUnwrap(PingHandler().handle(ping), "a plain ping must be answered")

        XCTAssertEqual(reply.type, "bridge.ping")
        XCTAssertEqual((reply.body["replyTo"] as? NSNumber)?.int64Value, ping.id)
    }

    /// The loop guard. Once both platforms answer, a handler that answers a reply
    /// ping-pongs at line rate for the life of the connection.
    func testHandlerDoesNotAnswerAReply() {
        let reply = BridgePacket(
            id: 1_728_000_020_100,
            type: "bridge.ping",
            body: ["replyTo": 1_728_000_020_000]
        )
        XCTAssertNil(PingHandler().handle(reply), "a reply must be terminal")
    }

    func testHandlerIgnoresOtherPacketTypes() {
        let other = BridgePacket(id: 1, type: "bridge.clipboard", body: [:])
        XCTAssertNil(PingHandler().handle(other))
    }

    func testReplyRoundTrip() throws {
        let reply = BridgePacket.pingReply(to: 1_728_000_020_000)
        let deserialized = try BridgePacket.deserialize(from: reply.serialize())

        XCTAssertEqual(deserialized.type, "bridge.ping")
        XCTAssertEqual(
            (deserialized.body["replyTo"] as? NSNumber)?.int64Value, 1_728_000_020_000
        )
    }

    func testPingRoundTrip() throws {
        let packet = BridgePacket.ping()
        let data = try packet.serialize()
        let deserialized = try BridgePacket.deserialize(from: data)
        XCTAssertEqual(deserialized.type, "bridge.ping")
        XCTAssertTrue(deserialized.body.isEmpty)
    }
}
