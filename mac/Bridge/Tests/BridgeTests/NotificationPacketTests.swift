import XCTest
@testable import Bridge

final class NotificationPacketTests: XCTestCase {

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

    // MARK: - bridge.notification

    func testDeserializeNotificationVectors() throws {
        let url = Self.testVectorsDir.appendingPathComponent("notification.json")
        let data = try Data(contentsOf: url)
        let json = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        let valid = json["valid"] as! [[String: Any]]

        for vector in valid {
            let name = vector["name"] as! String
            let packetDict = vector["packet"] as! [String: Any]
            let expected = vector["expectedFields"] as! [String: Any]

            let packetData = try JSONSerialization.data(withJSONObject: packetDict)
            let packet = try BridgePacket.deserialize(from: packetData)

            XCTAssertEqual(packet.type, "bridge.notification", "Failed for \(name)")
            XCTAssertEqual(packet.body["id"] as? String, expected["id"] as? String, "id mismatch for \(name)")
            XCTAssertEqual(packet.body["appName"] as? String, expected["appName"] as? String, "appName mismatch for \(name)")
            XCTAssertEqual(packet.body["title"] as? String, expected["title"] as? String, "title mismatch for \(name)")
            XCTAssertEqual(packet.body["text"] as? String, expected["text"] as? String, "text mismatch for \(name)")
        }
    }

    func testNotificationFactoryWithReply() {
        let packet = BridgePacket.notification(
            id: "com.test|0|null|1",
            appName: "TestApp",
            title: "Hello",
            text: "World",
            actions: ["Reply"],
            requestReplyId: "test-uuid"
        )

        XCTAssertEqual(packet.type, "bridge.notification")
        XCTAssertEqual(packet.body["id"] as? String, "com.test|0|null|1")
        XCTAssertEqual(packet.body["appName"] as? String, "TestApp")
        XCTAssertEqual(packet.body["requestReplyId"] as? String, "test-uuid")
    }

    func testNotificationFactoryWithoutReply() {
        let packet = BridgePacket.notification(
            id: "com.test|0|null|2",
            appName: "Gmail",
            title: "Email",
            text: "Hello",
            actions: [],
            requestReplyId: nil
        )

        XCTAssertEqual(packet.type, "bridge.notification")
        XCTAssertTrue(packet.body["requestReplyId"] is NSNull)
    }

    func testNotificationRoundTrip() throws {
        let original = BridgePacket.notification(
            id: "com.test|0|null|3",
            appName: "Slack",
            title: "Message",
            text: "Hey there",
            actions: ["Reply", "Mute"],
            requestReplyId: "abc-123"
        )

        let serialized = try original.serialize()
        let deserialized = try BridgePacket.deserialize(from: serialized)

        XCTAssertEqual(deserialized.type, "bridge.notification")
        XCTAssertEqual(deserialized.body["id"] as? String, "com.test|0|null|3")
        XCTAssertEqual(deserialized.body["requestReplyId"] as? String, "abc-123")
    }

    // MARK: - bridge.notification.reply

    func testDeserializeNotificationReplyVectors() throws {
        let url = Self.testVectorsDir.appendingPathComponent("notification-reply.json")
        let data = try Data(contentsOf: url)
        let json = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        let valid = json["valid"] as! [[String: Any]]

        for vector in valid {
            let name = vector["name"] as! String
            let packetDict = vector["packet"] as! [String: Any]
            let expected = vector["expectedFields"] as! [String: Any]

            let packetData = try JSONSerialization.data(withJSONObject: packetDict)
            let packet = try BridgePacket.deserialize(from: packetData)

            XCTAssertEqual(packet.type, "bridge.notification.reply", "Failed for \(name)")
            XCTAssertEqual(
                packet.body["requestReplyId"] as? String,
                expected["requestReplyId"] as? String,
                "requestReplyId mismatch for \(name)"
            )
            XCTAssertEqual(
                packet.body["message"] as? String,
                expected["message"] as? String,
                "message mismatch for \(name)"
            )
        }
    }

    func testNotificationReplyFactory() throws {
        let packet = BridgePacket.notificationReply(requestReplyId: "uuid-1", message: "Yes!")
        XCTAssertEqual(packet.type, "bridge.notification.reply")
        XCTAssertEqual(packet.body["requestReplyId"] as? String, "uuid-1")
        XCTAssertEqual(packet.body["message"] as? String, "Yes!")

        let data = try packet.serialize()
        let rt = try BridgePacket.deserialize(from: data)
        XCTAssertEqual(rt.body["message"] as? String, "Yes!")
    }

    // MARK: - bridge.notification.request

    func testDeserializeNotificationRequestVectors() throws {
        let url = Self.testVectorsDir.appendingPathComponent("notification-request.json")
        let data = try Data(contentsOf: url)
        let json = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        let valid = json["valid"] as! [[String: Any]]

        for vector in valid {
            let name = vector["name"] as! String
            let packetDict = vector["packet"] as! [String: Any]
            let expected = vector["expectedFields"] as! [String: Any]

            let packetData = try JSONSerialization.data(withJSONObject: packetDict)
            let packet = try BridgePacket.deserialize(from: packetData)

            XCTAssertEqual(packet.type, "bridge.notification.request", "Failed for \(name)")
            XCTAssertEqual(
                packet.body["cancel"] as? String,
                expected["cancel"] as? String,
                "cancel mismatch for \(name)"
            )
        }
    }

    func testNotificationRequestFactory() throws {
        let packet = BridgePacket.notificationRequest(cancel: "com.test|0|null|99")
        XCTAssertEqual(packet.type, "bridge.notification.request")
        XCTAssertEqual(packet.body["cancel"] as? String, "com.test|0|null|99")
    }

    // MARK: - bridge.battery

    func testDeserializeBatteryVectors() throws {
        let url = Self.testVectorsDir.appendingPathComponent("battery.json")
        let data = try Data(contentsOf: url)
        let json = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        let valid = json["valid"] as! [[String: Any]]

        for vector in valid {
            let name = vector["name"] as! String
            let packetDict = vector["packet"] as! [String: Any]
            let expected = vector["expectedFields"] as! [String: Any]

            let packetData = try JSONSerialization.data(withJSONObject: packetDict)
            let packet = try BridgePacket.deserialize(from: packetData)

            XCTAssertEqual(packet.type, "bridge.battery", "Failed for \(name)")
            XCTAssertEqual(
                packet.body["currentCharge"] as? Int,
                expected["currentCharge"] as? Int,
                "currentCharge mismatch for \(name)"
            )
            XCTAssertEqual(
                packet.body["isCharging"] as? Bool,
                expected["isCharging"] as? Bool,
                "isCharging mismatch for \(name)"
            )
        }
    }

    func testBatteryFactory() throws {
        let packet = BridgePacket.battery(currentCharge: 85, isCharging: true)
        XCTAssertEqual(packet.type, "bridge.battery")
        XCTAssertEqual(packet.body["currentCharge"] as? Int, 85)
        XCTAssertEqual(packet.body["isCharging"] as? Bool, true)

        let data = try packet.serialize()
        let rt = try BridgePacket.deserialize(from: data)
        XCTAssertEqual(rt.body["currentCharge"] as? Int, 85)
    }

    // MARK: - Category selection

    func testCategorySelectionWithReplyId() {
        let packet = BridgePacket.notification(
            id: "test", appName: "App", title: "T", text: "B",
            actions: [], requestReplyId: "some-uuid"
        )
        let hasReplyId = packet.body["requestReplyId"] as? String != nil
        XCTAssertTrue(hasReplyId)
        XCTAssertEqual(hasReplyId ? "BRIDGE_REPLY" : "BRIDGE_DEFAULT", "BRIDGE_REPLY")
    }

    func testCategorySelectionWithoutReplyId() {
        let packet = BridgePacket.notification(
            id: "test", appName: "App", title: "T", text: "B",
            actions: [], requestReplyId: nil
        )
        let replyId = packet.body["requestReplyId"]
        XCTAssertTrue(replyId is NSNull)
        let hasReplyId = replyId != nil && !(replyId is NSNull)
        XCTAssertFalse(hasReplyId)
    }

    // MARK: - BR-6: bridge.notification.filter

    func testNotificationFilterVectorsParse() throws {
        let url = Self.testVectorsDir.appendingPathComponent("notification-filter.json")
        let data = try Data(contentsOf: url)
        let json = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        let valid = json["valid"] as! [[String: Any]]
        XCTAssertEqual(valid.count, 4, "expected mirroring, none-allowed, none-seen and paused")

        for vector in valid {
            let packetDict = vector["packet"] as! [String: Any]
            let packetData = try JSONSerialization.data(withJSONObject: packetDict)
            let packet = try BridgePacket.deserialize(from: packetData)
            XCTAssertEqual(packet.type, "bridge.notification.filter")
            XCTAssertNotNil(packet.body["allowed"] as? Int)
            XCTAssertNotNil(packet.body["muted"] as? Bool)
        }
    }

    func testPhoneClearedDismissalVectorsParse() throws {
        let url = Self.testVectorsDir.appendingPathComponent("notification-request.json")
        let data = try Data(contentsOf: url)
        let json = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        let valid = json["valid"] as! [[String: Any]]

        for vector in valid {
            let packetDict = vector["packet"] as! [String: Any]
            let packetData = try JSONSerialization.data(withJSONObject: packetDict)
            let packet = try BridgePacket.deserialize(from: packetData)
            XCTAssertEqual(packet.type, "bridge.notification.request")
            let cancel = packet.body["cancel"] as? String
            XCTAssertNotNil(cancel)
            XCTAssertFalse(cancel!.isEmpty, "a dismissal with no identifier cancels nothing")
        }
    }
}
