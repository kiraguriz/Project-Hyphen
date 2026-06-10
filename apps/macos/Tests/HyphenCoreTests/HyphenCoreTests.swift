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
}
