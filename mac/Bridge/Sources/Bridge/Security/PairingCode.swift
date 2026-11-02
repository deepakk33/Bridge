import CryptoKit
import Foundation

/// The six digits both devices display before either side pins anything.
///
/// Derived from the two certificate fingerprints and never transmitted: a peer in the
/// middle necessarily presents a different certificate to at least one side, so the two
/// screens disagree and the user declines. Sorting the inputs makes the result
/// order-independent, so neither side needs to know which one initiated.
///
/// The Android implementation must agree byte for byte — both are checked against
/// `protocol/test-vectors/pairing-code.json`.
enum PairingCode {
    /// - Returns: exactly six decimal digits, zero-padded on the left.
    static func derive(_ a: String, _ b: String) -> String {
        let sorted = [a.lowercased(), b.lowercased()].sorted()
        let digest = SHA256.hash(data: Data(sorted.joined().utf8))
        let leading = digest.prefix(4).reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
        return String(format: "%06d", Int(leading % 1_000_000))
    }
}
