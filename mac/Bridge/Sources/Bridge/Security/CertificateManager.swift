import Foundation
import Security
import CryptoKit
import os

actor CertificateManager {
    static let shared = CertificateManager()

    private static let logger = Logger(subsystem: "com.bridge.mac", category: "CertificateManager")
    private static let keychainLabel = "com.bridge.mac.certificate"
    private static let keychainTag = "com.bridge.mac.key".data(using: .utf8)!

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

    private func loadExistingIdentity() -> SecIdentity? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassIdentity,
            kSecAttrLabel as String: Self.keychainLabel,
            kSecReturnRef as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess else { return nil }
        return (result as! SecIdentity)
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

        guard let identity = loadExistingIdentity() else {
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
        tbs.append(contentsOf: derInteger(Data([0x01]))) // serial number
        tbs.append(contentsOf: ecdsaWithSHA256AlgorithmIdentifier()) // signature algorithm
        tbs.append(contentsOf: derSequence(content: derSet(content: derSequence(content:
            derOID([0x55, 0x04, 0x03]) + derUTF8String("Bridge Device")
        )))) // issuer: CN=Bridge Device
        tbs.append(contentsOf: validity()) // validity: now to +10 years
        tbs.append(contentsOf: derSequence(content: derSet(content: derSequence(content:
            derOID([0x55, 0x04, 0x03]) + derUTF8String("Bridge Device")
        )))) // subject = issuer (self-signed)
        tbs.append(contentsOf: subjectPublicKeyInfo(pubKeyData: pubKeyData))

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
