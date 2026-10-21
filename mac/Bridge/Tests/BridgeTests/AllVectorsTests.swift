import XCTest
@testable import Bridge

/// Cross-platform guarantee (PRD: "shared test vectors"): every `valid` packet in
/// protocol/test-vectors parses on the Mac side. The Android suite runs the same
/// sweep, so a vector that parses on one platform parses on both.
final class AllVectorsTests: XCTestCase {

    private static var testVectorsDir: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("protocol/test-vectors")
    }

    func testEveryValidVectorParses() throws {
        let fm = FileManager.default
        let files = try fm.contentsOfDirectory(at: Self.testVectorsDir, includingPropertiesForKeys: nil)
            .filter { $0.pathExtension == "json" }
        XCTAssertFalse(files.isEmpty, "no test vectors found")

        var parsedCount = 0
        for file in files {
            let data = try Data(contentsOf: file)
            guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let valid = json["valid"] as? [[String: Any]] else {
                continue // skip files without a `valid` array (e.g. malformed.json)
            }
            for vector in valid {
                let name = vector["name"] as? String ?? "unnamed"
                let packetDict = vector["packet"] as! [String: Any]
                let packetData = try JSONSerialization.data(withJSONObject: packetDict)
                let packet = try BridgePacket.deserialize(from: packetData)
                XCTAssertFalse(packet.type.isEmpty, "empty type in \(file.lastPathComponent)/\(name)")
                XCTAssertGreaterThan(packet.id, 0, "bad id in \(file.lastPathComponent)/\(name)")
                parsedCount += 1
            }
        }
        XCTAssertGreaterThan(parsedCount, 10, "expected to parse many vectors")
    }
}
