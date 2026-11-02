import Foundation
import Network
import Security
import os

/// Mutual TLS for the out-of-band payload connection (BR-4).
///
/// This is where the exposure actually is: the payload crosses a shared network and is
/// reachable by any host on it, where the BLE control channel needs physical proximity.
/// It is also the cheap side — the payload connection is a `NWConnection`, so TLS is a
/// parameter rather than a hand-driven handshake.
///
/// Verification is by pinned fingerprint only. No certificate authority, no hostname
/// check and no system trust store takes part in the decision.
enum PayloadTLS {

    enum Error: Swift.Error, CustomStringConvertible {
        case notPaired
        case noLocalIdentity

        var description: String {
            switch self {
            case .notPaired: return "No pinned fingerprint — refusing to transfer"
            case .noLocalIdentity: return "No local certificate — refusing to transfer"
            }
        }
    }

    private static let logger = Logger(subsystem: "com.bridge.mac", category: "PayloadTLS")
    private static let verifyQueue = DispatchQueue(label: "com.bridge.mac.payloadtls")

    /// Parameters presenting `identity` and accepting only `pinnedFingerprint`.
    static func parameters(identity: SecIdentity, pinnedFingerprint: String) -> NWParameters {
        let options = NWProtocolTLS.Options()
        let sec = options.securityProtocolOptions

        if let localIdentity = sec_identity_create(identity) {
            sec_protocol_options_set_local_identity(sec, localIdentity)
        }

        // Both ends present a certificate: pinning one direction would leave the sender
        // serving a file to anyone who connects.
        sec_protocol_options_set_peer_authentication_required(sec, true)

        sec_protocol_options_set_verify_block(sec, { _, trust, complete in
            let secTrust = sec_trust_copy_ref(trust).takeRetainedValue()
            guard let chain = SecTrustCopyCertificateChain(secTrust) as? [SecCertificate],
                  let leaf = chain.first else {
                logger.error("Payload peer presented no certificate")
                complete(false)
                return
            }
            let presented = CertificateManager.fingerprint(of: leaf)
            let matches = presented == pinnedFingerprint
            if !matches {
                logger.error("Payload peer fingerprint \(presented) does not match the pin")
            }
            complete(matches)
        }, verifyQueue)

        return NWParameters(tls: options, tcp: NWProtocolTCP.Options())
    }

    /// Parameters for the single paired peer.
    /// - Throws: `Error.notPaired` when nothing is pinned, so no listener is opened and
    ///   no connection is attempted — an unpaired peer gets no bytes rather than plaintext ones.
    static func pairedPeerParameters() async throws -> NWParameters {
        guard let deviceId = TrustStore.shared.pairedDeviceId(),
              let fingerprint = TrustStore.shared.pinnedFingerprint(for: deviceId) else {
            throw Error.notPaired
        }
        guard let identity = try? await CertificateManager.shared.getIdentity() else {
            throw Error.noLocalIdentity
        }
        return parameters(identity: identity, pinnedFingerprint: fingerprint)
    }
}
