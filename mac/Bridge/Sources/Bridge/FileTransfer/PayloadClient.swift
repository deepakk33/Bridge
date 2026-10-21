import Foundation
import Network
import os

/// Out-of-band TCP payload client. Connects to the sender's advertised host+port
/// and reads exactly `payloadSize` bytes. Two sinks are supported: an in-memory
/// `Data` (small transfers, tests) and a streaming file sink that writes each
/// chunk straight to disk so a large file never sits fully in memory. Partial
/// transfers are discarded (the partial file is deleted) so no corrupt file is
/// left behind.
final class PayloadClient: @unchecked Sendable {
    private static let logger = Logger(subsystem: "com.bridge.mac", category: "PayloadClient")

    enum PayloadError: Error {
        case connectionFailed(Error)
        case incomplete(received: Int, expected: Int)
        case fileOpenFailed
        case timeout
        case cancelled
    }

    private let endpoint: NWEndpoint
    private let expected: Int
    private let timeout: TimeInterval
    private let queue = DispatchQueue(label: "com.bridge.mac.payloadclient")

    private var connection: NWConnection?
    private var timeoutWork: DispatchWorkItem?
    private var finished = false

    // In-memory sink (default).
    private var received = Data()
    private var dataCompletion: ((Result<Data, Error>) -> Void)?

    // File sink (streaming).
    private var fileHandle: FileHandle?
    private var destURL: URL?
    private var bytesWritten = 0
    private var fileCompletion: ((Result<URL, Error>) -> Void)?
    private var onProgress: ((Int, Int) -> Void)?

    init(host: String, port: Int, expectedBytes: Int, timeout: TimeInterval = 30) {
        let nwPort = NWEndpoint.Port(rawValue: UInt16(port)) ?? .any
        self.endpoint = .hostPort(host: NWEndpoint.Host(host), port: nwPort)
        self.expected = expectedBytes
        self.timeout = timeout
    }

    /// Connect to an already-discovered endpoint (e.g. an mDNS service result).
    init(endpoint: NWEndpoint, expectedBytes: Int, timeout: TimeInterval = 30) {
        self.endpoint = endpoint
        self.expected = expectedBytes
        self.timeout = timeout
    }

    /// Connect, read exactly `expectedBytes` into memory, then close.
    func start(completion: @escaping (Result<Data, Error>) -> Void) {
        self.dataCompletion = completion
        received.reserveCapacity(min(expected, 1 << 20))
        beginConnection()
    }

    /// Connect and stream `expectedBytes` directly to `url` on disk, reporting
    /// progress as bytes arrive. The partial file is removed on failure.
    func start(
        savingTo url: URL,
        onProgress: ((Int, Int) -> Void)? = nil,
        completion: @escaping (Result<URL, Error>) -> Void
    ) {
        FileManager.default.createFile(atPath: url.path, contents: nil)
        guard let handle = try? FileHandle(forWritingTo: url) else {
            completion(.failure(PayloadError.fileOpenFailed))
            return
        }
        self.fileHandle = handle
        self.destURL = url
        self.onProgress = onProgress
        self.fileCompletion = completion
        beginConnection()
    }

    private func beginConnection() {
        let conn = NWConnection(to: endpoint, using: .tcp)
        connection = conn
        conn.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                Self.logger.info("Payload client connected")
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

    private var bytesReceived: Int {
        fileHandle != nil ? bytesWritten : received.count
    }

    private func receiveLoop(_ conn: NWConnection) {
        let remaining = expected - bytesReceived
        guard remaining > 0 else {
            finishSuccess()
            return
        }
        conn.receive(minimumIncompleteLength: 1, maximumLength: remaining) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let error {
                self.finish(.failure(PayloadError.connectionFailed(error)))
                return
            }
            if let data, !data.isEmpty {
                let needed = self.expected - self.bytesReceived
                let chunk = data.prefix(needed)
                if let handle = self.fileHandle {
                    handle.write(chunk)
                    self.bytesWritten += chunk.count
                    self.onProgress?(self.bytesWritten, self.expected)
                } else {
                    self.received.append(chunk)
                }
                self.resetTimeout()
            }
            if self.bytesReceived >= self.expected {
                self.finishSuccess()
                return
            }
            if isComplete {
                self.finish(.failure(PayloadError.incomplete(received: self.bytesReceived, expected: self.expected)))
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

    private func finishSuccess() {
        if let url = destURL {
            try? fileHandle?.close()
            fileHandle = nil
            finishFile(.success(url))
        } else {
            finishData(.success(received))
        }
    }

    private func finish(_ result: Result<Data, Error>) {
        // Failure path shared by both sinks.
        if destURL != nil {
            try? fileHandle?.close()
            fileHandle = nil
            if let url = destURL { try? FileManager.default.removeItem(at: url) }
            if case .failure(let err) = result {
                finishFile(.failure(err))
            }
            return
        }
        finishData(result)
    }

    private func finishData(_ result: Result<Data, Error>) {
        guard !finished else { return }
        finished = true
        teardown()
        let completion = dataCompletion
        dataCompletion = nil
        completion?(result)
    }

    private func finishFile(_ result: Result<URL, Error>) {
        guard !finished else { return }
        finished = true
        teardown()
        let completion = fileCompletion
        fileCompletion = nil
        completion?(result)
    }

    private func teardown() {
        timeoutWork?.cancel()
        timeoutWork = nil
        connection?.cancel()
        connection = nil
    }
}
