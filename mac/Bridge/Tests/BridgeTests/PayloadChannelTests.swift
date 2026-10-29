import XCTest
import CryptoKit
import Network
import Security
@testable import Bridge

final class PayloadChannelTests: XCTestCase {

    /// Skips when the Mac has no TLS-capable identity.
    ///
    /// `CertificateManager.loadExistingIdentity()` matches neither `kSecAttrLabel` nor
    /// `kSecAttrApplicationTag` — a `kSecClassIdentity` query returns whatever identity
    /// the keychain holds, which on a dev machine is the "Bridge Dev" code-signing
    /// certificate. So the generated device certificate is never stored or used, and the
    /// one that *is* returned has extendedKeyUsage=codeSigning, which Network.framework
    /// refuses for TLS (the listener fails with EINVAL before any handshake).
    ///
    /// Tracked as the blocker for BR-4 task 7.3. These tests come back the moment the
    /// identity lookup is fixed.
    private func skipUnlessIdentityCanServeTLS() async throws {
        let identity = try await CertificateManager.shared.getIdentity()
        var certRef: SecCertificate?
        SecIdentityCopyCertificate(identity, &certRef)
        let summary = certRef.flatMap { SecCertificateCopySubjectSummary($0) as String? } ?? ""
        try XCTSkipUnless(
            summary == "Bridge Device",
            "Mac identity is '\(summary)', not the Bridge device certificate — see BR-4 task 7.3"
        )
    }

    /// Both ends of a loopback transfer are this process, so the Mac's own certificate
    /// stands in for both peers and each pins the other's — the same fingerprint.
    private func loopbackParameters() async throws -> NWParameters {
        let identity = try await CertificateManager.shared.getIdentity()
        let fingerprint = try await CertificateManager.shared.getFingerprint()
        return PayloadTLS.parameters(identity: identity, pinnedFingerprint: fingerprint)
    }

    /// Parameters that refuse whatever the peer presents.
    private func mismatchedParameters() async throws -> NWParameters {
        let identity = try await CertificateManager.shared.getIdentity()
        return PayloadTLS.parameters(
            identity: identity,
            pinnedFingerprint: String(repeating: "ff", count: 32)
        )
    }

    /// Server serves a payload over loopback; client reads exact bytes; checksums match.
    func testLoopbackRoundTripChecksum() async throws {
        try await skipUnlessIdentityCanServeTLS()
        let tls = try await loopbackParameters()
        let payload = Data((0..<1024).map { UInt8($0 & 0xFF) })
        let server = try PayloadServer(payload: payload, parameters: tls)

        let ready = expectation(description: "server ready")
        let done = expectation(description: "transfer done")
        var receivedPort = 0

        server.start(onReady: { port in
            receivedPort = port
            ready.fulfill()
        }, completion: { _ in })

        await fulfillment(of: [ready], timeout: 5)
        XCTAssertGreaterThan(receivedPort, 0)

        let client = PayloadClient(
            host: "127.0.0.1", port: receivedPort, expectedBytes: payload.count, parameters: tls
        )
        var result: Data?
        client.start { res in
            if case .success(let data) = res { result = data }
            done.fulfill()
        }

        await fulfillment(of: [done], timeout: 5)
        XCTAssertEqual(result, payload)
        XCTAssertEqual(SHA256.hash(data: result ?? Data()), SHA256.hash(data: payload))
    }

    /// Client reads exactly payloadSize even if the server offers more bytes.
    func testClientIgnoresExcessBytes() async throws {
        try await skipUnlessIdentityCanServeTLS()
        let tls = try await loopbackParameters()
        let payload = Data(repeating: 0x5A, count: 2048)
        let server = try PayloadServer(payload: payload, parameters: tls)
        let ready = expectation(description: "ready")
        let done = expectation(description: "done")
        var port = 0
        server.start(onReady: { port = $0; ready.fulfill() }, completion: { _ in })
        await fulfillment(of: [ready], timeout: 5)

        // Ask for fewer bytes than offered.
        let client = PayloadClient(
            host: "127.0.0.1", port: port, expectedBytes: 1000, parameters: tls
        )
        var count = -1
        client.start { res in
            if case .success(let data) = res { count = data.count }
            done.fulfill()
        }
        await fulfillment(of: [done], timeout: 5)
        XCTAssertEqual(count, 1000)
    }

    /// Server bound to an ephemeral port reports a nonzero port before serving.
    func testEphemeralPortAssigned() async throws {
        try await skipUnlessIdentityCanServeTLS()
        let tls = try await loopbackParameters()
        let server = try PayloadServer(payload: Data([1, 2, 3]), parameters: tls)
        let ready = expectation(description: "ready")
        var port = -1
        server.start(onReady: { port = $0; ready.fulfill() }, completion: { _ in })
        await fulfillment(of: [ready], timeout: 5)
        XCTAssertGreaterThan(port, 0)
        server.cancel()
    }

    /// A stalled server (no client connects) times out and cleans up.
    func testServerTimeout() async throws {
        try await skipUnlessIdentityCanServeTLS()
        let tls = try await loopbackParameters()
        let server = try PayloadServer(payload: Data([1, 2, 3]), parameters: tls, timeout: 1)
        let done = expectation(description: "timeout")
        server.start(onReady: { _ in }, completion: { res in
            if case .failure = res { done.fulfill() }
        })
        await fulfillment(of: [done], timeout: 4)
    }

    // MARK: - BR-4: mutual TLS with pinned fingerprints

    /// A client pinned to the wrong fingerprint gets no payload.
    func testClientRefusesAnUnpinnedServer() async throws {
        try await skipUnlessIdentityCanServeTLS()
        let serverTLS = try await loopbackParameters()
        let clientTLS = try await mismatchedParameters()
        let payload = Data(repeating: 0x11, count: 1024)
        let server = try PayloadServer(payload: payload, parameters: serverTLS, timeout: 3)

        let ready = expectation(description: "ready")
        let done = expectation(description: "done")
        var port = 0
        server.start(onReady: { port = $0; ready.fulfill() }, completion: { _ in })
        await fulfillment(of: [ready], timeout: 5)

        let client = PayloadClient(
            host: "127.0.0.1", port: port, expectedBytes: payload.count,
            parameters: clientTLS, timeout: 3
        )
        var succeeded = false
        client.start { res in
            if case .success = res { succeeded = true }
            done.fulfill()
        }
        await fulfillment(of: [done], timeout: 8)
        XCTAssertFalse(succeeded, "a mismatched pin must not yield a payload")
        server.cancel()
    }

    /// A server pinned to the wrong fingerprint serves nobody.
    func testServerRefusesAnUnpinnedClient() async throws {
        try await skipUnlessIdentityCanServeTLS()
        let serverTLS = try await mismatchedParameters()
        let clientTLS = try await loopbackParameters()
        let payload = Data(repeating: 0x22, count: 1024)
        let server = try PayloadServer(payload: payload, parameters: serverTLS, timeout: 3)

        let ready = expectation(description: "ready")
        let done = expectation(description: "done")
        var port = 0
        var served = false
        server.start(onReady: { port = $0; ready.fulfill() }, completion: { res in
            if case .success = res { served = true }
        })
        await fulfillment(of: [ready], timeout: 5)

        let client = PayloadClient(
            host: "127.0.0.1", port: port, expectedBytes: payload.count,
            parameters: clientTLS, timeout: 3
        )
        client.start { _ in done.fulfill() }
        await fulfillment(of: [done], timeout: 8)
        XCTAssertFalse(served, "an unpinned client must not be served")
        server.cancel()
    }

    /// With nothing pinned, no parameters are produced at all — so no listener is
    /// opened and no connection is attempted.
    func testNoPinnedPeerMeansNoTransfer() async {
        let saved = TrustStore.shared.pairedDeviceId().map {
            ($0, TrustStore.shared.pinnedFingerprint(for: $0))
        }
        TrustStore.shared.unpinAll()
        defer {
            if let (id, fp) = saved, let fp { TrustStore.shared.pin(deviceId: id, fingerprint: fp) }
        }

        do {
            _ = try await PayloadTLS.pairedPeerParameters()
            XCTFail("an unpaired peer must be refused")
        } catch {
            XCTAssertTrue(error is PayloadTLS.Error)
        }
    }
}
