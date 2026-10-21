import XCTest
@testable import Bridge

final class TransferTierTests: XCTestCase {

    func testSmallFileUsesBluetooth() {
        let d = TierRouter.decide(payloadSize: 300_000, sharedNetworkAvailable: true)
        XCTAssertEqual(d.tier, .bluetooth)
        XCTAssertFalse(d.isSlowFallback)
    }

    func testBoundaryBelowThreshold() {
        let d = TierRouter.decide(payloadSize: 511_999, sharedNetworkAvailable: true)
        XCTAssertEqual(d.tier, .bluetooth)
    }

    func testThresholdExactUsesWifiWhenAvailable() {
        let d = TierRouter.decide(payloadSize: 512_000, sharedNetworkAvailable: true)
        XCTAssertEqual(d.tier, .wifiLan)
        XCTAssertFalse(d.isSlowFallback)
    }

    func testLargeFileUsesWifi() {
        let d = TierRouter.decide(payloadSize: 5_000_000, sharedNetworkAvailable: true)
        XCTAssertEqual(d.tier, .wifiLan)
    }

    func testLargeFileFallsBackToBluetoothWithWarning() {
        let d = TierRouter.decide(payloadSize: 2_000_000, sharedNetworkAvailable: false)
        XCTAssertEqual(d.tier, .bluetooth)
        XCTAssertTrue(d.isSlowFallback)
    }

    func testSmallFileNoNetworkNoFallbackFlag() {
        let d = TierRouter.decide(payloadSize: 100_000, sharedNetworkAvailable: false)
        XCTAssertEqual(d.tier, .bluetooth)
        XCTAssertFalse(d.isSlowFallback)
    }
}
