import XCTest
import CoreBluetooth
import UserNotifications
@testable import Bridge

final class PermissionCheckerTests: XCTestCase {

    func testBluetoothAuthorizationMapping() {
        XCTAssertEqual(PermissionChecker.map(.allowedAlways), .granted)
        XCTAssertEqual(PermissionChecker.map(.denied), .denied)
        XCTAssertEqual(PermissionChecker.map(.restricted), .restricted)
        XCTAssertEqual(PermissionChecker.map(.notDetermined), .notRequested)
    }

    func testNotificationAuthorizationMapping() {
        XCTAssertEqual(PermissionChecker.mapNotification(.authorized), .granted)
        XCTAssertEqual(PermissionChecker.mapNotification(.provisional), .granted)
        XCTAssertEqual(PermissionChecker.mapNotification(.denied), .denied)
        XCTAssertEqual(PermissionChecker.mapNotification(.notDetermined), .notRequested)
    }

    func testStatusLabels() {
        XCTAssertEqual(PermissionStatus.granted.label, "Granted")
        XCTAssertEqual(PermissionStatus.denied.label, "Denied")
        XCTAssertEqual(PermissionStatus.notRequested.label, "Not Requested")
    }
}
