import XCTest
import AppKit
@testable import Bridge

/// A symbol name that does not resolve draws nothing at all, which reads as a broken app
/// rather than a missing icon — so every name the view can ask for is checked against the
/// system here. `battery.50.bolt` shipped for months and never drew.
@MainActor
final class PhoneBatteryStateTests: XCTestCase {

    private func symbol(level: Int?, charging: Bool) -> String {
        let state = PhoneBatteryState()
        state.level = level
        state.isCharging = charging
        return state.sfSymbol
    }

    func testEveryLevelResolvesToARealSymbol() {
        for level in 0...100 {
            for charging in [true, false] {
                let name = symbol(level: level, charging: charging)
                XCTAssertNotNil(
                    NSImage(systemSymbolName: name, accessibilityDescription: nil),
                    "level \(level) charging=\(charging) produced unresolvable symbol \(name)"
                )
            }
        }
    }

    func testUnknownLevelResolvesToARealSymbol() {
        let name = symbol(level: nil, charging: false)
        XCTAssertNotNil(NSImage(systemSymbolName: name, accessibilityDescription: nil))
    }

    func testSymbolTracksLevel() {
        XCTAssertEqual(symbol(level: 100, charging: false), "battery.100percent")
        XCTAssertEqual(symbol(level: 60, charging: false), "battery.75percent")
        XCTAssertEqual(symbol(level: 45, charging: true), "battery.50percent")
        XCTAssertEqual(symbol(level: 20, charging: false), "battery.25percent")
        XCTAssertEqual(symbol(level: 5, charging: false), "battery.0percent")
    }

    func testTintIsRedOnlyWhenLowAndNotCharging() {
        XCTAssertEqual(PhoneBatteryState.tintColor(level: 10, isCharging: false), .red)
        XCTAssertEqual(PhoneBatteryState.tintColor(level: 10, isCharging: true), .primary)
        XCTAssertEqual(PhoneBatteryState.tintColor(level: 80, isCharging: false), .primary)
        XCTAssertEqual(PhoneBatteryState.tintColor(level: nil, isCharging: false), .primary)
    }
}
