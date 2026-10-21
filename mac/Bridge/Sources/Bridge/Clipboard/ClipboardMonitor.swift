import AppKit
import Foundation
import os

actor ClipboardMonitor {

    private static let logger = Logger(subsystem: "com.bridge.mac", category: "ClipboardMonitor")

    private var timer: Timer?
    private var lastChangeCount: Int
    private var lastContent: String?
    private(set) var isRemoteWrite = false
    private(set) var lastClipboardTimestamp: Int64 = 0

    private var sendPacket: ((BridgePacket) -> Void)?

    init() {
        lastChangeCount = NSPasteboard.general.changeCount
        lastContent = NSPasteboard.general.string(forType: .string)
    }

    func configure(sendPacket: @escaping (BridgePacket) -> Void) {
        self.sendPacket = sendPacket
    }

    func start() {
        guard timer == nil else { return }
        let t = Timer(timeInterval: 0.5, repeats: true) { [weak self] _ in
            guard let self else { return }
            Task { await self.pollClipboard() }
        }
        RunLoop.main.add(t, forMode: .common)
        timer = t
        Self.logger.info("Clipboard monitor started")
    }

    func stop() {
        timer?.invalidate()
        timer = nil
    }

    func setRemoteWrite() {
        isRemoteWrite = true
    }

    func handleClipboardPacket(_ packet: BridgePacket) {
        guard let content = packet.body["content"] as? String else { return }

        let current = NSPasteboard.general.string(forType: .string)
        if current == content { return }

        isRemoteWrite = true
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(content, forType: .string)
        lastContent = content
        Self.logger.info("Clipboard updated from remote")
    }

    func handleClipboardConnectPacket(_ packet: BridgePacket) {
        guard let content = packet.body["content"] as? String else { return }
        guard let rawTs = packet.body["timestamp"] else { return }
        let remoteTimestamp: Int64
        if let num = rawTs as? NSNumber {
            remoteTimestamp = num.int64Value
        } else {
            return
        }

        if remoteTimestamp == 0 { return }

        let diff = remoteTimestamp - lastClipboardTimestamp
        if abs(diff) <= 100 { return }
        if diff <= 0 { return }

        let current = NSPasteboard.general.string(forType: .string)
        if current == content { return }

        isRemoteWrite = true
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(content, forType: .string)
        lastContent = content
        lastClipboardTimestamp = remoteTimestamp
        Self.logger.info("Clipboard updated from reconnect (remote ts=\(remoteTimestamp))")
    }

    func makeConnectPacket() -> BridgePacket {
        let content = NSPasteboard.general.string(forType: .string) ?? ""
        return BridgePacket.clipboardConnect(content: content, timestamp: lastClipboardTimestamp)
    }

    private func pollClipboard() {
        let currentCount = NSPasteboard.general.changeCount
        guard currentCount != lastChangeCount else { return }
        lastChangeCount = currentCount

        if isRemoteWrite {
            isRemoteWrite = false
            return
        }

        guard let text = NSPasteboard.general.string(forType: .string),
              !text.isEmpty else { return }

        if text == lastContent { return }
        lastContent = text
        lastClipboardTimestamp = Int64(Date().timeIntervalSince1970 * 1000)

        sendPacket?(BridgePacket.clipboard(content: text))
        Self.logger.info("Clipboard change detected, sent to remote")
    }
}
