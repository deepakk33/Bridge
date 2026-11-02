import AppKit
import XCTest
@testable import Bridge

/// Since BR-16 the phone's reconnect packet carries a timestamp and no clipboard text. A
/// receiver must ignore it — reading a missing `content` as an empty clip would clear the
/// pasteboard on every reconnect, which is the opposite of what the change is for.
final class ClipboardMonitorTests: XCTestCase {

    private static var vectorsDir: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("protocol/test-vectors")
    }

    private func timestampOnlyVector() throws -> BridgePacket {
        let data = try Data(contentsOf: Self.vectorsDir.appendingPathComponent("clipboard-connect.json"))
        let json = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        let valid = try XCTUnwrap(json["valid"] as? [[String: Any]])
        let vector = try XCTUnwrap(
            valid.first { $0["name"] as? String == "clipboard_connect_timestamp_only" },
            "the timestamp-only vector is missing from clipboard-connect.json"
        )
        let packetDict = try XCTUnwrap(vector["packet"] as? [String: Any])
        return try BridgePacket.deserialize(from: try JSONSerialization.data(withJSONObject: packetDict))
    }

    /// These tests write to the real pasteboard, which belongs to whoever is running them.
    private var saved: String?

    override func setUp() {
        super.setUp()
        saved = NSPasteboard.general.string(forType: .string)
    }

    override func tearDown() {
        NSPasteboard.general.clearContents()
        if let saved { NSPasteboard.general.setString(saved, forType: .string) }
        super.tearDown()
    }

    func testConnectPacketWithoutContentLeavesPasteboardAlone() async throws {
        let packet = try timestampOnlyVector()
        XCTAssertNil(packet.body["content"], "the vector must carry no content key")

        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        pasteboard.setString("set by the user, not by Bridge", forType: .string)
        let before = pasteboard.string(forType: .string)

        let monitor = ClipboardMonitor()
        // lastClipboardTimestamp starts at 0, so the vector's timestamp is newer: the packet
        // clears every check except the missing content, which is the one under test.
        await monitor.handleClipboardConnectPacket(packet)

        XCTAssertEqual(pasteboard.string(forType: .string), before,
                       "a connect packet with no content must not change the pasteboard")
    }

    func testConnectPacketWithContentStillApplies() async throws {
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        pasteboard.setString("older local clip", forType: .string)

        let packet = BridgePacket.clipboardConnect(content: "newer remote clip",
                                                  timestamp: Int64(Date().timeIntervalSince1970 * 1000))
        let monitor = ClipboardMonitor()
        await monitor.handleClipboardConnectPacket(packet)

        XCTAssertEqual(pasteboard.string(forType: .string), "newer remote clip",
                       "Mac-to-phone direction is unchanged by BR-16")
    }
}
