import XCTest
@testable import Bridge

final class SingleInstanceGuardTests: XCTestCase {

    private let bundleID = "com.bridge.mac"

    private func instance(_ pid: pid_t, _ bundleID: String?, terminated: Bool = false) -> SingleInstanceGuard.Instance {
        SingleInstanceGuard.Instance(pid: pid, bundleIdentifier: bundleID, isTerminated: terminated)
    }

    func testNoOtherInstanceLetsLaunchProceed() {
        let running = [instance(100, bundleID), instance(200, "com.apple.finder")]
        XCTAssertNil(SingleInstanceGuard.holder(among: running, selfPid: 100, bundleIdentifier: bundleID))
    }

    func testAnotherInstanceWithTheSameBundleIDHoldsTheApp() {
        let running = [instance(100, bundleID), instance(200, bundleID)]
        let holder = SingleInstanceGuard.holder(among: running, selfPid: 200, bundleIdentifier: bundleID)
        XCTAssertEqual(holder?.pid, 100)
    }

    /// Two builds at different paths share one bundle identifier — the case BR-14 exists for,
    /// so the path must never enter the comparison.
    func testPathIsNotPartOfTheComparison() {
        let running = [instance(100, bundleID)]
        XCTAssertNotNil(SingleInstanceGuard.holder(among: running, selfPid: 999, bundleIdentifier: bundleID))
    }

    func testADifferentBundleIDIsNotUs() {
        let running = [instance(100, "com.bridge.mac.helper"), instance(200, nil)]
        XCTAssertNil(SingleInstanceGuard.holder(among: running, selfPid: 300, bundleIdentifier: bundleID))
    }

    /// Quit then relaunch immediately: the old process is still in the snapshot. Handing over
    /// to it would exit this copy as well and leave no Bridge running.
    func testAQuittingInstanceIsNotAHolder() {
        let running = [instance(100, bundleID, terminated: true)]
        XCTAssertNil(SingleInstanceGuard.holder(among: running, selfPid: 200, bundleIdentifier: bundleID))
    }

    func testAnUnidentifiedSelfNeverYields() {
        let running = [instance(100, bundleID)]
        XCTAssertNil(SingleInstanceGuard.holder(among: running, selfPid: 200, bundleIdentifier: nil))
    }

    func testTwoOtherInstancesYieldToOne() {
        let running = [instance(100, bundleID), instance(200, bundleID), instance(300, bundleID)]
        let holder = SingleInstanceGuard.holder(among: running, selfPid: 300, bundleIdentifier: bundleID)
        XCTAssertNotNil(holder)
        XCTAssertNotEqual(holder?.pid, 300)
    }

    // MARK: - Test-host escape hatch

    func testTestHostIsRecognised() {
        XCTAssertTrue(SingleInstanceGuard.isUnderTest(["XCTestConfigurationFilePath": "/tmp/x.xctestconfiguration"]))
        XCTAssertFalse(SingleInstanceGuard.isUnderTest([:]))
    }

    /// `BridgeTests` hosts itself in `Bridge.app`. Without the escape hatch this call exits the
    /// test host whenever a copy of Bridge is running, so reaching the next line is the assertion.
    @MainActor
    func testEnforceReturnsUnderTest() {
        SingleInstanceGuard.enforce()
        XCTAssertTrue(SingleInstanceGuard.isUnderTest(ProcessInfo.processInfo.environment))
    }
}
