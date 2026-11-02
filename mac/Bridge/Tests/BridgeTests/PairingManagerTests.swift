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

    // MARK: - The confirmation does not depend on arrival order (BR-19)

    /// `setLocalFingerprint` is delivered by a detached Task in BLEManager, so an
    /// identity packet can beat it. The old code logged an error and returned, leaving
    /// pairing silently impossible for the life of the connection.
    func testIdentityBeforeLocalFingerprintStillRaisesTheConfirmation() {
        let late = PairingManager()
        late.configure(localDeviceId: "mac-1") { _ in }

        late.setPeerIdentity(
            deviceId: "phone-1", deviceName: "Galaxy S25", fingerprint: phoneFingerprint
        )
        XCTAssertNil(late.incomingPairRequest, "no code can be derived yet")

        late.setLocalFingerprint(macFingerprint)

        let request = late.incomingPairRequest
        XCTAssertNotNil(request, "the held request must be presented once the fingerprint lands")
        XCTAssertEqual(request?.deviceId, "phone-1")
        XCTAssertEqual(
            request?.verificationCode,
            PairingCode.derive(macFingerprint, phoneFingerprint),
            "the digits must match the ones the phone shows"
        )
    }

    /// A peer that disconnects while its request is held must not produce a prompt for
    /// a connection that is gone.
    func testAHeldRequestIsDroppedOnReset() {
        let late = PairingManager()
        late.configure(localDeviceId: "mac-1") { _ in }

        late.setPeerIdentity(
            deviceId: "phone-1", deviceName: "Galaxy S25", fingerprint: phoneFingerprint
        )
        late.reset()
        late.setLocalFingerprint(macFingerprint)

        XCTAssertNil(late.incomingPairRequest, "a held request must not survive a disconnect")
    }

    /// The ordinary order keeps working.
    func testLocalFingerprintFirstPresentsImmediately() {
        identifyUnknownPhone()

        XCTAssertNotNil(manager.incomingPairRequest)
        XCTAssertEqual(
            manager.incomingPairRequest?.verificationCode,
            PairingCode.derive(macFingerprint, phoneFingerprint)
        )
    }

    /// Holding a confirmation is only better than dropping it if someone re-reads the
    /// Keychain. Without this the hold is a quieter version of the same dead end.
    func testHoldingAConfirmationAsksForTheFingerprintAgain() {
        let late = PairingManager()
        late.configure(localDeviceId: "mac-1") { _ in }
        var asked = 0
        late.onLocalFingerprintNeeded = { asked += 1 }

        late.setPeerIdentity(
            deviceId: "phone-1", deviceName: "Galaxy S25", fingerprint: phoneFingerprint
        )

        XCTAssertEqual(asked, 1, "a held confirmation must ask for the fingerprint")
    }

    /// Nothing is pinned for a user who was shown no digits. Without the local
    /// fingerprint no code exists, so there is nothing they could have compared.
    func testAcceptingWithNoVerificationCodeDoesNotPin() {
        let late = PairingManager()
        late.configure(localDeviceId: "mac-1") { _ in }

        late.setPeerIdentity(
            deviceId: "phone-1", deviceName: "Galaxy S25", fingerprint: phoneFingerprint
        )
        // The peer says yes first; only this device's confirmation is missing.
        late.handlePairPacket(BridgePacket.pairRequest())
        late.acceptIncomingPair()

        XCTAssertFalse(
            TrustStore.shared.isTrusted(deviceId: "phone-1", fingerprint: phoneFingerprint),
            "a peer must not be pinned on a confirmation that was never shown"
        )
    }

    /// The same acceptance succeeds once the digits can actually be derived.
    func testAcceptingPinsOnceTheCodeExists() {
        identifyUnknownPhone()
        manager.handlePairPacket(BridgePacket.pairRequest())
        manager.acceptIncomingPair()

        XCTAssertTrue(
            TrustStore.shared.isTrusted(deviceId: "phone-1", fingerprint: phoneFingerprint)
        )
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

    /// Connecting stops the scan and empties the picker, so a decline that does not drop
    /// the link leaves the Mac with nothing to click and no way back short of relaunching.
    func testLocalDeclineAsksToReturnToThePicker() {
        var returned = false
        manager.onPairDeclined = { returned = true }
        identifyUnknownPhone()
        manager.handlePairPacket(BridgePacket.pairRequest())
        manager.rejectIncomingPair()

        XCTAssertTrue(returned, "declining must return the Mac to the picker")
    }

    func testRemoteDeclineAlsoReturnsToThePicker() {
        var returned = false
        manager.onPairDeclined = { returned = true }
        identifyUnknownPhone()
        manager.acceptIncomingPair()
        manager.handlePairPacket(BridgePacket.pairReject())

        XCTAssertTrue(returned, "a decline on the phone must also free the Mac to retry")
    }

    /// Unpairing a paired device is a different path and must not be mistaken for a decline.
    func testUnpairingDoesNotFireTheDeclineHook() {
        TrustStore.shared.pin(deviceId: "phone-1", fingerprint: phoneFingerprint)
        identifyUnknownPhone()
        var returned = false
        manager.onPairDeclined = { returned = true }
        manager.unpair()

        XCTAssertFalse(returned)
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
