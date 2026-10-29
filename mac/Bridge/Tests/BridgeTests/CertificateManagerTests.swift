import XCTest
import Security
@testable import Bridge

/// Regression guard for the identity lookup (BR-4 task 7.6).
///
/// `kSecClassIdentity` queries on macOS ignore `kSecAttrLabel` and
/// `kSecAttrApplicationTag`, so the old lookup returned the app's "Bridge Dev"
/// code-signing identity and the real device certificate was never generated. If that
/// ever comes back, these fail.
final class CertificateManagerTests: XCTestCase {

    private func subjectSummary(of identity: SecIdentity) throws -> String {
        var certificate: SecCertificate?
        let status = SecIdentityCopyCertificate(identity, &certificate)
        XCTAssertEqual(status, errSecSuccess)
        let cert = try XCTUnwrap(certificate)
        return (SecCertificateCopySubjectSummary(cert) as String?) ?? ""
    }

    func testIdentityIsTheBridgeDeviceCertificate() async throws {
        let identity = try await CertificateManager.shared.getIdentity()
        let summary = try subjectSummary(of: identity)
        XCTAssertEqual(summary, "Bridge Device", "got '\(summary)' — the code-signing identity is not a device identity")
    }

    /// The device key is EC P-256 and signs with ECDSA; the code-signing identity is RSA,
    /// so this also catches the wrong identity being returned.
    func testIdentityKeyCanSignForECDSA() async throws {
        let identity = try await CertificateManager.shared.getIdentity()
        var key: SecKey?
        XCTAssertEqual(SecIdentityCopyPrivateKey(identity, &key), errSecSuccess)
        let privateKey = try XCTUnwrap(key)
        XCTAssertTrue(
            SecKeyIsAlgorithmSupported(privateKey, .sign, .ecdsaSignatureMessageX962SHA256),
            "the device key must be able to sign the TLS handshake"
        )
    }

    func testCertificateCarriesTheExtensionsTLSRequires() async throws {
        let certificate = try await CertificateManager.shared.getCertificate()
        let der = SecCertificateCopyData(certificate) as Data

        // extendedKeyUsage OID 2.5.29.37, and the serverAuth / clientAuth OIDs under it.
        XCTAssertTrue(der.contains(subrange: Data([0x55, 0x1D, 0x25])), "no extendedKeyUsage")
        XCTAssertTrue(
            der.contains(subrange: Data([0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x03, 0x01])),
            "no serverAuth — a TLS listener will refuse this certificate"
        )
        XCTAssertTrue(
            der.contains(subrange: Data([0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x03, 0x02])),
            "no clientAuth — the payload client cannot authenticate with this certificate"
        )
        XCTAssertTrue(der.contains(subrange: Data([0x55, 0x1D, 0x0F])), "no keyUsage")
    }

    func testFingerprintIsStableAcrossCalls() async throws {
        let first = try await CertificateManager.shared.getFingerprint()
        let second = try await CertificateManager.shared.getFingerprint()
        XCTAssertEqual(first, second)
        XCTAssertEqual(first.count, 64, "SHA-256 hex")
    }
}

private extension Data {
    func contains(subrange: Data) -> Bool {
        guard !subrange.isEmpty, count >= subrange.count else { return false }
        return indices.dropLast(subrange.count - 1).contains { start in
            self[start..<(start + subrange.count)] == subrange
        }
    }
}
