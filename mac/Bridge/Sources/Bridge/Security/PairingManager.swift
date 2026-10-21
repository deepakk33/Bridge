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

    struct PairRequestInfo: Identifiable {
        let id = UUID()
        let deviceId: String
        let deviceName: String
        let fingerprint: String
    }

    private static let logger = Logger(subsystem: "com.bridge.mac", category: "PairingManager")
    private static let timeoutSeconds: UInt64 = 30

    private var sendPacket: ((BridgePacket) -> Void)?
    private var peerDeviceId: String?
    private var peerDeviceName: String?
    private var peerFingerprint: String?
    private var pairingTimeoutTask: Task<Void, Never>?

    func configure(sendPacket: @escaping (BridgePacket) -> Void) {
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
                self.requestPairing()
            }
        }
    }

    func requestPairing() {
        guard let name = peerDeviceName else { return }
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
            handlePairAcceptOrRequest()
        } else {
            handlePairRejectOrUnpair()
        }
    }

    func acceptIncomingPair() {
        guard let deviceId = peerDeviceId,
              let deviceName = peerDeviceName,
              let fingerprint = peerFingerprint else { return }

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

    private func handlePairAcceptOrRequest() {
        switch state {
        case .awaitingResponse:
            guard let deviceId = peerDeviceId,
                  let deviceName = peerDeviceName,
                  let fingerprint = peerFingerprint else { return }
            TrustStore.shared.pin(deviceId: deviceId, fingerprint: fingerprint)
            state = .paired(deviceId: deviceId, deviceName: deviceName)
            cancelTimeout()
            Self.logger.info("Pair accepted by \(deviceName)")

        case .unpaired:
            guard let deviceId = peerDeviceId,
                  let deviceName = peerDeviceName,
                  let fingerprint = peerFingerprint else { return }
            incomingPairRequest = PairRequestInfo(
                deviceId: deviceId,
                deviceName: deviceName,
                fingerprint: fingerprint
            )
            startTimeout()
            Self.logger.info("Incoming pair request from \(deviceName)")

        case .paired:
            Self.logger.info("Already paired, re-accepting pair request (peer may have lost trust store)")
            acceptIncomingPair()
        }
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
        }
    }

    private func startTimeout() {
        cancelTimeout()
        pairingTimeoutTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: Self.timeoutSeconds * 1_000_000_000)
            guard !Task.isCancelled else { return }
            Self.logger.info("Pairing timed out")
            if incomingPairRequest != nil {
                rejectIncomingPair()
            } else if case .awaitingResponse = state {
                state = .unpaired
            }
        }
    }

    private func cancelTimeout() {
        pairingTimeoutTask?.cancel()
        pairingTimeoutTask = nil
    }
}
