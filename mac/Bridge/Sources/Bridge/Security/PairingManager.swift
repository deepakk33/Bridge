import Foundation
import os

@MainActor
final class PairingManager: ObservableObject {
    enum PairingState: Sendable {
        case unpaired
        case awaitingResponse(deviceName: String)
        case paired(deviceId: String, deviceName: String)
    }

    @Published var state: PairingState = .unpaired
    @Published var incomingPairRequest: PairRequestInfo?

    /// Called when a confirmation is staged, so the app can surface it outside the
    /// menu-bar panel (the sheet only renders while that panel is open).
    var onPairConfirmationPending: ((String) -> Void)?

    struct PairRequestInfo: Identifiable {
        let id = UUID()
        let deviceId: String
        let deviceName: String
        let fingerprint: String
    }

    private static let logger = Logger(subsystem: "com.bridge.mac", category: "PairingManager")
    private static let timeoutSeconds: UInt64 = 30

    private var sendPacket: ((BridgePacket) -> Void)?
    private var localDeviceId: String = ""
    private var peerDeviceId: String?
    private var peerDeviceName: String?
    private var peerFingerprint: String?
    private var pairingTimeoutTask: Task<Void, Never>?

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

    func setPeerIdentity(deviceId: String, deviceName: String, fingerprint: String) {
        peerDeviceId = deviceId
        peerDeviceName = deviceName
        peerFingerprint = fingerprint

        if TrustStore.shared.isTrusted(deviceId: deviceId, fingerprint: fingerprint) {
            state = .paired(deviceId: deviceId, deviceName: deviceName)
            Self.logger.info("Already paired with \(deviceName)")
        } else if TrustStore.shared.isEmpty {
            Self.logger.info("Auto-pairing with \(deviceName) (trust store empty)")
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: 500_000_000)
                guard case .unpaired = self.state, self.incomingPairRequest == nil else {
                    Self.logger.info("Skipping auto-pair with \(deviceName): a pair exchange is already in progress")
                    return
                }
                self.requestPairing()
            }
        } else if let pinned = TrustStore.shared.pinnedFingerprint(for: deviceId) {
            Self.logger.warning(
                "\(deviceName) [\(deviceId)] presented fingerprint \(fingerprint) but \(pinned) is pinned — asking the user to confirm re-pairing"
            )
            presentPairConfirmation(deviceId: deviceId, deviceName: deviceName, fingerprint: fingerprint)
        } else {
            Self.logger.warning(
                "Unknown device \(deviceName) [\(deviceId)] while \(TrustStore.shared.count) other device(s) are pinned — asking the user to confirm pairing"
            )
            presentPairConfirmation(deviceId: deviceId, deviceName: deviceName, fingerprint: fingerprint)
        }
    }

    func requestPairing() {
        guard let name = peerDeviceName else {
            Self.logger.warning("Pair requested before peer identity is known — ignoring")
            return
        }
        if case .awaitingResponse = state {
            Self.logger.info("Pair request to \(name) already outstanding")
            return
        }
        let packet = BridgePacket.pairRequest()
        sendPacket?(packet)
        state = .awaitingResponse(deviceName: name)
        startTimeout()
        Self.logger.info("Sent pair request to \(name)")
    }

    func handlePairPacket(_ packet: BridgePacket) {
        guard let pairValue = packet.body["pair"] as? Bool else {
            Self.logger.warning("Invalid pair packet: missing 'pair' field")
            return
        }

        if pairValue {
            handlePairAcceptOrRequest(kind: packet.body["kind"] as? String)
        } else {
            handlePairRejectOrUnpair()
        }
    }

    func acceptIncomingPair() {
        guard let deviceId = peerDeviceId,
              let deviceName = peerDeviceName,
              let fingerprint = peerFingerprint else {
            Self.logger.warning("Cannot accept pair: peer identity unknown")
            return
        }

        sendPacket?(BridgePacket.pairAccept())
        TrustStore.shared.pin(deviceId: deviceId, fingerprint: fingerprint)
        state = .paired(deviceId: deviceId, deviceName: deviceName)
        incomingPairRequest = nil
        cancelTimeout()
        Self.logger.info("Accepted pair from \(deviceName)")
    }

    func rejectIncomingPair() {
        sendPacket?(BridgePacket.pairReject())
        incomingPairRequest = nil
        cancelTimeout()
        Self.logger.info("Rejected pair request")
    }

    func unpair() {
        guard let deviceId = peerDeviceId else { return }
        sendPacket?(BridgePacket.pairReject())
        TrustStore.shared.unpin(deviceId: deviceId)
        state = .unpaired
        Self.logger.info("Unpaired device \(deviceId)")
    }

    func reset() {
        pairingTimeoutTask?.cancel()
        pairingTimeoutTask = nil
        peerDeviceId = nil
        peerDeviceName = nil
        peerFingerprint = nil
        incomingPairRequest = nil
        if case .paired = state {} else {
            state = .unpaired
        }
    }

    // MARK: - Private

    /// `kind` is "request", "accept", or nil for peers that predate the field.
    private func handlePairAcceptOrRequest(kind: String?) {
        guard let deviceId = peerDeviceId,
              let deviceName = peerDeviceName,
              let fingerprint = peerFingerprint else {
            Self.logger.warning("Pair packet before peer identity — ignoring")
            return
        }

        switch state {
        case .awaitingResponse:
            if kind == "request" {
                guard Self.yieldsOnCrossedRequest(localDeviceId: localDeviceId, peerDeviceId: deviceId) else {
                    Self.logger.info("Crossed pair request from \(deviceName) — we stay the initiator, awaiting their accept")
                    return
                }
                Self.logger.info("Crossed pair request from \(deviceName) — yielding and accepting theirs")
                acceptIncomingPair()
                return
            }
            TrustStore.shared.pin(deviceId: deviceId, fingerprint: fingerprint)
            state = .paired(deviceId: deviceId, deviceName: deviceName)
            incomingPairRequest = nil
            cancelTimeout()
            Self.logger.info("Pair accepted by \(deviceName)")

        case .unpaired:
            if kind == "accept" {
                Self.logger.info("Unsolicited pair accept from \(deviceName) — treating it as a request")
            }
            // First run: an empty trust store already means "auto-pair with whatever
            // we find" (see setPeerIdentity). Staging a confirmation here instead would
            // strand the peer, which is waiting on a reply — and the prompt only renders
            // while the menu-bar panel happens to be open. Confirmation is the gate for
            // the case that actually needs a human: a device we do not already trust
            // arriving when we are paired to something else.
            if TrustStore.shared.isEmpty {
                Self.logger.info("Incoming pair request from \(deviceName) — trust store empty, accepting")
                presentPairConfirmation(deviceId: deviceId, deviceName: deviceName, fingerprint: fingerprint)
                acceptIncomingPair()
                return
            }
            presentPairConfirmation(deviceId: deviceId, deviceName: deviceName, fingerprint: fingerprint)
            Self.logger.info("Incoming pair request from \(deviceName)")

        case .paired:
            if kind == "accept" {
                Self.logger.info("Already paired — ignoring accept from \(deviceName)")
                return
            }
            Self.logger.info("Already paired, re-accepting pair request (peer may have lost trust store)")
            acceptIncomingPair()
        }
    }

    /// Surfaces the peer in the UI (menu bar badge + confirmation sheet). No timeout:
    /// the peer may already have pinned us, and a silently expiring prompt is the
    /// dead end this replaces. Cleared on disconnect by `reset()`.
    private func presentPairConfirmation(deviceId: String, deviceName: String, fingerprint: String) {
        incomingPairRequest = PairRequestInfo(
            deviceId: deviceId,
            deviceName: deviceName,
            fingerprint: fingerprint
        )
        onPairConfirmationPending?(deviceName)
    }

    private func handlePairRejectOrUnpair() {
        switch state {
        case .awaitingResponse:
            state = .unpaired
            cancelTimeout()
            Self.logger.info("Pair request rejected by peer")

        case .paired(let deviceId, _):
            TrustStore.shared.unpin(deviceId: deviceId)
            state = .unpaired
            Self.logger.info("Unpaired by peer")

        case .unpaired:
            incomingPairRequest = nil
            cancelTimeout()
            Self.logger.info("Peer declined or cancelled pairing while unpaired — nothing to do")
        }
    }

    private func startTimeout() {
        cancelTimeout()
        pairingTimeoutTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: Self.timeoutSeconds * 1_000_000_000)
            guard !Task.isCancelled else { return }
            // Local expiry only. Sending a reject here would make a peer that already
            // pinned us drop that pin, which is how the link ended up half-paired.
            Self.logger.info("Pair request timed out with no response — giving up locally")
            if case .awaitingResponse = state {
                state = .unpaired
            }
        }
    }

    private func cancelTimeout() {
        pairingTimeoutTask?.cancel()
        pairingTimeoutTask = nil
    }
}
