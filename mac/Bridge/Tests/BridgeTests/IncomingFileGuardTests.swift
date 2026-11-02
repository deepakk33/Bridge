import XCTest
@testable import Bridge

/// The peer controls the filename and the declared size. Neither may steer where the file lands
/// or how much is committed before anything is checked.
final class IncomingFileGuardTests: XCTestCase {

    // MARK: - shared vectors

    private static var vectorFile: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("protocol/test-vectors/incoming-file-guard.json")
    }

    private func vectors() throws -> [String: Any] {
        let data = try Data(contentsOf: Self.vectorFile)
        return try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    func testSharedVectorsEveryNameReducesAsAndroidReducesIt() throws {
        let names = try XCTUnwrap(try vectors()["names"] as? [[String: Any]])
        for v in names {
            let caseName = v["name"] as? String ?? "?"
            let raw = try XCTUnwrap(v["raw"] as? String)
            let safe = try XCTUnwrap(v["safe"] as? String)
            XCTAssertEqual(FileReceiveController.safeName(raw), safe, "name vector '\(caseName)'")
        }
    }

    func testSharedVectorsBothPlatformsAgreeOnAcceptableSizes() throws {
        let root = try vectors()
        let cap = try XCTUnwrap((root["maxPayloadBytes"] as? NSNumber)?.intValue)
        XCTAssertEqual(cap, FileReceiveController.maxPayloadBytes,
                       "the cap itself must match Android's")
        let sizes = try XCTUnwrap(root["sizes"] as? [[String: Any]])
        for v in sizes {
            let caseName = v["name"] as? String ?? "?"
            let size = try XCTUnwrap((v["size"] as? NSNumber)?.intValue)
            let acceptable = try XCTUnwrap(v["acceptable"] as? Bool)
            XCTAssertEqual(FileReceiveController.isAcceptableSize(size), acceptable,
                           "size vector '\(caseName)'")
        }
    }

    // MARK: - filename

    func testParentReferenceCannotEscapeTheDownloadDirectory() {
        XCTAssertEqual(FileReceiveController.safeName("../../.zshenv"), ".zshenv")
        XCTAssertEqual(FileReceiveController.safeName("../../../etc/passwd"), "passwd")
    }

    func testAbsolutePathIsReducedToItsLastComponent() {
        XCTAssertEqual(FileReceiveController.safeName("/etc/passwd"), "passwd")
    }

    func testNamesThatReduceToNothingGetAFallback() {
        XCTAssertEqual(FileReceiveController.safeName(""), "BridgeFile")
        XCTAssertEqual(FileReceiveController.safeName("/"), "BridgeFile")
        XCTAssertEqual(FileReceiveController.safeName("///"), "BridgeFile")
        XCTAssertEqual(FileReceiveController.safeName(".."), "BridgeFile")
        XCTAssertEqual(FileReceiveController.safeName("."), "BridgeFile")
        XCTAssertEqual(FileReceiveController.safeName("   "), "BridgeFile")
    }

    func testOrdinaryNamesSurviveUnchanged() {
        XCTAssertEqual(FileReceiveController.safeName("report.pdf"), "report.pdf")
        XCTAssertEqual(FileReceiveController.safeName("holiday photo.jpeg"), "holiday photo.jpeg")
    }

    func testSeparatorsTheFilesystemWouldHonourAreNeutralised() {
        // A colon is a path separator to some macOS APIs, and a backslash travels from the peer.
        XCTAssertFalse(FileReceiveController.safeName("a:b.txt").contains(":"))
        XCTAssertEqual(FileReceiveController.safeName("dir\\file.txt"), "file.txt")
    }

    func testAnAbsurdlyLongNameIsBounded() {
        let name = String(repeating: "a", count: 2000) + ".bin"
        let safe = FileReceiveController.safeName(name)
        XCTAssertLessThanOrEqual(safe.utf8.count, 255)
        XCTAssertFalse(safe.isEmpty)
    }

    // MARK: - declared size

    func testAPayloadAboveTheCapIsRefused() {
        XCTAssertFalse(FileReceiveController.isAcceptableSize(FileReceiveController.maxPayloadBytes + 1))
        XCTAssertFalse(FileReceiveController.isAcceptableSize(Int.max))
    }

    func testAPayloadWithinTheCapIsAccepted() {
        XCTAssertTrue(FileReceiveController.isAcceptableSize(1))
        XCTAssertTrue(FileReceiveController.isAcceptableSize(716_800))
        XCTAssertTrue(FileReceiveController.isAcceptableSize(FileReceiveController.maxPayloadBytes))
    }

    func testAnEmptyOrNegativePayloadIsNotAcceptable() {
        XCTAssertFalse(FileReceiveController.isAcceptableSize(0))
        XCTAssertFalse(FileReceiveController.isAcceptableSize(-1))
    }
}
