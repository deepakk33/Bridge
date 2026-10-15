import XCTest
@testable import Bridge

final class TrustStoreTests: XCTestCase {

    private var store: TrustStore!

    override func setUp() {
        super.setUp()
        store = TrustStore()
        store.unpinAll()
    }

    override func tearDown() {
        store.unpinAll()
        super.tearDown()
    }

    func testPinAndRetrieve() {
        store.pin(deviceId: "device-1", fingerprint: "fp-1")
        XCTAssertTrue(store.isTrusted(deviceId: "device-1", fingerprint: "fp-1"))
        XCTAssertFalse(store.isTrusted(deviceId: "device-1", fingerprint: "fp-wrong"))
    }

    func testUnpin() {
        store.pin(deviceId: "device-1", fingerprint: "fp-1")
        store.unpin(deviceId: "device-1")
        XCTAssertFalse(store.hasPinnedDevice(deviceId: "device-1"))
    }

    func testUnpinAll() {
        store.pin(deviceId: "device-1", fingerprint: "fp-1")
        store.pin(deviceId: "device-2", fingerprint: "fp-2")
        store.unpinAll()
        XCTAssertTrue(store.isEmpty)
    }

    func testPinnedFingerprint() {
        store.pin(deviceId: "device-1", fingerprint: "fp-1")
        XCTAssertEqual(store.pinnedFingerprint(for: "device-1"), "fp-1")
        XCTAssertNil(store.pinnedFingerprint(for: "device-unknown"))
    }

    func testFingerprintMismatchNotTrusted() {
        store.pin(deviceId: "device-1", fingerprint: "fp-1")
        XCTAssertFalse(store.isTrusted(deviceId: "device-1", fingerprint: "fp-different"))
    }
}
