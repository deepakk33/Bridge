import XCTest
import CryptoKit
@testable import Bridge

final class PayloadChannelTests: XCTestCase {

    /// Server serves a payload over loopback; client reads exact bytes; checksums match.
    func testLoopbackRoundTripChecksum() throws {
        let payload = Data((0..<1024).map { UInt8($0 & 0xFF) })
        let server = try PayloadServer(payload: payload)

        let ready = expectation(description: "server ready")
        let done = expectation(description: "transfer done")
        var receivedPort = 0

        server.start(onReady: { port in
            receivedPort = port
            ready.fulfill()
        }, completion: { _ in })

        wait(for: [ready], timeout: 5)
        XCTAssertGreaterThan(receivedPort, 0)

        let client = PayloadClient(host: "127.0.0.1", port: receivedPort, expectedBytes: payload.count)
        var result: Data?
        client.start { res in
            if case .success(let data) = res { result = data }
            done.fulfill()
        }

        wait(for: [done], timeout: 5)
        XCTAssertEqual(result, payload)
        XCTAssertEqual(SHA256.hash(data: result ?? Data()), SHA256.hash(data: payload))
    }

    /// Client reads exactly payloadSize even if the server offers more bytes.
    func testClientIgnoresExcessBytes() throws {
        let payload = Data(repeating: 0x5A, count: 2048)
        let server = try PayloadServer(payload: payload)
        let ready = expectation(description: "ready")
        let done = expectation(description: "done")
        var port = 0
        server.start(onReady: { port = $0; ready.fulfill() }, completion: { _ in })
        wait(for: [ready], timeout: 5)

        // Ask for fewer bytes than offered.
        let client = PayloadClient(host: "127.0.0.1", port: port, expectedBytes: 1000)
        var count = -1
        client.start { res in
            if case .success(let data) = res { count = data.count }
            done.fulfill()
        }
        wait(for: [done], timeout: 5)
        XCTAssertEqual(count, 1000)
    }

    /// Server bound to an ephemeral port reports a nonzero port before serving.
    func testEphemeralPortAssigned() throws {
        let server = try PayloadServer(payload: Data([1, 2, 3]))
        let ready = expectation(description: "ready")
        var port = -1
        server.start(onReady: { port = $0; ready.fulfill() }, completion: { _ in })
        wait(for: [ready], timeout: 5)
        XCTAssertGreaterThan(port, 0)
        server.cancel()
    }

    /// A stalled server (no client connects) times out and cleans up.
    func testServerTimeout() throws {
        let server = try PayloadServer(payload: Data([1, 2, 3]), timeout: 1)
        let done = expectation(description: "timeout")
        server.start(onReady: { _ in }, completion: { res in
            if case .failure = res { done.fulfill() }
        })
        wait(for: [done], timeout: 4)
    }
}
