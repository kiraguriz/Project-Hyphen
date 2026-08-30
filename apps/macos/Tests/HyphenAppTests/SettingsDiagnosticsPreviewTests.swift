import XCTest
@testable import HyphenApp

final class SettingsDiagnosticsPreviewTests: XCTestCase {
    func testLoadReturnsLiveExporterJSON() {
        let json = SettingsDiagnosticsPreview.load(
            fallback: "unavailable",
            generate: { "{\"eventCount\":1}" }
        )

        XCTAssertEqual(json, "{\"eventCount\":1}")
    }

    func testLoadUsesFallbackWhenExporterFails() {
        enum SampleError: Error { case failed }

        let json = SettingsDiagnosticsPreview.load(
            fallback: "unavailable",
            generate: { throw SampleError.failed }
        )

        XCTAssertEqual(json, "unavailable")
    }
}
