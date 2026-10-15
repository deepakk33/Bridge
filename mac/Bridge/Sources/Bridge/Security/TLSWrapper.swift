import Foundation
import Security
import os

final class TLSWrapper: @unchecked Sendable {
    private static let logger = Logger(subsystem: "com.bridge.mac", category: "TLSWrapper")

    private var sslContext: SSLContext?
    private let ioState = TLSIOState()
    private(set) var isHandshakeComplete = false

    func wrap(
        readStream: InputStream,
        writeStream: OutputStream,
        identity: SecIdentity,
        peerFingerprint: String?,
        isServer: Bool
    ) throws {
        ioState.inputStream = readStream
        ioState.outputStream = writeStream

        guard let ctx = SSLCreateContext(nil, isServer ? .serverSide : .clientSide, .streamType) else {
            throw TLSError.contextCreationFailed
        }
        sslContext = ctx

        let connectionRef = Unmanaged.passUnretained(ioState).toOpaque()
        SSLSetConnection(ctx, connectionRef)
        SSLSetIOFuncs(ctx, tlsRead, tlsWrite)

        let certArray = [identity] as CFArray
        let status = SSLSetCertificate(ctx, certArray)
        guard status == errSecSuccess else {
            throw TLSError.setCertificateFailed(status)
        }

        if !isServer {
            SSLSetSessionOption(ctx, .breakOnServerAuth, true)
        }
        SSLSetSessionOption(ctx, .breakOnClientAuth, true)

        var result = SSLHandshake(ctx)

        while result == errSSLWouldBlock {
            result = SSLHandshake(ctx)
        }

        if result == errSSLPeerAuthCompleted {
            if let expected = peerFingerprint {
                try verifyPeer(ctx: ctx, expectedFingerprint: expected)
            }
            result = SSLHandshake(ctx)
            while result == errSSLWouldBlock {
                result = SSLHandshake(ctx)
            }
        }

        guard result == noErr else {
            throw TLSError.handshakeFailed(result)
        }

        isHandshakeComplete = true
        Self.logger.info("TLS handshake complete")
    }

    func read(_ buffer: UnsafeMutablePointer<UInt8>, maxLength: Int) -> Int {
        guard let ctx = sslContext else { return -1 }
        var processed = 0
        let status = SSLRead(ctx, buffer, maxLength, &processed)
        if status != noErr && status != errSSLWouldBlock && status != errSSLClosedGraceful {
            return -1
        }
        return processed
    }

    func write(_ data: Data) -> Int {
        guard let ctx = sslContext else { return -1 }
        var processed = 0
        let status = data.withUnsafeBytes { ptr -> OSStatus in
            guard let base = ptr.baseAddress else { return errSSLInternal }
            return SSLWrite(ctx, base, data.count, &processed)
        }
        if status != noErr && status != errSSLWouldBlock {
            return -1
        }
        return processed
    }

    func close() {
        if let ctx = sslContext {
            SSLClose(ctx)
        }
        sslContext = nil
        isHandshakeComplete = false
    }

    private func verifyPeer(ctx: SSLContext, expectedFingerprint: String) throws {
        var trust: SecTrust?
        SSLCopyPeerTrust(ctx, &trust)
        guard let peerTrust = trust else {
            throw TLSError.noPeerCertificate
        }
        guard let chain = SecTrustCopyCertificateChain(peerTrust) as? [SecCertificate],
              let peerCert = chain.first else {
            throw TLSError.noPeerCertificate
        }
        let actual = CertificateManager.fingerprint(of: peerCert)
        guard actual == expectedFingerprint else {
            throw TLSError.fingerprintMismatch(expected: expectedFingerprint, actual: actual)
        }
        Self.logger.info("Peer fingerprint verified")
    }
}

private final class TLSIOState {
    var inputStream: InputStream?
    var outputStream: OutputStream?
}

private func tlsRead(
    connection: SSLConnectionRef,
    data: UnsafeMutableRawPointer,
    dataLength: UnsafeMutablePointer<Int>
) -> OSStatus {
    let state = Unmanaged<TLSIOState>.fromOpaque(connection).takeUnretainedValue()
    guard let stream = state.inputStream else {
        dataLength.pointee = 0
        return errSSLClosedGraceful
    }
    let buf = data.assumingMemoryBound(to: UInt8.self)
    let read = stream.read(buf, maxLength: dataLength.pointee)
    if read > 0 {
        dataLength.pointee = read
        return noErr
    } else if read == 0 {
        dataLength.pointee = 0
        return errSSLWouldBlock
    } else {
        dataLength.pointee = 0
        return errSSLClosedAbort
    }
}

private func tlsWrite(
    connection: SSLConnectionRef,
    data: UnsafeRawPointer,
    dataLength: UnsafeMutablePointer<Int>
) -> OSStatus {
    let state = Unmanaged<TLSIOState>.fromOpaque(connection).takeUnretainedValue()
    guard let stream = state.outputStream else {
        dataLength.pointee = 0
        return errSSLClosedGraceful
    }
    let buf = data.assumingMemoryBound(to: UInt8.self)
    let written = stream.write(buf, maxLength: dataLength.pointee)
    if written > 0 {
        dataLength.pointee = written
        return noErr
    } else {
        dataLength.pointee = 0
        return errSSLClosedAbort
    }
}

enum TLSError: Error, CustomStringConvertible {
    case contextCreationFailed
    case setCertificateFailed(OSStatus)
    case handshakeFailed(OSStatus)
    case noPeerCertificate
    case fingerprintMismatch(expected: String, actual: String)

    var description: String {
        switch self {
        case .contextCreationFailed: return "Failed to create SSL context"
        case .setCertificateFailed(let s): return "Failed to set certificate: \(s)"
        case .handshakeFailed(let s): return "TLS handshake failed: \(s)"
        case .noPeerCertificate: return "No peer certificate presented"
        case .fingerprintMismatch(let e, let a): return "Fingerprint mismatch: expected \(e), got \(a)"
        }
    }
}
