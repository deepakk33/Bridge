import Foundation
import UserNotifications
import os

@MainActor
final class NotificationManager: NSObject, ObservableObject {
    static let shared = NotificationManager()

    private static let logger = Logger(subsystem: "com.bridge.mac", category: "NotificationManager")

    private var packetSender: ((BridgePacket) -> Bool)?

    override init() {
        super.init()
        registerCategories()
        UNUserNotificationCenter.current().delegate = self
    }

    func configure(sendPacket: @escaping (BridgePacket) -> Bool) {
        packetSender = sendPacket
    }

    func requestPermission() {
        UNUserNotificationCenter.current().requestAuthorization(
            options: [.alert, .sound, .badge]
        ) { granted, error in
            if let error {
                Self.logger.error("Notification permission error: \(error.localizedDescription)")
            } else {
                Self.logger.info("Notification permission granted: \(granted)")
            }
        }
    }

    private func registerCategories() {
        let replyAction = UNTextInputNotificationAction(
            identifier: "REPLY_ACTION",
            title: "Reply",
            textInputButtonTitle: "Send",
            textInputPlaceholder: "Reply..."
        )

        let replyCategory = UNNotificationCategory(
            identifier: "BRIDGE_REPLY",
            actions: [replyAction],
            intentIdentifiers: [],
            options: .customDismissAction
        )

        let defaultCategory = UNNotificationCategory(
            identifier: "BRIDGE_DEFAULT",
            actions: [],
            intentIdentifiers: [],
            options: .customDismissAction
        )

        UNUserNotificationCenter.current().setNotificationCategories([replyCategory, defaultCategory])
        Self.logger.info("Notification categories registered")
    }

    func handleNotificationPacket(_ packet: BridgePacket) {
        guard packet.type == "bridge.notification" else { return }

        let notifId = packet.body["id"] as? String ?? ""
        let appName = packet.body["appName"] as? String ?? ""
        let title = packet.body["title"] as? String ?? ""
        let text = packet.body["text"] as? String ?? ""
        let requestReplyId = packet.body["requestReplyId"] as? String

        let content = UNMutableNotificationContent()
        content.title = title
        content.subtitle = appName
        content.body = text
        content.categoryIdentifier = requestReplyId != nil ? "BRIDGE_REPLY" : "BRIDGE_DEFAULT"
        // userInfo must be property-list serializable: never store a nil. Boxing
        // `requestReplyId as Any` when nil made every reply-less notification fail
        // to post ("userInfo contains non-property-list values").
        var userInfo: [String: Any] = ["bridgeNotificationId": notifId]
        if let requestReplyId {
            userInfo["requestReplyId"] = requestReplyId
        }
        content.userInfo = userInfo
        content.sound = .default

        // An empty identifier is rejected by UNUserNotificationCenter; fall back.
        let identifier = notifId.isEmpty ? UUID().uuidString : notifId
        let request = UNNotificationRequest(
            identifier: identifier,
            content: content,
            trigger: nil
        )

        UNUserNotificationCenter.current().add(request) { error in
            if let error {
                Self.logger.error("Failed to post notification: \(error.localizedDescription, privacy: .public)")
            } else {
                Self.logger.info("Posted notification \(identifier, privacy: .public)")
            }
        }
    }

    func handleBatteryPacket(_ packet: BridgePacket) {
        guard packet.type == "bridge.battery" else { return }
        let charge = packet.body["currentCharge"] as? Int
        let charging = packet.body["isCharging"] as? Bool
        if let charge, let charging {
            NotificationCenter.default.post(
                name: .bridgeBatteryUpdated,
                object: nil,
                userInfo: ["currentCharge": charge, "isCharging": charging]
            )
        }
    }
}

// MARK: - UNUserNotificationCenterDelegate

extension NotificationManager: UNUserNotificationCenterDelegate {
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let userInfo = response.notification.request.content.userInfo

        if response.actionIdentifier == "REPLY_ACTION",
           let textResponse = response as? UNTextInputNotificationResponse {
            let replyText = textResponse.userText
            guard !replyText.isEmpty else {
                completionHandler()
                return
            }
            if let replyId = userInfo["requestReplyId"] as? String {
                let packet = BridgePacket.notificationReply(
                    requestReplyId: replyId,
                    message: replyText
                )
                Task { @MainActor in
                    let sent = self.packetSender?(packet) ?? false
                    if !sent {
                        Self.logger.warning("Reply failed: channel not available")
                        self.postDeliveryFailure()
                    }
                }
            }
        } else if response.actionIdentifier == UNNotificationDismissActionIdentifier {
            if let notifId = userInfo["bridgeNotificationId"] as? String {
                let packet = BridgePacket.notificationRequest(cancel: notifId)
                Task { @MainActor in
                    _ = self.packetSender?(packet)
                }
            }
        }

        completionHandler()
    }

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound])
    }

    @MainActor
    func postDeliveryFailure() {
        let content = UNMutableNotificationContent()
        content.title = "Reply could not be delivered"
        content.body = "Phone is out of range."
        content.sound = .default

        let request = UNNotificationRequest(
            identifier: "bridge-reply-failure-\(Date().timeIntervalSince1970)",
            content: content,
            trigger: nil
        )
        UNUserNotificationCenter.current().add(request)
    }
}

extension Notification.Name {
    static let bridgeBatteryUpdated = Notification.Name("bridgeBatteryUpdated")
    static let bridgeDisconnected = Notification.Name("bridgeDisconnected")
    static let bridgeShareRequestReceived = Notification.Name("bridgeShareRequestReceived")
}
