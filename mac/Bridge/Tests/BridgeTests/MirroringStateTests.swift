import XCTest
@testable import Bridge

/// The Mac cannot distinguish "no apps allowed" from "allowed apps are quiet", which is
/// how an empty allow-list went unnoticed. These pin the three states apart, and pin the
/// rule that the Mac never invents a count it was not told.
final class MirroringStateTests: XCTestCase {

    func testCountIsReported() {
        XCTAssertEqual(MirroringState(allowed: 3, seen: 47, muted: false).summary, "Mirroring 3 apps")
    }

    func testSingularIsNotPluralised() {
        XCTAssertEqual(MirroringState(allowed: 1, seen: 47, muted: false).summary, "Mirroring 1 app")
    }

    func testNothingAllowedSaysSoRatherThanShowingZero() {
        let summary = MirroringState(allowed: 0, seen: 47, muted: false).summary
        XCTAssertEqual(summary, "No apps mirroring")
        XCTAssertFalse(summary.contains("0"), "a bare zero does not explain itself")
    }

    func testPausedOutranksTheCount() {
        // An allowed app is not mirroring while master mute is on; reporting "3 apps"
        // here would be the same lie the panel is meant to stop telling.
        XCTAssertEqual(MirroringState(allowed: 3, seen: 47, muted: true).summary, "Mirroring paused")
        XCTAssertEqual(MirroringState(allowed: 0, seen: 0, muted: true).summary, "Mirroring paused")
    }

    func testNothingSeenYetStillReadsAsNothingMirroring() {
        XCTAssertEqual(MirroringState(allowed: 0, seen: 0, muted: false).summary, "No apps mirroring")
    }

    func testUnknownIsDistinctFromNone() {
        XCTAssertNotEqual(
            MirroringState.unknownSummary,
            MirroringState(allowed: 0, seen: 0, muted: false).summary,
            "not having been told must not read as having been told zero"
        )
    }

    func testEquatableSoRepeatedPacketsAreIdempotent() {
        let a = MirroringState(allowed: 2, seen: 9, muted: false)
        let b = MirroringState(allowed: 2, seen: 9, muted: false)
        XCTAssertEqual(a, b)
        XCTAssertNotEqual(a, MirroringState(allowed: 2, seen: 9, muted: true))
    }
}

/// The dismissal guard. A packet that names no notification must withdraw nothing
/// rather than silently withdraw "" and read as if it worked.
final class NotificationDismissalTests: XCTestCase {

    private func packet(type: String, body: [String: Any]) -> BridgePacket {
        BridgePacket(id: 1_728_000_000_000, type: type, body: body)
    }

    func testIdentifierIsTakenFromAValidDismissal() {
        let p = packet(type: "bridge.notification.request", body: ["cancel": "0|com.whatsapp|1|null|10"])
        XCTAssertEqual(NotificationManager.cancelIdentifier(from: p), "0|com.whatsapp|1|null|10")
    }

    func testEmptyIdentifierIsRejected() {
        let p = packet(type: "bridge.notification.request", body: ["cancel": ""])
        XCTAssertNil(NotificationManager.cancelIdentifier(from: p))
    }

    func testMissingIdentifierIsRejected() {
        let p = packet(type: "bridge.notification.request", body: [:])
        XCTAssertNil(NotificationManager.cancelIdentifier(from: p))
    }

    func testOtherPacketTypesAreIgnored() {
        let p = packet(type: "bridge.notification", body: ["cancel": "0|com.whatsapp|1|null|10"])
        XCTAssertNil(NotificationManager.cancelIdentifier(from: p), "only a dismissal withdraws")
    }

    func testNonStringIdentifierIsRejected() {
        let p = packet(type: "bridge.notification.request", body: ["cancel": 42])
        XCTAssertNil(NotificationManager.cancelIdentifier(from: p))
    }
}
