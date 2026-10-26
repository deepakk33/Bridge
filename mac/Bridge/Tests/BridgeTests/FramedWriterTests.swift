import XCTest
@testable import Bridge

/// BR-3: a send must report whether the bytes actually went out, so the
/// "Reply could not be delivered" notification can fire for the case it was written for.
final class FramedWriterTests: XCTestCase {

    private func line(_ byteCount: Int) -> Data {
        Data(repeating: 0x61, count: byteCount - 1) + Data([0x0A])
    }

    func testWritesEveryByteWhenStreamAccepts() {
        let data = line(1000)
        var received = Data()
        let written = FramedWriter.write(data) { pointer, count in
            received.append(pointer, count: count)
            return count
        }
        XCTAssertEqual(written, data.count)
        XCTAssertEqual(received, data)
    }

    func testLoopsOverAFixedChunkSize() {
        let data = line(1000)
        var received = Data()
        var calls = 0
        let written = FramedWriter.write(data) { pointer, count in
            calls += 1
            let chunk = min(count, 256)
            received.append(pointer, count: chunk)
            return chunk
        }
        XCTAssertEqual(written, data.count)
        XCTAssertEqual(received, data)
        XCTAssertEqual(calls, 4)
    }

    func testStopsAndReportsShortCountWhenStreamRefuses() {
        let data = line(1000)
        var accepted = 0
        let written = FramedWriter.write(data) { _, count in
            guard accepted < 300 else { return 0 }
            let chunk = min(count, 300 - accepted)
            accepted += chunk
            return chunk
        }
        XCTAssertEqual(written, 300)
        XCTAssertLessThan(written, data.count)
    }

    func testStopsOnWriteError() {
        let written = FramedWriter.write(line(100)) { _, _ in -1 }
        XCTAssertEqual(written, 0)
    }

    func testEmptyDataWritesNothing() {
        var calls = 0
        let written = FramedWriter.write(Data()) { _, _ in
            calls += 1
            return 0
        }
        XCTAssertEqual(written, 0)
        XCTAssertEqual(calls, 0)
    }
}
