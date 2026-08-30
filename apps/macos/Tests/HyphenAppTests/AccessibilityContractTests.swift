import XCTest
@testable import HyphenApp

final class AccessibilityContractTests: XCTestCase {
    func testSecuritySensitiveIdentifiersStayStableAndUnique() {
        let expected = [
            "hyphen.pairing.qr",
            "hyphen.pairing.manual-payload",
            "hyphen.pairing.sas",
            "hyphen.pairing.confirm",
            "hyphen.pairing.reject",
            "hyphen.diagnostics.trace-ids",
            "hyphen.diagnostics.preview",
            "hyphen.diagnostics.export",
            "hyphen.diagnostics.delete",
        ]

        XCTAssertEqual(HyphenAccessibilityID.all, expected)
        XCTAssertEqual(Set(HyphenAccessibilityID.all).count, HyphenAccessibilityID.all.count)
    }

    func testManualPairingPayloadKeepsItsAccessibleValueWithoutADuplicateHeader() throws {
        let source = try String(
            contentsOf: sourceRoot.appendingPathComponent("UI/PairingWindowView.swift"),
            encoding: .utf8
        )

        XCTAssertTrue(source.contains(".accessibilityHidden(true)"))
        XCTAssertTrue(source.contains(".accessibilityLabel(L(\"pairing.manualEntry\"))"))
        XCTAssertTrue(source.contains(".accessibilityValue(manualPairingPayload)"))
    }

    private var sourceRoot: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("Sources/HyphenApp")
    }
}
