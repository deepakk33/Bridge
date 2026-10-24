import Foundation

/// A single Bridge protocol packet.
/// Wire format: one JSON object per line, terminated by `\n`.
struct BridgePacket: @unchecked Sendable {
    let id: Int64
    let type: String
    let body: [String: Any]

    static func ping() -> BridgePacket {
        BridgePacket(
            id: Int64(Date().timeIntervalSince1970 * 1000),
            type: "bridge.ping",
            body: [:]
        )
    }

    static func identity(
        deviceId: String,
        deviceName: String,
        deviceType: String,
        protocolVersion: Int,
        certificateFingerprint: String,
        incomingCapabilities: [String],
        outgoingCapabilities: [String]
    ) -> BridgePacket {
        BridgePacket(
            id: Int64(Date().timeIntervalSince1970 * 1000),
            type: "bridge.identity",
            body: [
                "deviceId": deviceId,
                "deviceName": deviceName,
                "deviceType": deviceType,
                "protocolVersion": protocolVersion,
                "certificateFingerprint": certificateFingerprint,
                "incomingCapabilities": incomingCapabilities,
                "outgoingCapabilities": outgoingCapabilities
            ]
        )
    }

    /// `kind` disambiguates the two `pair: true` packets. Peers that predate it
    /// omit the field; both sides still fall back to state-based interpretation.
    static func pairRequest() -> BridgePacket {
        BridgePacket(
            id: Int64(Date().timeIntervalSince1970 * 1000),
            type: "bridge.pair",
            body: ["pair": true, "kind": "request"]
        )
    }

    static func pairAccept() -> BridgePacket {
        BridgePacket(
            id: Int64(Date().timeIntervalSince1970 * 1000),
            type: "bridge.pair",
            body: ["pair": true, "kind": "accept"]
        )
    }

    static func pairReject() -> BridgePacket {
        BridgePacket(
            id: Int64(Date().timeIntervalSince1970 * 1000),
            type: "bridge.pair",
            body: ["pair": false]
        )
    }

    static func notification(
        id notifId: String,
        appName: String,
        title: String,
        text: String,
        actions: [String],
        requestReplyId: String?
    ) -> BridgePacket {
        var body: [String: Any] = [
            "id": notifId,
            "appName": appName,
            "title": title,
            "text": text,
            "actions": actions
        ]
        if let requestReplyId {
            body["requestReplyId"] = requestReplyId
        } else {
            body["requestReplyId"] = NSNull()
        }
        return BridgePacket(
            id: Int64(Date().timeIntervalSince1970 * 1000),
            type: "bridge.notification",
            body: body
        )
    }

    static func notificationReply(requestReplyId: String, message: String) -> BridgePacket {
        BridgePacket(
            id: Int64(Date().timeIntervalSince1970 * 1000),
            type: "bridge.notification.reply",
            body: [
                "requestReplyId": requestReplyId,
                "message": message
            ]
        )
    }

    static func notificationRequest(cancel notifId: String) -> BridgePacket {
        BridgePacket(
            id: Int64(Date().timeIntervalSince1970 * 1000),
            type: "bridge.notification.request",
            body: ["cancel": notifId]
        )
    }

    static func battery(currentCharge: Int, isCharging: Bool) -> BridgePacket {
        BridgePacket(
            id: Int64(Date().timeIntervalSince1970 * 1000),
            type: "bridge.battery",
            body: [
                "currentCharge": currentCharge,
                "isCharging": isCharging
            ]
        )
    }

    static func clipboard(content: String) -> BridgePacket {
        BridgePacket(
            id: Int64(Date().timeIntervalSince1970 * 1000),
            type: "bridge.clipboard",
            body: ["content": content]
        )
    }

    static func clipboardConnect(content: String, timestamp: Int64) -> BridgePacket {
        BridgePacket(
            id: Int64(Date().timeIntervalSince1970 * 1000),
            type: "bridge.clipboard.connect",
            body: [
                "content": content,
                "timestamp": timestamp
            ]
        )
    }

    static func shareRequest(
        filename: String,
        fileSize: Int64,
        payloadSize: Int64,
        port: Int?,
        host: String? = nil
    ) -> BridgePacket {
        var body: [String: Any] = [
            "filename": filename,
            "fileSize": fileSize
        ]
        if payloadSize > 0 {
            body["payloadSize"] = payloadSize
            if let port {
                var info: [String: Any] = ["port": port]
                if let host { info["host"] = host }
                body["payloadTransferInfo"] = info
            }
        }
        return BridgePacket(
            id: Int64(Date().timeIntervalSince1970 * 1000),
            type: "bridge.share.request",
            body: body
        )
    }

    // MARK: - Share request accessors

    /// Byte count of the out-of-band payload (0 when metadata-only).
    var payloadSize: Int64 {
        (body["payloadSize"] as? NSNumber)?.int64Value ?? 0
    }

    /// TCP port the sender is listening on, or nil when there is no payload.
    var payloadPort: Int? {
        guard payloadSize > 0,
              let info = body["payloadTransferInfo"] as? [String: Any],
              let port = (info["port"] as? NSNumber)?.intValue,
              port > 0 else {
            return nil
        }
        return port
    }

    /// True when this share request carries a file body to fetch.
    var hasPayload: Bool {
        payloadSize > 0
    }

    /// Sender IP for the out-of-band payload connection, when provided.
    var payloadHost: String? {
        guard payloadSize > 0,
              let info = body["payloadTransferInfo"] as? [String: Any],
              let host = info["host"] as? String,
              !host.isEmpty else {
            return nil
        }
        return host
    }

    // MARK: - Serialization

    /// Serialize to newline-delimited JSON bytes.
    func serialize() throws -> Data {
        let dict: [String: Any] = [
            "id": id,
            "type": type,
            "body": body
        ]
        guard JSONSerialization.isValidJSONObject(dict) else {
            throw BridgePacketError.invalidBody
        }
        var data = try JSONSerialization.data(withJSONObject: dict, options: [.sortedKeys])
        data.append(contentsOf: [0x0A]) // newline
        return data
    }

    /// Deserialize from a single line of JSON bytes (with or without trailing newline).
    static func deserialize(from data: Data) throws -> BridgePacket {
        let trimmed = data.trimmingNewlines()
        guard !trimmed.isEmpty else {
            throw BridgePacketError.emptyInput
        }

        let obj: Any
        do {
            obj = try JSONSerialization.jsonObject(with: trimmed, options: [])
        } catch {
            throw BridgePacketError.invalidJSON(error)
        }

        guard let dict = obj as? [String: Any] else {
            throw BridgePacketError.notADictionary
        }

        guard let rawID = dict["id"] else {
            throw BridgePacketError.missingField("id")
        }
        guard let idNumber = rawID as? NSNumber else {
            throw BridgePacketError.invalidFieldType("id")
        }
        let id = idNumber.int64Value
        guard id > 0 else {
            throw BridgePacketError.invalidID(id)
        }

        guard let type = dict["type"] as? String else {
            if dict["type"] == nil {
                throw BridgePacketError.missingField("type")
            }
            throw BridgePacketError.invalidFieldType("type")
        }

        guard let body = dict["body"] as? [String: Any] else {
            if dict["body"] == nil {
                throw BridgePacketError.missingField("body")
            }
            throw BridgePacketError.invalidFieldType("body")
        }

        return BridgePacket(id: id, type: type, body: body)
    }
}

// MARK: - Errors

enum BridgePacketError: Error, CustomStringConvertible {
    case emptyInput
    case invalidJSON(Error)
    case notADictionary
    case missingField(String)
    case invalidFieldType(String)
    case invalidID(Int64)
    case invalidBody

    var description: String {
        switch self {
        case .emptyInput:
            return "Empty input"
        case .invalidJSON(let err):
            return "Invalid JSON: \(err.localizedDescription)"
        case .notADictionary:
            return "Packet is not a JSON object"
        case .missingField(let name):
            return "Missing required field: \(name)"
        case .invalidFieldType(let name):
            return "Invalid type for field: \(name)"
        case .invalidID(let id):
            return "Invalid packet ID: \(id) (must be positive)"
        case .invalidBody:
            return "Body is not valid JSON"
        }
    }
}

// MARK: - Data extension

private extension Data {
    func trimmingNewlines() -> Data {
        var end = count
        while end > 0 && self[end - 1] == 0x0A {
            end -= 1
        }
        return self[startIndex..<startIndex.advanced(by: end)]
    }
}
