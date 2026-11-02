import Foundation
import Security
import CryptoKit
import os

actor CertificateManager {
    static let shared = CertificateManager()

    private static let logger = Logger(subsystem: "com.bridge.mac", category: "CertificateManager")
    // v2: the original certificate carried no X.509 extensions, which Network.framework
    // rejects outright — a TLS listener built on it fails to start with EINVAL before any
    // handshake. Bumping the label generates a conforming certificate instead of reusing
    // the unusable one. The fingerprint changes, so paired devices must pair again.
    /// The certificate's common name, and the only reliable way to find it again:
    /// macOS derives a certificate's `kSecAttrLabel` from its subject and ignores the
    /// label passed to `SecItemAdd`.
    static let certificateCommonName = "Bridge Device"
    private static let keychainLabel = "com.bridge.mac.certificate.v2"
    private static let keychainTag = "com.bridge.mac.key.v2".data(using: .utf8)!

    private var cachedIdentity: SecIdentity?
    private var cachedFingerprint: String?

    func ensureCertificate() throws {
        if cachedIdentity != nil { return }
        if let identity = loadExistingIdentity() {
            cachedIdentity = identity
            cachedFingerprint = try computeFingerprint(for: identity)
            Self.logger.info("Loaded existing certificate")
            return
        }
        let identity = try generateAndStoreIdentity()
        cachedIdentity = identity
        cachedFingerprint = try computeFingerprint(for: identity)
        Self.logger.info("Generated new certificate")
    }

    func getIdentity() throws -> SecIdentity {
        try ensureCertificate()
        guard let identity = cachedIdentity else {
            throw CertificateError.noCertificate
        }
        return identity
    }

    func getFingerprint() throws -> String {
        try ensureCertificate()
        guard let fp = cachedFingerprint else {
            throw CertificateError.noCertificate
        }
        return fp
    }

    func getCertificate() throws -> SecCertificate {
        let identity = try getIdentity()
        var cert: SecCertificate?
        let status = SecIdentityCopyCertificate(identity, &cert)
        guard status == errSecSuccess, let certificate = cert else {
            throw CertificateError.certificateExtractFailed(status)
        }
        return certificate
    }

    // MARK: - Private

    /// Finds the Bridge device identity, or nil when it has not been generated yet.
    ///
    /// Deliberately not a `kSecClassIdentity` query: on macOS that class ignores both
    /// `kSecAttrLabel` and `kSecAttrApplicationTag`, so it returns whichever identity the
    /// keychain happens to hold — on a development machine, the app's own "Bridge Dev"
    /// code-signing identity. That made this function always succeed, so the device
    /// certificate was never generated and the fingerprint Bridge pinned was the
    /// code-signing certificate's. Certificate-class queries do respect the label, so
    /// look the certificate up and let Security pair it with its private key.
    private func loadExistingIdentity() -> SecIdentity? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassCertificate,
            kSecReturnRef as String: true,
            kSecMatchLimit as String: kSecMatchLimitAll
        ]
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let certificates = result as? [SecCertificate] else {
            return nil
        }

        // Matching on the subject, and then on whether the private key is actually
        // present, is the only dependable test. A stale certificate from an earlier run
        // has no key left, so it fails the second step and is skipped.
        for certificate in certificates {
            guard (SecCertificateCopySubjectSummary(certificate) as String?) == Self.certificateCommonName
            else { continue }
            var identity: SecIdentity?
            if SecIdentityCreateWithCertificate(nil, certificate, &identity) == errSecSuccess,
               let identity {
                return identity
            }
        }
        return nil
    }

    private func generateAndStoreIdentity() throws -> SecIdentity {
        deleteExisting()

        let privateKey = try generateKeyPair()
        guard let publicKey = SecKeyCopyPublicKey(privateKey) else {
            throw CertificateError.publicKeyExtractFailed
        }

        let certData = try buildSelfSignedCertDER(privateKey: privateKey, publicKey: publicKey)
        guard let certificate = SecCertificateCreateWithData(nil, certData as CFData) else {
            throw CertificateError.certGenerationFailed
        }

        let addCertQuery: [String: Any] = [
            kSecClass as String: kSecClassCertificate,
            kSecValueRef as String: certificate,
            kSecAttrLabel as String: Self.keychainLabel
        ]
        let certStatus = SecItemAdd(addCertQuery as CFDictionary, nil)
        guard certStatus == errSecSuccess || certStatus == errSecDuplicateItem else {
            throw CertificateError.keychainStoreFailed(certStatus)
        }
        Self.logger.info("Stored Bridge certificate (status \(certStatus))")

        guard let identity = loadExistingIdentity() else {
            // Both halves are in the keychain but Security would not pair them. Say which
            // half is missing rather than leaving a bare "not found".
            var certRes: CFTypeRef?
            let certFind = SecItemCopyMatching([
                kSecClass as String: kSecClassCertificate,
                kSecAttrLabel as String: Self.keychainLabel,
                kSecReturnRef as String: true,
                kSecMatchLimit as String: kSecMatchLimitOne
            ] as CFDictionary, &certRes)
            var keyRes: CFTypeRef?
            let keyFind = SecItemCopyMatching([
                kSecClass as String: kSecClassKey,
                kSecAttrApplicationTag as String: Self.keychainTag,
                kSecReturnRef as String: true,
                kSecMatchLimit as String: kSecMatchLimitOne
            ] as CFDictionary, &keyRes)
            Self.logger.error(
                "Identity pairing failed — certificate lookup \(certFind), key lookup \(keyFind)"
            )
            throw CertificateError.identityNotFoundAfterCreation
        }
        return identity
    }

    private func generateKeyPair() throws -> SecKey {
        let keyParams: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits as String: 256,
            kSecAttrIsPermanent as String: true,
            kSecAttrLabel as String: Self.keychainLabel,
            kSecAttrApplicationTag as String: Self.keychainTag
        ]

        var error: Unmanaged<CFError>?
        guard let privateKey = SecKeyCreateRandomKey(keyParams as CFDictionary, &error) else {
            throw CertificateError.keyGenerationFailed(error?.takeRetainedValue())
        }
        return privateKey
    }

    private func deleteExisting() {
        let keyQuery: [String: Any] = [
            kSecClass as String: kSecClassKey,
            kSecAttrLabel as String: Self.keychainLabel,
            kSecAttrApplicationTag as String: Self.keychainTag
        ]
        SecItemDelete(keyQuery as CFDictionary)

        let certQuery: [String: Any] = [
            kSecClass as String: kSecClassCertificate,
            kSecAttrLabel as String: Self.keychainLabel
        ]
        SecItemDelete(certQuery as CFDictionary)
    }

    private func buildSelfSignedCertDER(privateKey: SecKey, publicKey: SecKey) throws -> Data {
        guard let pubKeyData = SecKeyCopyExternalRepresentation(publicKey, nil) as Data? else {
            throw CertificateError.publicKeyExtractFailed
        }

        var cert = Data()

        // Build TBSCertificate
        var tbs = Data()
        tbs.append(contentsOf: derExplicit(tag: 0, content: derInteger(Data([0x02])))) // version v3
        tbs.append(contentsOf: derInteger(randomSerialNumber())) // serial number
        tbs.append(contentsOf: ecdsaWithSHA256AlgorithmIdentifier()) // signature algorithm
        tbs.append(contentsOf: derSequence(content: derSet(content: derSequence(content:
            derOID([0x55, 0x04, 0x03]) + derUTF8String(Self.certificateCommonName)
        )))) // issuer: CN=Bridge Device
        tbs.append(contentsOf: validity()) // validity: now to +10 years
        tbs.append(contentsOf: derSequence(content: derSet(content: derSequence(content:
            derOID([0x55, 0x04, 0x03]) + derUTF8String(Self.certificateCommonName)
        )))) // subject = issuer (self-signed)
        tbs.append(contentsOf: subjectPublicKeyInfo(pubKeyData: pubKeyData))
        tbs.append(contentsOf: v3Extensions())

        let tbsSequence = derSequence(content: tbs)

        // Sign the TBSCertificate
        var signError: Unmanaged<CFError>?
        guard let signature = SecKeyCreateSignature(
            privateKey,
            .ecdsaSignatureMessageX962SHA256,
            tbsSequence as CFData,
            &signError
        ) as Data? else {
            throw CertificateError.certGenerationFailed
        }

        // Build final Certificate
        cert.append(contentsOf: tbsSequence)
        cert.append(contentsOf: ecdsaWithSHA256AlgorithmIdentifier())
        cert.append(contentsOf: derBitString(signature))

        return derSequence(content: cert)
    }

    // MARK: - ASN.1 DER Helpers

    private func derLength(_ length: Int) -> Data {
        if length < 0x80 {
            return Data([UInt8(length)])
        } else if length < 0x100 {
            return Data([0x81, UInt8(length)])
        } else {
            return Data([0x82, UInt8(length >> 8), UInt8(length & 0xFF)])
        }
    }

    private func derTag(_ tag: UInt8, _ content: Data) -> Data {
        var result = Data([tag])
        result.append(contentsOf: derLength(content.count))
        result.append(content)
        return result
    }

    private func derSequence(content: Data) -> Data { derTag(0x30, content) }
    private func derSet(content: Data) -> Data { derTag(0x31, content) }
    private func derBitString(_ data: Data) -> Data { derTag(0x03, Data([0x00]) + data) }
    private func derOctetString(_ data: Data) -> Data { derTag(0x04, data) }
    private func derUTF8String(_ str: String) -> Data { derTag(0x0C, Data(str.utf8)) }

    /// macOS keys certificate uniqueness on issuer + serial, and both are ours. A fixed
    /// serial meant every regenerated certificate collided with the first one ever made:
    /// `SecItemAdd` returned `errSecDuplicateItem`, the new certificate was silently not
    /// stored, and the lookup that followed found nothing.
    private func randomSerialNumber() -> Data {
        var bytes = [UInt8](repeating: 0, count: 16)
        if SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) != errSecSuccess {
            bytes = (0..<16).map { _ in UInt8.random(in: 0...255) }
        }
        // Keep it positive: derInteger pads a leading high bit, but a leading zero byte
        // would then be a non-minimal encoding.
        bytes[0] &= 0x7F
        if bytes[0] == 0 { bytes[0] = 1 }
        return Data(bytes)
    }

    private func derBoolean(_ value: Bool) -> Data { derTag(0x01, Data([value ? 0xFF : 0x00])) }

    /// The v3 extensions a modern TLS stack requires before it will use a certificate.
    /// Without these the identity is valid as a certificate and useless as a TLS peer.
    private func v3Extensions() -> Data {
        // basicConstraints (2.5.29.19), critical: an empty SEQUENCE means CA:FALSE.
        let basicConstraints = derSequence(content:
            derOID([0x55, 0x1D, 0x13]) + derBoolean(true) +
            derOctetString(derSequence(content: Data()))
        )

        // keyUsage (2.5.29.15), critical: digitalSignature only — the long-term key signs
        // the ephemeral ECDHE parameters, it never encrypts or agrees directly.
        // BIT STRING with 7 unused bits and bit 0 set.
        let keyUsage = derSequence(content:
            derOID([0x55, 0x1D, 0x0F]) + derBoolean(true) +
            derOctetString(derTag(0x03, Data([0x07, 0x80])))
        )

        // extendedKeyUsage (2.5.29.37): both, because each device is the payload server
        // for files it sends and the client for files it receives.
        let serverAuth: [UInt8] = [0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x03, 0x01]
        let clientAuth: [UInt8] = [0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x03, 0x02]
        let extendedKeyUsage = derSequence(content:
            derOID([0x55, 0x1D, 0x25]) +
            derOctetString(derSequence(content: derOID(serverAuth) + derOID(clientAuth)))
        )

        // subjectAltName (2.5.29.17): dNSName [2]. The pinned-fingerprint verify block
        // replaces hostname checking, but a certificate with no SAN is malformed to some
        // stacks and costs nothing to supply.
        let subjectAltName = derSequence(content:
            derOID([0x55, 0x1D, 0x11]) +
            derOctetString(derSequence(content: derTag(0x82, Data("bridge.local".utf8))))
        )

        return derExplicit(tag: 3, content: derSequence(
            content: basicConstraints + keyUsage + extendedKeyUsage + subjectAltName
        ))
    }

    private func derInteger(_ data: Data) -> Data {
        var padded = data
        if let first = padded.first, first >= 0x80 {
            padded.insert(0x00, at: 0)
        }
        return derTag(0x02, padded)
    }

    private func derOID(_ oid: [UInt8]) -> Data {
        return derTag(0x06, Data(oid))
    }

    private func derExplicit(tag: Int, content: Data) -> Data {
        return derTag(UInt8(0xA0 | tag), content)
    }

    private func ecdsaWithSHA256AlgorithmIdentifier() -> Data {
        // OID 1.2.840.10045.4.3.2 = ecdsaWithSHA256
        let oid: [UInt8] = [0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x04, 0x03, 0x02]
        return derSequence(content: derOID(oid))
    }

    private func validity() -> Data {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyMMddHHmmss'Z'"
        formatter.timeZone = TimeZone(abbreviation: "UTC")

        let notBefore = formatter.string(from: Date())
        let notAfter = formatter.string(from: Calendar.current.date(
            byAdding: .year, value: 10, to: Date()
        )!)

        return derSequence(content:
            derTag(0x17, Data(notBefore.utf8)) + // UTCTime
            derTag(0x17, Data(notAfter.utf8))    // UTCTime
        )
    }

    private func subjectPublicKeyInfo(pubKeyData: Data) -> Data {
        // EC public key algorithm identifier
        // OID 1.2.840.10045.2.1 = ecPublicKey
        let ecKeyOID: [UInt8] = [0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x02, 0x01]
        // OID 1.2.840.10045.3.1.7 = prime256v1
        let curveOID: [UInt8] = [0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x03, 0x01, 0x07]

        let algorithm = derSequence(content: derOID(ecKeyOID) + derOID(curveOID))
        return derSequence(content: algorithm + derBitString(pubKeyData))
    }

    private func computeFingerprint(for identity: SecIdentity) throws -> String {
        var cert: SecCertificate?
        let status = SecIdentityCopyCertificate(identity, &cert)
        guard status == errSecSuccess, let certificate = cert else {
            throw CertificateError.certificateExtractFailed(status)
        }
        return Self.fingerprint(of: certificate)
    }

    static func fingerprint(of certificate: SecCertificate) -> String {
        let data = SecCertificateCopyData(certificate) as Data
        let hash = SHA256.hash(data: data)
        return hash.map { String(format: "%02x", $0) }.joined()
    }
}

enum CertificateError: Error, CustomStringConvertible {
    case noCertificate
    case keyGenerationFailed(CFError?)
    case publicKeyExtractFailed
    case certGenerationFailed
    case keychainStoreFailed(OSStatus)
    case certificateExtractFailed(OSStatus)
    case identityNotFoundAfterCreation

    var description: String {
        switch self {
        case .noCertificate: return "No certificate available"
        case .keyGenerationFailed(let e): return "Key generation failed: \(String(describing: e))"
        case .publicKeyExtractFailed: return "Could not extract public key"
        case .certGenerationFailed: return "Certificate generation failed"
        case .keychainStoreFailed(let s): return "Keychain store failed: \(s)"
        case .certificateExtractFailed(let s): return "Certificate extract failed: \(s)"
        case .identityNotFoundAfterCreation: return "Identity not found after creation"
        }
    }
}
