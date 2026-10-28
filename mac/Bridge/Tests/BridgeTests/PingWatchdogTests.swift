import XCTest
@testable import Bridge

/// BR-3: `stop()` clears `onTimeout`, and `checkWatchdog` called it *after* `stop()`,
/// so the silent-death callback never ran. Everything downstream of it — "Not responding",
/// the restart guidance, the reconnect attempt — was unreachable.
@MainActor
final class PingWatchdogTests: XCTestCase {

    func testTimeoutCallbackFiresAfterSilence() {
        let watchdog = PingWatchdog(pingInterval: 0.05, watchdogTimeout: 0.1)
        let timedOut = expectation(description: "onTimeout fires")

        watchdog.start(sendPing: {}, onTimeout: { timedOut.fulfill() })

        wait(for: [timedOut], timeout: 2.0)
    }

    func testTimeoutDoesNotFireWhilePacketsArrive() {
        let watchdog = PingWatchdog(pingInterval: 0.05, watchdogTimeout: 0.5)
        let timedOut = expectation(description: "onTimeout fires")
        timedOut.isInverted = true

        watchdog.start(sendPing: {}, onTimeout: { timedOut.fulfill() })

        let keepAlive = Timer.scheduledTimer(withTimeInterval: 0.05, repeats: true) { _ in
            MainActor.assumeIsolated { watchdog.packetReceived() }
        }
        defer { keepAlive.invalidate(); watchdog.stop() }

        wait(for: [timedOut], timeout: 0.8)
    }

    func testPingIsSentOnTheInterval() {
        let watchdog = PingWatchdog(pingInterval: 0.05, watchdogTimeout: 10)
        let pinged = expectation(description: "sendPing fires")
        pinged.assertForOverFulfill = false

        watchdog.start(sendPing: { pinged.fulfill() }, onTimeout: {})
        defer { watchdog.stop() }

        wait(for: [pinged], timeout: 2.0)
    }
}
