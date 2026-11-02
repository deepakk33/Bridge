import XCTest
@testable import Bridge

/// BR-4: default-deny. The shipped bug was structural — the trust check sat behind
/// `if peerDeviceId != nil, peerFingerprint != nil`, so a peer that had said nothing
/// about itself was the one peer whose packets were never checked.
final class PacketGateTests: XCTestCase {

    private let fingerprint = String(repeating: "aa", count: 32)

    private func trusted(_ id: String, _ fp: String) -> Bool {
        id == "phone-1" && fp == fingerprint
    }

    private func nothingTrusted(_: String, _: String) -> Bool { false }

    private func allows(_ type: String, _ id: String?, _ fp: String?,
                        _ isTrusted: (String, String) -> Bool) -> Bool {
        PacketGate.allows(packetType: type, peerDeviceId: id, peerFingerprint: fp, isTrusted: isTrusted)
    }

    func testIdentityAndPairPassFromAStranger() {
        XCTAssertTrue(allows("bridge.identity", nil, nil, nothingTrusted))
        XCTAssertTrue(allows("bridge.pair", nil, nil, nothingTrusted))
    }

    func testEveryOtherTypeIsDroppedBeforeIdentity() {
        for type in [
            "bridge.clipboard", "bridge.clipboard.connect", "bridge.notification",
            "bridge.notification.reply", "bridge.share.request", "bridge.battery"
        ] {
            XCTAssertFalse(allows(type, nil, nil, trusted), "\(type) must not pass")
        }
    }

    /// Answering a ping confirms a live Bridge endpoint to a peer that has not identified itself.
    func testPingIsNotExempt() {
        XCTAssertFalse(allows("bridge.ping", nil, nil, trusted))
    }

    func testIdentifiedButUnpinnedPeerIsDropped() {
        XCTAssertFalse(allows("bridge.clipboard", "phone-1", fingerprint, nothingTrusted))
    }

    func testMismatchedFingerprintIsDropped() {
        XCTAssertFalse(allows("bridge.clipboard", "phone-1", String(repeating: "bb", count: 32), trusted))
    }

    func testTrustedPeerPasses() {
        XCTAssertTrue(allows("bridge.clipboard", "phone-1", fingerprint, trusted))
        XCTAssertTrue(allows("bridge.ping", "phone-1", fingerprint, trusted))
    }

    /// Identity and pair must stay reachable, or a re-pair could never happen.
    func testIdentityAndPairPassEvenWhenNothingIsTrusted() {
        XCTAssertTrue(allows("bridge.identity", "phone-1", fingerprint, nothingTrusted))
        XCTAssertTrue(allows("bridge.pair", "phone-1", fingerprint, nothingTrusted))
    }

    func testHalfAnIdentityIsNoIdentity() {
        XCTAssertFalse(allows("bridge.clipboard", "phone-1", nil, trusted))
        XCTAssertFalse(allows("bridge.clipboard", nil, fingerprint, trusted))
    }

    /// The cap is shared with the Android reader; a drift here is a protocol split.
    func testLineCapMatchesTheAndroidReader() {
        XCTAssertEqual(L2CAPChannel.maxLineLength, 256 * 1024)
    }
}
