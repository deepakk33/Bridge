import Foundation
import Network
import UserNotifications
import os

/// Handles an incoming `bridge.share.request` on the Mac: discovers the sender's
/// `_bridge._tcp` service via mDNS, opens the payload channel, reads exactly
/// `payloadSize` bytes, and saves the file to ~/Downloads. Posts a notification
/// on completion.
@MainActor
final class FileReceiveController {
    private static let logger = Logger(subsystem: "com.bridge.mac", category: "FileReceiveController")

    private var browser: MdnsBrowser?
    private var client: PayloadClient?

    /// Begin fetching an incoming file. No-op when there is no payload body.
    /// Prefers a direct `host:port` connection (carried in the share request);
    /// falls back to mDNS discovery when no host is provided.
    func receive(filename: String, payloadSize: Int, port: Int?, host: String?) {
        guard payloadSize > 0 else {
            Self.logger.info("Share request '\(filename)' has no payload; ignoring")
            return
        }

        if let host, let port {
            Self.logger.info("Fetching '\(filename)' (\(payloadSize) bytes) from \(host):\(port)")
            let client = PayloadClient(host: host, port: port, expectedBytes: payloadSize)
            run(client, filename: filename)
            return
        }

        Self.logger.info("Fetching '\(filename)' (\(payloadSize) bytes) via mDNS")
        let browser = MdnsBrowser()
        self.browser = browser
        browser.discover(timeout: 8, onFound: { [weak self] endpoint in
            Task { @MainActor in
                self?.download(from: endpoint, filename: filename, payloadSize: payloadSize)
            }
        }, onTimeout: { [weak self] in
            Task { @MainActor in
                Self.logger.error("mDNS discovery timed out; cannot fetch '\(filename)'")
                self?.browser = nil
            }
        })
    }

    private func download(from endpoint: NWEndpoint, filename: String, payloadSize: Int) {
        let client = PayloadClient(endpoint: endpoint, expectedBytes: payloadSize)
        run(client, filename: filename)
    }

    private func run(_ client: PayloadClient, filename: String) {
        self.client = client
        let downloads = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        let safeName = filename.isEmpty ? "BridgeFile" : filename
        let dest = Self.uniqueURL(downloads.appendingPathComponent(safeName))

        client.start(savingTo: dest, onProgress: { written, total in
            if total > 0, written % (4 * 1024 * 1024) < 65_536 {
                Self.logger.info("Receiving '\(filename)': \(written * 100 / total)%")
            }
        }, completion: { [weak self] result in
            Task { @MainActor in
                guard let self else { return }
                switch result {
                case .success(let url):
                    Self.logger.info("Saved file to \(url.path)")
                    self.postNotification(filename: url.lastPathComponent)
                case .failure(let error):
                    Self.logger.error("Payload download failed for '\(filename)': \(String(describing: error))")
                }
                self.client = nil
                self.browser = nil
            }
        })
    }

    private func postNotification(filename: String) {
        let content = UNMutableNotificationContent()
        content.title = "File received"
        content.body = filename
        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }

    /// Append " (n)" before the extension if a file already exists at `url`.
    static func uniqueURL(_ url: URL) -> URL {
        let fm = FileManager.default
        guard fm.fileExists(atPath: url.path) else { return url }
        let dir = url.deletingLastPathComponent()
        let ext = url.pathExtension
        let base = url.deletingPathExtension().lastPathComponent
        var n = 1
        while true {
            let candidate = dir.appendingPathComponent(ext.isEmpty ? "\(base) (\(n))" : "\(base) (\(n)).\(ext)")
            if !fm.fileExists(atPath: candidate.path) { return candidate }
            n += 1
        }
    }
}
