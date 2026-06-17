import XCTest
@testable import HyphenCore

final class HyphenCoreTests: XCTestCase {

    func testVersionIsSemverish() {
        let parts = HyphenCore.version.split(separator: ".")
        XCTAssertEqual(parts.count, 3)
        XCTAssertTrue(parts.allSatisfy { Int($0) != nil })
    }

    func testBonjourServiceTypeMatchesProtocolV0AndAndroid() {
        // Protocol v0 §5 / DiscoveryManager.SERVICE_TYPE on Android.
        XCTAssertEqual(HyphenCore.bonjourServiceType, "_hyphen._tcp")
    }

    func testFingerprintDisplayStylesAreCanonical() {
        let fingerprint = Data([0xAB, 0xCD, 0xEF, 0x01, 0x23, 0x45] + Array(repeating: UInt8(0x67), count: 26))

        XCTAssertEqual(
            HyphenFingerprintDisplay.string(for: fingerprint, style: .shortPrefix),
            "abcdef012345"
        )
        XCTAssertEqual(
            HyphenFingerprintDisplay.string(for: fingerprint, style: .auditPreview),
            "SHA-256 · ab:cd:ef:01:...:67"
        )
        XCTAssertEqual(
            HyphenFingerprintDisplay.string(for: fingerprint, style: .fullHex).count,
            64
        )
    }
}
