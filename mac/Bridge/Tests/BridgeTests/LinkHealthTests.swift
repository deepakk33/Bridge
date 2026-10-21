import XCTest
@testable import Bridge

final class LinkHealthTests: XCTestCase {

    private let now = Date(timeIntervalSince1970: 1_000_000)

    func testConnectedWhenRecent() {
        let h = LinkHealthEvaluator.evaluate(lastSeen: now.addingTimeInterval(-10), now: now, bleConnected: true, bluetoothOn: true)
        XCTAssertEqual(h, .connected)
    }

    func testStaleAfter60s() {
        let h = LinkHealthEvaluator.evaluate(lastSeen: now.addingTimeInterval(-65), now: now, bleConnected: true, bluetoothOn: true)
        XCTAssertEqual(h, .stale(seconds: 65))
    }

    func testDeadAfter90sWhileBleConnected() {
        let h = LinkHealthEvaluator.evaluate(lastSeen: now.addingTimeInterval(-95), now: now, bleConnected: true, bluetoothOn: true)
        XCTAssertEqual(h, .dead)
    }

    func testOutOfRangeWhenBleDisconnected() {
        let h = LinkHealthEvaluator.evaluate(lastSeen: now.addingTimeInterval(-200), now: now, bleConnected: false, bluetoothOn: true)
        XCTAssertEqual(h, .outOfRange)
    }

    func testBluetoothOffTakesPriority() {
        let h = LinkHealthEvaluator.evaluate(lastSeen: now, now: now, bleConnected: false, bluetoothOn: false)
        XCTAssertEqual(h, .bluetoothOff)
    }

    func testLastSeenTextFormats() {
        XCTAssertEqual(LinkHealthEvaluator.lastSeenText(lastSeen: now.addingTimeInterval(-2), now: now), "Last seen: just now")
        XCTAssertEqual(LinkHealthEvaluator.lastSeenText(lastSeen: now.addingTimeInterval(-30), now: now), "Last seen: 30s ago")
        XCTAssertEqual(LinkHealthEvaluator.lastSeenText(lastSeen: now.addingTimeInterval(-150), now: now), "Last seen: 2m ago")
    }

    func testDeadHasBatteryKillGuidance() {
        let g = LinkHealthEvaluator.guidance(for: .dead)
        XCTAssertNotNil(g)
        XCTAssertTrue(g!.contains("battery management"))
    }

    func testConnectedHasNoGuidance() {
        XCTAssertNil(LinkHealthEvaluator.guidance(for: .connected))
    }
}
