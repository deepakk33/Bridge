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

    // MARK: - Panel filtering

    private func item(_ id: String, _ status: PermissionStatus) -> PermissionItem {
        PermissionItem(id: id, name: id.capitalized, status: status, settingsURL: nil)
    }

    func testAllGrantedListsNothing() {
        let items = [
            item("bluetooth", .granted),
            item("localNetwork", .granted),
            item("notifications", .granted)
        ]
        XCTAssertTrue(PermissionChecker.needingAttention(in: items).isEmpty)
    }

    func testOnlyNonGrantedAreListed() {
        let items = [
            item("bluetooth", .granted),
            item("localNetwork", .notRequested),
            item("notifications", .denied)
        ]
        XCTAssertEqual(
            PermissionChecker.needingAttention(in: items).map(\.id),
            ["localNetwork", "notifications"]
        )
    }

    func testRestrictedIsListed() {
        XCTAssertEqual(PermissionChecker.needingAttention(in: [item("bluetooth", .restricted)]).count, 1)
    }

    func testRevokedPermissionReappears() {
        let granted = [item("bluetooth", .granted)]
        XCTAssertTrue(PermissionChecker.needingAttention(in: granted).isEmpty)

        let revoked = [item("bluetooth", .denied)]
        XCTAssertEqual(PermissionChecker.needingAttention(in: revoked).map(\.id), ["bluetooth"])
    }

    func testEmptyInputListsNothing() {
        XCTAssertTrue(PermissionChecker.needingAttention(in: []).isEmpty)
    }
}
