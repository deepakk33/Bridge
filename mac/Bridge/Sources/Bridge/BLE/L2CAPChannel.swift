@preconcurrency import CoreBluetooth
import Foundation
import os

/// Wraps a CBL2CAPChannel, handling stream I/O and newline-delimited JSON framing.
final class L2CAPChannel: NSObject, @unchecked Sendable {
    private let channel: CBL2CAPChannel
    private var inputStream: InputStream?
    private var outputStream: OutputStream?
    private var readBuffer = Data()
    /// Tail of a line the stream stopped accepting. Held so the peer never sees a
    /// half-written line, flushed on the next space-available event.
    private var pendingWrite = Data()

    /// Called on the main actor when a complete packet is received.
    var onPacketReceived: (@Sendable (BridgePacket) -> Void)?

    // Throughput tracking
    private var bytesSentInWindow: Int = 0
    private var windowStart: Date = Date()
    private static let throughputWindowSeconds: TimeInterval = 5.0

    private static let logger = Logger(
        subsystem: "com.bridge.mac",
        category: "L2CAPChannel"
    )

    private static let bufferSize = 4096

    init(channel: CBL2CAPChannel) {
        self.channel = channel
        super.init()
        setupStreams()
    }

    deinit {
        close()
    }

    // MARK: - Public

    /// Send a packet over the L2CAP channel.
    /// - Returns: true only when every byte of the line reached the output stream.
    @discardableResult
    func send(_ packet: BridgePacket) -> Bool {
        do {
            let data = try packet.serialize()
            return writeToStream(data)
        } catch {
            Self.logger.error("Failed to serialize packet: \(error.localizedDescription)")
            return false
        }
    }

    /// Send 50KB of JSON lines and measure throughput.
    func runThroughputTest() {
        let targetBytes = 50 * 1024
        var totalSent = 0
        let start = Date()

        Self.logger.info("Throughput test: sending ~50KB of JSON lines...")

        while totalSent < targetBytes {
            let padding = String(repeating: "x", count: 200)
            let packet = BridgePacket(
                id: Int64(Date().timeIntervalSince1970 * 1000),
                type: "bridge.ping",
                body: ["data": padding]
            )
            do {
                let data = try packet.serialize()
                _ = writeToStream(data)
                totalSent += data.count
            } catch {
                Self.logger.error("Throughput test serialize error: \(error.localizedDescription)")
                break
            }
        }

        let elapsed = Date().timeIntervalSince(start)
        let kbPerSec = Double(totalSent) / 1024.0 / max(elapsed, 0.001)
        Self.logger.info("Throughput test complete: \(totalSent) bytes in \(String(format: "%.3f", elapsed))s = \(String(format: "%.1f", kbPerSec)) KB/s")
    }

    /// Close the channel and its streams.
    func close() {
        inputStream?.close()
        inputStream?.remove(from: .main, forMode: .default)
        inputStream?.delegate = nil
        inputStream = nil

        outputStream?.close()
        outputStream?.remove(from: .main, forMode: .default)
        outputStream?.delegate = nil
        outputStream = nil

        readBuffer.removeAll()
        pendingWrite.removeAll()
    }

    // MARK: - Private

    private func setupStreams() {
        inputStream = channel.inputStream
        outputStream = channel.outputStream

        inputStream?.delegate = self
        outputStream?.delegate = self

        inputStream?.schedule(in: .main, forMode: .default)
        outputStream?.schedule(in: .main, forMode: .default)

        inputStream?.open()
        outputStream?.open()

        Self.logger.info("Streams opened")
    }

    private func writeToStream(_ data: Data) -> Bool {
        guard let outputStream else {
            Self.logger.warning("Output stream not available for writing")
            return false
        }

        // Writing ahead of a queued tail would interleave two lines, so queue behind it.
        guard pendingWrite.isEmpty else {
            pendingWrite.append(data)
            Self.logger.warning("Output stream backed up; queued \(data.count) bytes")
            return false
        }

        let written = FramedWriter.write(data) { pointer, count in
            guard outputStream.hasSpaceAvailable else { return 0 }
            return outputStream.write(pointer, maxLength: count)
        }

        if written > 0 {
            trackThroughput(bytes: written)
        }
        if written == data.count {
            return true
        }

        if let error = outputStream.streamError {
            Self.logger.error("Stream write error: \(error.localizedDescription)")
        }
        pendingWrite = Data(data.dropFirst(max(written, 0)))
        Self.logger.warning("Partial write: \(max(written, 0))/\(data.count) bytes, remainder queued")
        return false
    }

    private func flushPendingWrite() {
        guard !pendingWrite.isEmpty, let outputStream else { return }
        let data = pendingWrite
        let written = FramedWriter.write(data) { pointer, count in
            guard outputStream.hasSpaceAvailable else { return 0 }
            return outputStream.write(pointer, maxLength: count)
        }
        if written > 0 {
            trackThroughput(bytes: written)
        }
        pendingWrite = written == data.count ? Data() : Data(data.dropFirst(max(written, 0)))
    }

    private func readFromStream() {
        guard let inputStream else { return }
        var buffer = [UInt8](repeating: 0, count: Self.bufferSize)

        while inputStream.hasBytesAvailable {
            let bytesRead = inputStream.read(&buffer, maxLength: Self.bufferSize)
            if bytesRead > 0 {
                readBuffer.append(contentsOf: buffer[0..<bytesRead])
                Self.logger.info("Read \(bytesRead) bytes, buffer now \(self.readBuffer.count)")
                processBuffer()
            } else if bytesRead < 0 {
                Self.logger.error("Stream read error: \(inputStream.streamError?.localizedDescription ?? "unknown")")
                break
            }
        }
    }

    /// Split the read buffer on newlines and parse each complete line as a packet.
    private func processBuffer() {
        let newline: UInt8 = 0x0A

        while let newlineIndex = readBuffer.firstIndex(of: newline) {
            let lineData = readBuffer[readBuffer.startIndex..<newlineIndex]
            readBuffer = Data(readBuffer[(newlineIndex + 1)...])

            guard !lineData.isEmpty else { continue }

            do {
                let packet = try BridgePacket.deserialize(from: Data(lineData))
                Self.logger.info("Parsed packet: \(packet.type)")
                onPacketReceived?(packet)
            } catch {
                let preview = String(data: Data(lineData.prefix(80)), encoding: .utf8) ?? "binary"
                Self.logger.warning("Failed to parse packet (\(lineData.count) bytes): \(error.localizedDescription) preview: \(preview)")
            }
        }
    }

    // MARK: - Throughput

    private func trackThroughput(bytes: Int) {
        bytesSentInWindow += bytes
        let elapsed = Date().timeIntervalSince(windowStart)
        if elapsed >= Self.throughputWindowSeconds {
            let kbPerSec = Double(bytesSentInWindow) / 1024.0 / elapsed
            Self.logger.info("Throughput: \(String(format: "%.1f", kbPerSec)) KB/s (last \(String(format: "%.1f", elapsed))s)")
            bytesSentInWindow = 0
            windowStart = Date()
        }
    }
}

// MARK: - StreamDelegate

extension L2CAPChannel: StreamDelegate {
    nonisolated func stream(_ aStream: Stream, handle eventCode: Stream.Event) {
        // StreamDelegate callbacks come on the run loop the stream is scheduled on (main).
        switch eventCode {
        case .hasBytesAvailable:
            readFromStream()
        case .hasSpaceAvailable:
            flushPendingWrite()
        case .errorOccurred:
            Self.logger.error("Stream error: \(aStream.streamError?.localizedDescription ?? "unknown")")
        case .endEncountered:
            Self.logger.info("Stream ended")
            close()
        default:
            break
        }
    }
}

/// The byte-accounting half of the channel's write loop, kept free of Core Bluetooth
/// so it can be tested directly. A stream that accepts fewer bytes than asked is
/// normal; a stream that accepts none means it has stopped taking data.
enum FramedWriter {
    /// - Returns: how many bytes `writeChunk` accepted in total; less than
    ///   `data.count` means the stream stopped before the line was complete.
    static func write(_ data: Data, using writeChunk: (UnsafePointer<UInt8>, Int) -> Int) -> Int {
        guard !data.isEmpty else { return 0 }
        return data.withUnsafeBytes { raw -> Int in
            guard let base = raw.baseAddress?.assumingMemoryBound(to: UInt8.self) else { return 0 }
            var offset = 0
            while offset < data.count {
                let written = writeChunk(base + offset, data.count - offset)
                if written <= 0 { break }
                offset += written
            }
            return offset
        }
    }
}
