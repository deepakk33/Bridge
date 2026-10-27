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

    // MARK: - Disconnect reason (BR-3)

    func testPeerTerminatedDisconnectIsDeadNotOutOfRange() {
        let h = LinkHealthEvaluator.evaluate(
            lastSeen: now.addingTimeInterval(-2), now: now,
            bleConnected: false, bluetoothOn: true, lastDisconnect: .peerTerminated
        )
        XCTAssertEqual(h, .dead)
    }

    func testSupervisionTimeoutIsOutOfRange() {
        let h = LinkHealthEvaluator.evaluate(
            lastSeen: now.addingTimeInterval(-2), now: now,
            bleConnected: false, bluetoothOn: true, lastDisconnect: .supervisionTimeout
        )
        XCTAssertEqual(h, .outOfRange)
    }

    func testLocalDisconnectIsDisconnected() {
        let h = LinkHealthEvaluator.evaluate(
            lastSeen: now, now: now,
            bleConnected: false, bluetoothOn: true, lastDisconnect: .local
        )
        XCTAssertEqual(h, .disconnected)
    }

    func testUnknownDisconnectFallsBackToOutOfRange() {
        let h = LinkHealthEvaluator.evaluate(
            lastSeen: now, now: now,
            bleConnected: false, bluetoothOn: true, lastDisconnect: .unknown
        )
        XCTAssertEqual(h, .outOfRange)
    }

    func testSilentDeathWhileStillConnectedBeatsDisconnectReason() {
        let h = LinkHealthEvaluator.evaluate(
            lastSeen: now.addingTimeInterval(-95), now: now,
            bleConnected: true, bluetoothOn: true, lastDisconnect: .supervisionTimeout
        )
        XCTAssertEqual(h, .dead)
    }

    // MARK: - Verification (BR-3)

    func testUnverifiedPeerIsNeverConnected() {
        for age in [0.0, 10.0, 65.0, 200.0] {
            let h = LinkHealthEvaluator.evaluate(
                lastSeen: now.addingTimeInterval(-age), now: now,
                bleConnected: true, bluetoothOn: true, isVerified: false
            )
            XCTAssertNotEqual(h, .connected, "age \(age)s must never read as connected")
        }
    }

    func testUnverifiedRecentPeerIsVerifying() {
        let h = LinkHealthEvaluator.evaluate(
            lastSeen: now.addingTimeInterval(-5), now: now,
            bleConnected: true, bluetoothOn: true, isVerified: false
        )
        XCTAssertEqual(h, .verifying)
    }

    /// A socket that opened and then went quiet is a dead service, not a slow handshake.
    func testUnverifiedPeerGoneQuietIsDeadNotVerifying() {
        let h = LinkHealthEvaluator.evaluate(
            lastSeen: now.addingTimeInterval(-95), now: now,
            bleConnected: true, bluetoothOn: true, isVerified: false
        )
        XCTAssertEqual(h, .dead)
    }

    func testUnverifiedPeerGoingQuietIsStale() {
        let h = LinkHealthEvaluator.evaluate(
            lastSeen: now.addingTimeInterval(-65), now: now,
            bleConnected: true, bluetoothOn: true, isVerified: false
        )
        XCTAssertEqual(h, .stale(seconds: 65))
    }

    func testBluetoothOffBeatsVerifying() {
        let h = LinkHealthEvaluator.evaluate(
            lastSeen: now, now: now,
            bleConnected: true, bluetoothOn: false, isVerified: false
        )
        XCTAssertEqual(h, .bluetoothOff)
    }

    /// An unknown Bluetooth state must not be rendered as "off" — see BLEManager.refreshHealth.
    /// Guarded here so the evaluator's own contract stays explicit: it is only ever handed a
    /// state Core Bluetooth has actually reported.
    func testBluetoothOffIsOnlyForAReportedOffState() {
        XCTAssertEqual(
            LinkHealthEvaluator.evaluate(
                lastSeen: now, now: now, bleConnected: false, bluetoothOn: false
            ),
            .bluetoothOff
        )
        XCTAssertNotEqual(
            LinkHealthEvaluator.evaluate(
                lastSeen: now, now: now, bleConnected: false, bluetoothOn: true
            ),
            .bluetoothOff
        )
    }

    // MARK: - Guidance (BR-3)

    func testGuidanceIsDefinedForEveryState() {
        XCTAssertNotNil(LinkHealthEvaluator.guidance(for: .dead))
        XCTAssertNotNil(LinkHealthEvaluator.guidance(for: .outOfRange))
        XCTAssertNotNil(LinkHealthEvaluator.guidance(for: .bluetoothOff))
        XCTAssertNil(LinkHealthEvaluator.guidance(for: .connected))
        XCTAssertNil(LinkHealthEvaluator.guidance(for: .verifying))
        XCTAssertNil(LinkHealthEvaluator.guidance(for: .disconnected))
        XCTAssertNil(LinkHealthEvaluator.guidance(for: .stale(seconds: 65)))
    }
}
