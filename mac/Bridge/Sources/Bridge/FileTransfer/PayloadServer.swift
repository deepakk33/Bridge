import Foundation
import Network
import os

/// Out-of-band TCP payload server (KDE Connect payload mechanism).
///
/// Binds an ephemeral port, serves the file bytes to the first connection that
/// arrives, then tears down. Used by both Tier 2 (RFCOMM-equivalent loopback in
/// tests) and Tier 3 (Wi-Fi LAN). Keeps the Tier 1 BLE control channel free.
final class PayloadServer: @unchecked Sendable {
    private static let logger = Logger(subsystem: "com.bridge.mac", category: "PayloadServer")

    enum PayloadError: Error {
        case listenerFailed(Error)
        case noPort
        case timeout
        case cancelled
    }

    private let payload: Data
    private let timeout: TimeInterval
    private let advertiseBonjour: Bool
    private let listener: NWListener
    private let queue = DispatchQueue(label: "com.bridge.mac.payloadserver")

    private var connection: NWConnection?
    private var timeoutWork: DispatchWorkItem?
    private var finished = false
    private var completion: ((Result<Int, Error>) -> Void)?

    /// - Parameters:
    ///   - payload: the exact bytes to serve.
    ///   - timeout: inactivity timeout before aborting (default 30s per spec).
    /// - Parameter advertiseBonjour: when true (Tier 3), publishes the listening
    ///   port as an `_bridge._tcp` mDNS service so a LAN peer can discover it.
    init(payload: Data, timeout: TimeInterval = 30, advertiseBonjour: Bool = false) throws {
        self.payload = payload
        self.timeout = timeout
        self.advertiseBonjour = advertiseBonjour
        self.listener = try NWListener(using: .tcp, on: .any)
        if advertiseBonjour {
            self.listener.service = NWListener.Service(
                name: Host.current().localizedName ?? "Bridge",
                type: BridgeConstants.fileTransferBonjourType
            )
        }
    }

    /// Bind and begin listening. `onReady` reports the OS-assigned ephemeral port
    /// once the socket is bound (call this before advertising the share request).
    /// `completion` fires once with bytes-served or an error.
    func start(
        onReady: @escaping (Int) -> Void,
        completion: @escaping (Result<Int, Error>) -> Void
    ) {
        self.completion = completion

        listener.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                guard let port = self.listener.port?.rawValue else {
                    self.finish(.failure(PayloadError.noPort))
                    return
                }
                Self.logger.info("Payload server ready on port \(port)")
                onReady(Int(port))
            case .failed(let err):
                self.finish(.failure(PayloadError.listenerFailed(err)))
            case .cancelled:
                self.finish(.failure(PayloadError.cancelled))
            default:
                break
            }
        }

        listener.newConnectionHandler = { [weak self] conn in
            self?.serve(conn)
        }

        armTimeout()
        listener.start(queue: queue)
    }

    func cancel() {
        queue.async { [weak self] in
            self?.finish(.failure(PayloadError.cancelled))
        }
    }

    private func serve(_ conn: NWConnection) {
        // Only the first connection is served; ignore any extras.
        guard connection == nil else {
            conn.cancel()
            return
        }
        connection = conn
        conn.start(queue: queue)
        conn.send(content: payload, completion: .contentProcessed { [weak self] error in
            guard let self else { return }
            if let error {
                self.finish(.failure(error))
            } else {
                Self.logger.info("Served \(self.payload.count) bytes")
                // Flush, then close.
                conn.send(content: nil, isComplete: true, completion: .contentProcessed { _ in
                    self.finish(.success(self.payload.count))
                })
            }
        })
    }

    private func armTimeout() {
        let work = DispatchWorkItem { [weak self] in
            guard let self else { return }
            Self.logger.warning("Payload server timed out after \(self.timeout)s")
            self.finish(.failure(PayloadError.timeout))
        }
        timeoutWork = work
        queue.asyncAfter(deadline: .now() + timeout, execute: work)
    }

    private func finish(_ result: Result<Int, Error>) {
        guard !finished else { return }
        finished = true
        timeoutWork?.cancel()
        timeoutWork = nil
        connection?.cancel()
        connection = nil
        listener.cancel()
        let completion = self.completion
        self.completion = nil
        completion?(result)
    }
}
