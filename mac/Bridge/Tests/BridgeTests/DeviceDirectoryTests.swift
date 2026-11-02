import XCTest
@testable import Bridge

final class DeviceDirectoryTests: XCTestCase {

    private let a = UUID()
    private let b = UUID()
    private let now = Date()

    func testRecordsADevice() {
        var dir = DeviceDirectory()
        dir.record(id: a, name: "Galaxy S25", rssi: -40, now: now)
        XCTAssertEqual(dir.devices.map(\.name), ["Galaxy S25"])
    }

    func testRefreshesRatherThanDuplicates() {
        var dir = DeviceDirectory()
        dir.record(id: a, name: "Galaxy S25", rssi: -70, now: now)
        dir.record(id: a, name: "Galaxy S25", rssi: -40, now: now.addingTimeInterval(1))
        XCTAssertEqual(dir.devices.count, 1)
        XCTAssertEqual(dir.devices.first?.rssi, -40)
    }

    func testStrongestSignalFirst() {
        var dir = DeviceDirectory()
        dir.record(id: a, name: "Far phone", rssi: -85, now: now)
        dir.record(id: b, name: "Desk phone", rssi: -38, now: now)
        XCTAssertEqual(dir.devices.map(\.name), ["Desk phone", "Far phone"])
    }

    /// BLE advertising is periodic; a device that is sitting right there still goes
    /// quiet for stretches, and dropping it immediately would make the list flicker.
    func testSurvivesAMissedAdvertisingInterval() {
        var dir = DeviceDirectory()
        dir.record(id: a, name: "Galaxy S25", rssi: -40, now: now)
        dir.prune(now: now.addingTimeInterval(3))
        XCTAssertEqual(dir.devices.count, 1)
    }

    func testDropsADeviceThatWalkedAway() {
        var dir = DeviceDirectory()
        dir.record(id: a, name: "Galaxy S25", rssi: -40, now: now)
        dir.prune(now: now.addingTimeInterval(DeviceDirectory.staleAfter + 1))
        XCTAssertTrue(dir.devices.isEmpty)
    }

    func testRecordingOneDeviceAgesOutAnother() {
        var dir = DeviceDirectory()
        dir.record(id: a, name: "Gone", rssi: -40, now: now)
        dir.record(id: b, name: "Here", rssi: -50, now: now.addingTimeInterval(DeviceDirectory.staleAfter + 1))
        XCTAssertEqual(dir.devices.map(\.name), ["Here"])
    }

    func testTiesBreakByNameSoTheListDoesNotJitter() {
        var dir = DeviceDirectory()
        dir.record(id: a, name: "Zed", rssi: -50, now: now)
        dir.record(id: b, name: "Alice", rssi: -50, now: now)
        XCTAssertEqual(dir.devices.map(\.name), ["Alice", "Zed"])
    }
}
