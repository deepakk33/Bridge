import Foundation
import os

@MainActor
final class PairingManager: ObservableObject {
    enum PairingState: Sendable {
        case unpaired
        case awaitingResponse(deviceName: String)
        case paired(deviceId: String, deviceName: String)
    }

    /// One side's answer to the confirmation. Nothing is pinned until both this
    /// device's user and the peer have accepted, in either order — holding the
    /// peer's answer is what makes "the user has not decided yet" a real state
    /// rather than an instant that the old code skipped straight past.
    enum Decision: Sendable {
        case pending
        case accepted
        case declined
    }

    @Published var state: PairingState = .unpaired
    @Published var incomingPairRequest: PairRequestInfo?

    /// Called when a confirmation is staged, so the app can surface it outside the
    /// menu-bar panel (the sheet only renders while that panel is open).
    var onPairConfirmationPending: ((String) -> Void)?

    /// Raised when a pairing is declined by either side and nothing was pinned. The
    /// caller drops the link and resumes scanning: the connection stopped the scan and
    /// emptied the picker, so without this a decline leaves the Mac with nothing to
    /// click and no way back short of relaunching.
    var onPairDeclined: (() -> Void)?

    /// Raised when a device whose `deviceId` is pinned presents a different
    /// fingerprint. The caller closes the link; the pin is deliberately left intact,
    /// so returning that device to service needs a deliberate unpair.
    var onFingerprintMismatch: ((String) -> Void)?

    struct PairRequestInfo: Identifiable {
        let id = UUID()
        let deviceId: String
        let deviceName: String
        let fingerprint: String
        /// The six digits the peer displays at the same moment. They agree only when
        /// each device is really talking to the device it thinks it is.
        let verificationCode: String
    }

    private static let logger = Logger(subsystem: "com.bridge.mac", category: "PairingManager")
    private static let timeoutSeconds: UInt64 = 30

    private var sendPacket: ((BridgePacket) -> Void)?
    private var localDeviceId: String = ""
    private var peerDeviceId: String?
    private var peerDeviceName: String?
    private var peerFingerprint: String?
    private var pairingTimeoutTask: Task<Void, Never>?

    private(set) var localDecision: Decision = .pending
    private(set) var remoteDecision: Decision = .pending

    /// This device's own certificate fingerprint, supplied once the Keychain identity
    /// has loaded. `CertificateManager` is an actor, so it cannot be read from the
    /// synchronous UI path where the code is needed.
    private(set) var localFingerprint: String?

    /// A peer confirmed before `localFingerprint` arrived. The two can land in either
    /// order — the fingerprint comes from an actor, the identity from the wire — and
    /// dropping the peer left pairing silently impossible for the connection.
    private var deferredConfirmation: (deviceId: String, deviceName: String, fingerprint: String)?

    /// Crossed initiation: both sides sent a request at once. The lower deviceId
    /// stays the initiator, the other yields and accepts, so exactly one accept
    /// flows and both sides pin. Equal (or unknown local) ids make both yield,
    /// which also converges — both accept and both pin.
    nonisolated static func yieldsOnCrossedRequest(localDeviceId: String, peerDeviceId: String) -> Bool {
        localDeviceId.isEmpty || localDeviceId >= peerDeviceId
    }

    func configure(localDeviceId: String, sendPacket: @escaping (BridgePacket) -> Void) {
        self.localDeviceId = localDeviceId
        self.sendPacket = sendPacket
    }

    func setLocalFingerprint(_ fingerprint: String) {
        localFingerprint = fingerprint
        guard let peer = deferredConfirmation else { return }
        deferredConfirmation = nil
        Self.logger.info("Local fingerprint arrived — presenting the held confirmation")
        presentPairConfirmation(
            deviceId: peer.deviceId, deviceName: peer.deviceName, fingerprint: peer.fingerprint
        )
    }

    /// The six digits for the peer currently being confirmed, or nil when there is none.
    var verificationCode: String? {
        guard let peerFingerprint, let local = localFingerprint else { return nil }
        return PairingCode.derive(local, peerFingerprint)
    }

    func setPeerIdentity(deviceId: String, deviceName: String, fingerprint: String) {
        peerDeviceId = deviceId
        peerDeviceName = deviceName
        peerFingerprint = fingerprint

        if TrustStore.shared.isTrusted(deviceId: deviceId, fingerprint: fingerprint) {
            state = .paired(deviceId: deviceId, deviceName: deviceName)
            Self.logger.info("Already paired with \(deviceName)")
            return
        }

        if let pinned = TrustStore.shared.pinnedFingerprint(for: deviceId) {
            // A pinned device presenting a different certificate is a reinstall or an
            // impostor. Both need a deliberate re-pair, so the link closes and the pin
            // stays — the old code re-accepted here, which is the re-pair hole itself.
            Self.logger.error(
                "\(deviceName) [\(deviceId)] presented fingerprint \(fingerprint) but \(pinned) is pinned — closing"
            )
            onFingerprintMismatch?(deviceName)
            return
        }

        // An empty trust store is not consent. Unknown devices are confirmed by a
        // human, whether or not this Mac has paired with anything before.
        Self.logger.info("Unknown device \(deviceName) [\(deviceId)] — awaiting confirmation")
        presentPairConfirmation(deviceId: deviceId, deviceName: deviceName, fingerprint: fingerprint)
    }

    /// The local user said yes. Sends this device's affirmation and pins if the peer
    /// has already said yes too. Named for the UI call site that initiates.
    func requestPairing() {
        acceptPairing()
    }

    /// The local user said yes to a staged confirmation. Same decision as
    /// `requestPairing()`; both exist because the panel offers both entry points.
    func acceptIncomingPair() {
        acceptPairing()
    }

    func handlePairPacket(_ packet: BridgePacket) {
        guard let pairValue = packet.body["pair"] as? Bool else {
            Self.logger.warning("Invalid pair packet: missing 'pair' field")
            return
        }

        if pairValue {
            handlePeerAffirmation(kind: packet.body["kind"] as? String)
        } else {
            handlePairRejectOrUnpair()
        }
    }

    func rejectIncomingPair() {
        localDecision = .declined
        sendPacket?(BridgePacket.pairReject())
        incomingPairRequest = nil
        cancelTimeout()
        if case .paired = state {} else {
            state = .unpaired
            onPairDeclined?()
        }
        Self.logger.info("Rejected pair request")
    }

    func unpair() {
        guard let deviceId = peerDeviceId else { return }
        sendPacket?(BridgePacket.pairReject())
        TrustStore.shared.unpin(deviceId: deviceId)
        state = .unpaired
        resetDecisions()
        Self.logger.info("Unpaired device \(deviceId)")
    }

    func reset() {
        pairingTimeoutTask?.cancel()
        pairingTimeoutTask = nil
        peerDeviceId = nil
        peerDeviceName = nil
        peerFingerprint = nil
        incomingPairRequest = nil
        deferredConfirmation = nil
        resetDecisions()
        if case .paired = state {} else {
            state = .unpaired
        }
    }

    // MARK: - Private

    private func resetDecisions() {
        localDecision = .pending
        remoteDecision = .pending
    }

    /// Records this device's acceptance, tells the peer, and pins only if the peer
    /// has already accepted. Sends `accept` when the peer asked first, `request`
    /// otherwise, so the peer can tell an answer from an opening offer.
    private func acceptPairing() {
        guard let deviceName = peerDeviceName else {
            Self.logger.warning("Pair accepted before peer identity is known — ignoring")
            return
        }
        guard localDecision != .accepted else {
            Self.logger.info("Already accepted pairing with \(deviceName)")
            return
        }

        localDecision = .accepted
        let peerAskedFirst = remoteDecision == .accepted
        sendPacket?(peerAskedFirst ? BridgePacket.pairAccept() : BridgePacket.pairRequest())
        incomingPairRequest = nil

        if pinIfBothAccepted() { return }

        state = .awaitingResponse(deviceName: deviceName)
        startTimeout()
        Self.logger.info("Accepted locally, awaiting \(deviceName)")
    }

    /// `kind` is "request", "accept", or nil for peers that predate the field. Either
    /// way a `pair: true` means the peer's user has accepted.
    private func handlePeerAffirmation(kind: String?) {
        guard let deviceId = peerDeviceId,
              let deviceName = peerDeviceName,
              let fingerprint = peerFingerprint else {
            Self.logger.warning("Pair packet before peer identity — ignoring")
            return
        }

        remoteDecision = .accepted
        cancelTimeout()

        if pinIfBothAccepted() { return }

        // The peer is waiting on this device's user. Crossed requests are resolved by
        // deviceId so exactly one side sends the follow-up accept, but both sides
        // still ask their own user first.
        if kind == "request", case .awaitingResponse = state,
           !Self.yieldsOnCrossedRequest(localDeviceId: localDeviceId, peerDeviceId: deviceId) {
            Self.logger.info("Crossed pair request from \(deviceName) — we stay the initiator")
        }

        Self.logger.info("\(deviceName) accepted — awaiting this device's user")
        presentPairConfirmation(deviceId: deviceId, deviceName: deviceName, fingerprint: fingerprint)
    }

    /// - Returns: true when both sides have accepted and the peer is now pinned.
    @discardableResult
    private func pinIfBothAccepted() -> Bool {
        guard localDecision == .accepted, remoteDecision == .accepted,
              let deviceId = peerDeviceId,
              let deviceName = peerDeviceName,
              let fingerprint = peerFingerprint else {
            return false
        }
        TrustStore.shared.pin(deviceId: deviceId, fingerprint: fingerprint)
        state = .paired(deviceId: deviceId, deviceName: deviceName)
        incomingPairRequest = nil
        cancelTimeout()
        Self.logger.info("Both sides accepted — pinned \(deviceName)")
        return true
    }

    /// Surfaces the peer in the UI (menu bar badge + confirmation sheet) with the six
    /// digits. No local timeout: the peer may already have accepted, and a silently
    /// expiring prompt is the dead end this replaces. Cleared on disconnect by `reset()`.
    private func presentPairConfirmation(deviceId: String, deviceName: String, fingerprint: String) {
        guard localDecision == .pending else { return }
        guard let local = localFingerprint else {
            // Held, not dropped: `setLocalFingerprint` presents it the moment the
            // Keychain read lands. Cleared by `reset()` if the peer leaves first.
            Self.logger.info("No local fingerprint yet — holding the confirmation for \(deviceName)")
            deferredConfirmation = (deviceId, deviceName, fingerprint)
            return
        }
        incomingPairRequest = PairRequestInfo(
            deviceId: deviceId,
            deviceName: deviceName,
            fingerprint: fingerprint,
            verificationCode: PairingCode.derive(local, fingerprint)
        )
        onPairConfirmationPending?(deviceName)
    }

    private func handlePairRejectOrUnpair() {
        remoteDecision = .declined
        cancelTimeout()

        switch state {
        case .paired(let deviceId, _):
            TrustStore.shared.unpin(deviceId: deviceId)
            state = .unpaired
            resetDecisions()
            Self.logger.info("Unpaired by peer")

        case .awaitingResponse, .unpaired:
            // Nothing was pinned, and a decline on either side leaves both trust
            // stores exactly as they were.
            state = .unpaired
            incomingPairRequest = nil
            resetDecisions()
            Self.logger.info("Peer declined pairing — nothing pinned")
            onPairDeclined?()
        }
    }

    /// Bounds the *peer's* decision only. A confirmation this device's user has not
    /// answered never expires, because a prompt that vanishes on its own is the dead
    /// end BR-2 removed.
    private func startTimeout() {
        cancelTimeout()
        pairingTimeoutTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: Self.timeoutSeconds * 1_000_000_000)
            guard !Task.isCancelled else { return }
            // Local expiry only. Sending a reject here would make a peer that already
            // pinned us drop that pin, which is how the link ended up half-paired.
            Self.logger.info("Peer did not answer in time — giving up locally")
            if case .awaitingResponse = state {
                // The local user's acceptance stands. Clearing it would mean a peer that
                // answers late pins us while we do not pin it — the half-paired state
                // this timeout exists to avoid.
                state = .unpaired
                remoteDecision = .pending
            }
        }
    }

    private func cancelTimeout() {
        pairingTimeoutTask?.cancel()
        pairingTimeoutTask = nil
    }
}
