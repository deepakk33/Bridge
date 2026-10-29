import XCTest
@testable import Bridge

/// Cross-platform guarantee: the Android suite reads the same vector file, so the two
/// derivations cannot drift apart without one of these suites going red.
final class PairingCodeTests: XCTestCase {

    private struct Vector {
        let name: String
        let a: String
        let b: String
        let expected: String
    }

    private static var vectorFile: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("protocol/test-vectors/pairing-code.json")
    }

    private func loadVectors() throws -> [Vector] {
        let data = try Data(contentsOf: Self.vectorFile)
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let raw = json["vectors"] as? [[String: Any]] else {
            XCTFail("pairing-code.json has no `vectors` array")
            return []
        }
        return raw.map {
            Vector(
                name: $0["name"] as? String ?? "unnamed",
                a: $0["fingerprintA"] as! String,
                b: $0["fingerprintB"] as! String,
                expected: $0["expected"] as! String
            )
        }
    }

    func testEveryVectorMatches() throws {
        let vectors = try loadVectors()
        XCTAssertGreaterThanOrEqual(vectors.count, 8, "expected the full vector set")
        for v in vectors {
            XCTAssertEqual(PairingCode.derive(v.a, v.b), v.expected, "vector: \(v.name)")
        }
    }

    func testAlwaysSixDigits() throws {
        for v in try loadVectors() {
            let code = PairingCode.derive(v.a, v.b)
            XCTAssertEqual(code.count, 6, "vector: \(v.name)")
            XCTAssertTrue(code.allSatisfy(\.isNumber), "vector: \(v.name) produced \(code)")
        }
    }

    /// The leading-zero vector exists because formatting with %d instead of %06d
    /// silently produces a five-digit code that still looks plausible on screen.
    func testLeadingZeroIsPreserved() throws {
        let vectors = try loadVectors()
        guard let zero = vectors.first(where: { $0.expected.hasPrefix("0") }) else {
            XCTFail("vector file lost its leading-zero case")
            return
        }
        XCTAssertEqual(PairingCode.derive(zero.a, zero.b), zero.expected)
    }

    func testOrderDoesNotMatter() {
        let a = String(repeating: "a1", count: 32)
        let b = String(repeating: "b2", count: 32)
        XCTAssertEqual(PairingCode.derive(a, b), PairingCode.derive(b, a))
    }

    func testCaseDoesNotMatter() {
        let a = String(repeating: "ab", count: 32)
        let b = String(repeating: "cd", count: 32)
        XCTAssertEqual(PairingCode.derive(a, b), PairingCode.derive(a.uppercased(), b))
    }

    /// The whole point: a different peer certificate must produce different digits.
    func testDifferentPeerGivesDifferentCode() {
        let mac = String(repeating: "11", count: 32)
        let phone = String(repeating: "22", count: 32)
        let impostor = String(repeating: "33", count: 32)
        XCTAssertNotEqual(PairingCode.derive(mac, phone), PairingCode.derive(mac, impostor))
    }
}
