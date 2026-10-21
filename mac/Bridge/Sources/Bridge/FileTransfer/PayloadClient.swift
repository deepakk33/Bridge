import Foundation
import Network
import os

/// Out-of-band TCP payload client. Connects to the sender's advertised host+port
/// and reads exactly `payloadSize` bytes. Discards partial transfers so a corrupt
/// file is never saved.
final class PayloadClient: @unchecked Sendable {
    private static let logger = Logger(subsystem: "com.bridge.mac", category: "PayloadClient")

    enum PayloadError: Error {
        case connectionFailed(Error)
        case incomplete(received: Int, expected: Int)
        case timeout
        case cancelled
    }

    private let host: NWEndpoint.Host
    private let port: NWEndpoint.Port
    private let expected: Int
    private let timeout: TimeInterval
    private let queue = DispatchQueue(label: "com.bridge.mac.payloadclient")

    private var connection: NWConnection?
    private var received = Data()
    private var timeoutWork: DispatchWorkItem?
    private var finished = false
    private var completion: ((Result<Data, Error>) -> Void)?

    init(host: String, port: Int, expectedBytes: Int, timeout: TimeInterval = 30) {
        self.host = NWEndpoint.Host(host)
        self.port = NWEndpoint.Port(rawValue: UInt16(port)) ?? .any
        self.expected = expectedBytes
        self.timeout = timeout
    }

    /// Connect, read exactly `expectedBytes`, then close. `completion` fires once.
    func start(completion: @escaping (Result<Data, Error>) -> Void) {
        self.completion = completion
        received.reserveCapacity(expected)

        let conn = NWConnection(host: host, port: port, using: .tcp)
        connection = conn
        conn.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                Self.logger.info("Payload client connected to \(self.port.rawValue)")
                self.receiveLoop(conn)
            case .failed(let err):
                self.finish(.failure(PayloadError.connectionFailed(err)))
            case .cancelled:
                if !self.finished {
                    self.finish(.failure(PayloadError.cancelled))
                }
            default:
                break
            }
        }
        armTimeout()
        conn.start(queue: queue)
    }

    func cancel() {
        queue.async { [weak self] in
            self?.finish(.failure(PayloadError.cancelled))
        }
    }

    private func receiveLoop(_ conn: NWConnection) {
        let remaining = expected - received.count
        guard remaining > 0 else {
            finish(.success(received))
            return
        }
        conn.receive(minimumIncompleteLength: 1, maximumLength: remaining) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let error {
                self.finish(.failure(PayloadError.connectionFailed(error)))
                return
            }
            if let data, !data.isEmpty {
                // Read at most `remaining` bytes; ignore any excess.
                let needed = self.expected - self.received.count
                self.received.append(data.prefix(needed))
                self.resetTimeout()
            }
            if self.received.count >= self.expected {
                self.finish(.success(self.received))
                return
            }
            if isComplete {
                // Stream ended before we got all bytes -> discard partial.
                self.finish(.failure(PayloadError.incomplete(received: self.received.count, expected: self.expected)))
                return
            }
            self.receiveLoop(conn)
        }
    }

    private func armTimeout() {
        let work = DispatchWorkItem { [weak self] in
            guard let self else { return }
            Self.logger.warning("Payload client timed out after \(self.timeout)s")
            self.finish(.failure(PayloadError.timeout))
        }
        timeoutWork = work
        queue.asyncAfter(deadline: .now() + timeout, execute: work)
    }

    private func resetTimeout() {
        timeoutWork?.cancel()
        armTimeout()
    }

    private func finish(_ result: Result<Data, Error>) {
        guard !finished else { return }
        finished = true
        timeoutWork?.cancel()
        timeoutWork = nil
        connection?.cancel()
        connection = nil
        let completion = self.completion
        self.completion = nil
        completion?(result)
    }
}
