import XCTest
@testable import Bridge

/// BR-5: nothing is pinned until both this device's user and the peer have accepted.
/// The old code staged a confirmation and then accepted on the user's behalf, so these
/// tests are mostly about what must *not* happen.
@MainActor
final class PairingManagerTests: XCTestCase {

    private let macFingerprint = String(repeating: "aa", count: 32)
    private let phoneFingerprint = String(repeating: "bb", count: 32)
    private let otherFingerprint = String(repeating: "cc", count: 32)

    private var manager: PairingManager!
    private var sent: [BridgePacket] = []

    override func setUp() {
        super.setUp()
        TrustStore.shared.unpinAll()
        sent = []
        manager = PairingManager()
        manager.configure(localDeviceId: "mac-1") { [self] packet in sent.append(packet) }
        manager.setLocalFingerprint(macFingerprint)
    }

    override func tearDown() {
        TrustStore.shared.unpinAll()
        super.tearDown()
    }

    private func identifyUnknownPhone() {
        manager.setPeerIdentity(deviceId: "phone-1", deviceName: "Galaxy S25", fingerprint: phoneFingerprint)
    }

    private var isPaired: Bool {
        if case .paired = manager.state { return true }
        return false
    }

    // MARK: - Both decisions required, either order

    func testLocalThenRemoteAcceptPins() {
        identifyUnknownPhone()
        manager.acceptIncomingPair()
        XCTAssertFalse(isPaired, "one acceptance is not two")

        manager.handlePairPacket(BridgePacket.pairAccept())
        XCTAssertTrue(isPaired)
        XCTAssertTrue(TrustStore.shared.isTrusted(deviceId: "phone-1", fingerprint: phoneFingerprint))
    }

    func testRemoteThenLocalAcceptPins() {
        identifyUnknownPhone()
        manager.handlePairPacket(BridgePacket.pairRequest())
        XCTAssertFalse(isPaired, "the peer's acceptance alone is not consent")
        XCTAssertTrue(TrustStore.shared.isEmpty)

        manager.acceptIncomingPair()
        XCTAssertTrue(isPaired)
        XCTAssertTrue(TrustStore.shared.isTrusted(deviceId: "phone-1", fingerprint: phoneFingerprint))
    }

    /// The exact shape of the shipped bug: a confirmation was staged and then accepted
    /// on the next line.
    func testRemoteAcceptAloneNeverPins() {
        identifyUnknownPhone()
        manager.handlePairPacket(BridgePacket.pairRequest())
        manager.handlePairPacket(BridgePacket.pairAccept())

        XCTAssertFalse(isPaired)
        XCTAssertTrue(TrustStore.shared.isEmpty)
        XCTAssertNotNil(manager.incomingPairRequest, "the user should still be being asked")
    }

    // MARK: - Declining

    func testLocalDeclineLeavesBothStoresUnchanged() {
        identifyUnknownPhone()
        manager.handlePairPacket(BridgePacket.pairRequest())
        manager.rejectIncomingPair()

        XCTAssertFalse(isPaired)
        XCTAssertTrue(TrustStore.shared.isEmpty)
        XCTAssertTrue(sent.contains { $0.body["pair"] as? Bool == false }, "peer must be told")

        // A later accept from the peer must not resurrect the declined pairing.
        manager.handlePairPacket(BridgePacket.pairAccept())
        XCTAssertTrue(TrustStore.shared.isEmpty)
    }

    func testRemoteDeclineLeavesBothStoresUnchanged() {
        identifyUnknownPhone()
        manager.acceptIncomingPair()
        manager.handlePairPacket(BridgePacket.pairReject())

        XCTAssertFalse(isPaired)
        XCTAssertTrue(TrustStore.shared.isEmpty)
    }

    // MARK: - An empty trust store is not consent

    func testEmptyTrustStoreDoesNotAutoPair() {
        XCTAssertTrue(TrustStore.shared.isEmpty)
        identifyUnknownPhone()

        XCTAssertFalse(isPaired)
        XCTAssertTrue(TrustStore.shared.isEmpty, "first run is still a human decision")
        XCTAssertNotNil(manager.incomingPairRequest)
        XCTAssertFalse(sent.contains { $0.body["pair"] as? Bool == true }, "nothing affirmed yet")
    }

    func testIncomingRequestOnEmptyStoreWaitsForExplicitAccept() {
        manager.setPeerIdentity(deviceId: "phone-1", deviceName: "Galaxy S25", fingerprint: phoneFingerprint)
        manager.handlePairPacket(BridgePacket.pairRequest())
        XCTAssertTrue(TrustStore.shared.isEmpty)

        manager.acceptIncomingPair()
        XCTAssertTrue(TrustStore.shared.isTrusted(deviceId: "phone-1", fingerprint: phoneFingerprint))
    }

    // MARK: - Fingerprint mismatch

    func testPinnedDeviceWithChangedFingerprintIsRefused() {
        TrustStore.shared.pin(deviceId: "phone-1", fingerprint: phoneFingerprint)
        var mismatched: String?
        manager.onFingerprintMismatch = { mismatched = $0 }

        manager.setPeerIdentity(deviceId: "phone-1", deviceName: "Galaxy S25", fingerprint: otherFingerprint)

        XCTAssertEqual(mismatched, "Galaxy S25", "the link must be closed")
        XCTAssertEqual(
            TrustStore.shared.pinnedFingerprint(for: "phone-1"), phoneFingerprint,
            "the pin must survive, so re-pairing stays deliberate"
        )
        XCTAssertFalse(isPaired)
        XCTAssertNil(manager.incomingPairRequest, "a mismatch is not a pairing offer")
    }

    func testAlreadyPairedDeviceReconnectsSilently() {
        TrustStore.shared.pin(deviceId: "phone-1", fingerprint: phoneFingerprint)
        identifyUnknownPhone()

        XCTAssertTrue(isPaired)
        XCTAssertNil(manager.incomingPairRequest)
    }

    // MARK: - Crossed initiation

    func testCrossedRequestStillConvergesWhenBothUsersAccept() {
        identifyUnknownPhone()
        manager.acceptIncomingPair()                           // we send a request
        manager.handlePairPacket(BridgePacket.pairRequest())   // theirs crosses ours

        XCTAssertTrue(isPaired, "both users accepted, so both sides pin")
        XCTAssertTrue(TrustStore.shared.isTrusted(deviceId: "phone-1", fingerprint: phoneFingerprint))
    }

    // MARK: - Timeout scope

    /// The 30-second timer only runs in `.awaitingResponse`, which is only entered
    /// after the local user accepts. A prompt nobody has answered must not expire.
    func testPendingLocalPromptDoesNotEnterTheTimedState() {
        identifyUnknownPhone()
        manager.handlePairPacket(BridgePacket.pairRequest())

        if case .awaitingResponse = manager.state {
            XCTFail("a prompt awaiting this device's user must not be on the peer timeout")
        }
        XCTAssertNotNil(manager.incomingPairRequest)
    }

    func testAcceptingEntersTheTimedState() {
        identifyUnknownPhone()
        manager.acceptIncomingPair()

        guard case .awaitingResponse = manager.state else {
            return XCTFail("after accepting, the peer's answer is what is being waited on")
        }
    }

    // MARK: - The code reaches the UI

    func testConfirmationCarriesTheSharedCode() {
        identifyUnknownPhone()
        XCTAssertEqual(
            manager.incomingPairRequest?.verificationCode,
            PairingCode.derive(macFingerprint, phoneFingerprint)
        )
    }

    func testNoConfirmationWithoutALocalFingerprint() {
        let bare = PairingManager()
        bare.configure(localDeviceId: "mac-1") { _ in }
        bare.setPeerIdentity(deviceId: "phone-1", deviceName: "Galaxy S25", fingerprint: phoneFingerprint)

        XCTAssertNil(bare.incomingPairRequest, "no code means no confirmation, so no pairing")
        XCTAssertTrue(TrustStore.shared.isEmpty)
    }
}
