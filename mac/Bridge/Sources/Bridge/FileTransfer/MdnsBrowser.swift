import Foundation
import Network
import os

/// Tier 3 mDNS browser. Discovers `_bridge._tcp` services advertised by a peer on
/// the LAN and resolves them to a connectable endpoint. The advertiser
/// (PayloadServer with `advertiseBonjour: true`) deregisters by tearing down its
/// listener after the transfer completes.
///
/// Device/LAN-only — exercised on a real shared Wi-Fi network.
final class MdnsBrowser: @unchecked Sendable {
    private static let logger = Logger(subsystem: "com.bridge.mac", category: "MdnsBrowser")

    private var browser: NWBrowser?
    private var finished = false
    private let queue = DispatchQueue(label: "com.bridge.mac.mdnsbrowser")

    /// Browse for the Bridge transfer service. Calls `onFound` with the first
    /// discovered endpoint, or `onTimeout` if none appears within `timeout`.
    func discover(
        timeout: TimeInterval = 5,
        onFound: @escaping (NWEndpoint) -> Void,
        onTimeout: @escaping () -> Void
    ) {
        let params = NWParameters()
        params.includePeerToPeer = true
        let browser = NWBrowser(
            for: .bonjour(type: BridgeConstants.fileTransferBonjourType, domain: nil),
            using: params
        )
        self.browser = browser

        browser.browseResultsChangedHandler = { [weak self] results, _ in
            guard let self, let first = results.first else { return }
            self.complete { onFound(first.endpoint) }
        }

        queue.asyncAfter(deadline: .now() + timeout) { [weak self] in
            guard let self else { return }
            self.complete { onTimeout() }
        }

        browser.start(queue: queue)
    }

    func stop() {
        browser?.cancel()
        browser = nil
    }

    private func complete(_ action: () -> Void) {
        guard !finished else { return }
        finished = true
        action()
        stop()
    }
}
