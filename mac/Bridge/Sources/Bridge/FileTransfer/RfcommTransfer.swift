import Foundation
import IOBluetooth
import os

/// Tier 2 file transfer over Bluetooth Classic RFCOMM. No network required.
///
/// The Mac connects to the Android device's RFCOMM server (published under the
/// Bridge file-transfer UUID) to receive files, and serves bytes over an opened
/// channel for outbound sends. Channel is opened per transfer and torn down
/// after, keeping the Tier 1 BLE control channel free.
///
/// Device-only — exercised on real MacBook ↔ S25 hardware.
final class RfcommTransfer: NSObject, @unchecked Sendable {
    private static let logger = Logger(subsystem: "com.bridge.mac", category: "RfcommTransfer")

    enum RfcommError: Error {
        case noServiceRecord
        case openFailed(IOReturn)
        case writeFailed(IOReturn)
        case incomplete(received: Int, expected: Int)
        case closedEarly
    }

    private var channel: IOBluetoothRFCOMMChannel?
    private var received = Data()
    private var expected = 0
    private var receiveCompletion: ((Result<Data, Error>) -> Void)?
    private var sendCompletion: ((Result<Int, Error>) -> Void)?
    private let lock = NSLock()

    static func fileTransferSDPUUID() -> IOBluetoothSDPUUID {
        let uuid = UUID(uuidString: BridgeConstants.fileTransferUUIDString)!
        var bytes = [UInt8](repeating: 0, count: 16)
        (uuid as NSUUID).getBytes(&bytes)
        return IOBluetoothSDPUUID(bytes: &bytes, length: 16)
    }

    // MARK: - Client (receive file from phone)

    /// Connect to the Android RFCOMM server and read exactly `expectedBytes`.
    func receive(
        from device: IOBluetoothDevice,
        expectedBytes: Int,
        completion: @escaping (Result<Data, Error>) -> Void
    ) {
        expected = expectedBytes
        received = Data()
        received.reserveCapacity(expectedBytes)
        receiveCompletion = completion

        let uuid = Self.fileTransferSDPUUID()
        guard let record = device.getServiceRecord(for: uuid) else {
            completion(.failure(RfcommError.noServiceRecord))
            return
        }
        var channelID: BluetoothRFCOMMChannelID = 0
        let idStatus = record.getRFCOMMChannelID(&channelID)
        guard idStatus == kIOReturnSuccess else {
            completion(.failure(RfcommError.noServiceRecord))
            return
        }

        var openedChannel: IOBluetoothRFCOMMChannel?
        let status = device.openRFCOMMChannelAsync(&openedChannel, withChannelID: channelID, delegate: self)
        guard status == kIOReturnSuccess else {
            completion(.failure(RfcommError.openFailed(status)))
            return
        }
        channel = openedChannel
    }

    // MARK: - Server (send file to phone)

    /// Serve `payload` over an already-open RFCOMM channel to the phone.
    func send(
        _ payload: Data,
        over channel: IOBluetoothRFCOMMChannel,
        completion: @escaping (Result<Int, Error>) -> Void
    ) {
        self.channel = channel
        sendCompletion = completion
        var bytes = [UInt8](payload)
        let status = channel.writeSync(&bytes, length: UInt16(min(payload.count, Int(UInt16.max))))
        if status == kIOReturnSuccess {
            completion(.success(payload.count))
        } else {
            completion(.failure(RfcommError.writeFailed(status)))
        }
    }

    private func finishReceive(_ result: Result<Data, Error>) {
        lock.lock()
        let completion = receiveCompletion
        receiveCompletion = nil
        lock.unlock()
        channel?.close()
        completion?(result)
    }
}

// MARK: - IOBluetoothRFCOMMChannelDelegate

extension RfcommTransfer: IOBluetoothRFCOMMChannelDelegate {
    func rfcommChannelOpenComplete(_ rfcommChannel: IOBluetoothRFCOMMChannel!, status error: IOReturn) {
        if error != kIOReturnSuccess {
            finishReceive(.failure(RfcommError.openFailed(error)))
        }
    }

    func rfcommChannelData(
        _ rfcommChannel: IOBluetoothRFCOMMChannel!,
        data dataPointer: UnsafeMutableRawPointer!,
        length dataLength: Int
    ) {
        let needed = expected - received.count
        guard needed > 0 else { return }
        let take = min(needed, dataLength)
        received.append(Data(bytes: dataPointer, count: take))
        if received.count >= expected {
            finishReceive(.success(received))
        }
    }

    func rfcommChannelClosed(_ rfcommChannel: IOBluetoothRFCOMMChannel!) {
        if received.count < expected {
            finishReceive(.failure(RfcommError.incomplete(received: received.count, expected: expected)))
        }
    }
}
