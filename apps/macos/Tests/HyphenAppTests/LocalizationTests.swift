import Foundation
import XCTest
@testable import HyphenApp

/// Guards the M-A6 localization wiring: `L(...)` must resolve against the
/// HyphenApp resource bundle. If the `.lproj` tables are dropped from the
/// bundle, `NSLocalizedString` returns the key verbatim — these assertions
/// catch that regardless of the test host's locale.
final class LocalizationTests: XCTestCase {
    func testKeysResolveToSomeTranslation() {
        for key in [
            "state.connected",
            "popover.pairNewDevice",
            "settings.windowTitle",
            "pairing.windowTitle",
            "settings.diag.previewLabel",
            "common.cancel",
        ] {
            let value = L(key)
            XCTAssertFalse(value.isEmpty, "\(key) resolved to empty")
            XCTAssertNotEqual(value, key, "\(key) did not resolve (table missing?)")
        }
    }

    func testFormatArgumentsInterpolate() {
        let connected = L("pairing.note.connected", "Pixel")
        XCTAssertTrue(connected.contains("Pixel"))
        XCTAssertFalse(connected.contains("%@"))

        let date = L("feed.dateMonthDay", 6, 17)
        XCTAssertTrue(date.contains("6"))
        XCTAssertTrue(date.contains("17"))
        XCTAssertFalse(date.contains("%"))
    }

    func testEnglishAndChineseTablesHaveIdenticalKeys() throws {
        let macOSRoot = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let resources = macOSRoot.appendingPathComponent("Sources/HyphenApp/Resources")
        let english = try localizationKeys(
            resources.appendingPathComponent("en.lproj/Localizable.strings")
        )
        let chinese = try localizationKeys(
            resources.appendingPathComponent("zh-Hans.lproj/Localizable.strings")
        )

        XCTAssertFalse(english.isEmpty)
        XCTAssertEqual(english, chinese)
    }

    private func localizationKeys(_ url: URL) throws -> Set<String> {
        let source = try String(contentsOf: url, encoding: .utf8)
        let expression = try NSRegularExpression(pattern: #"(?m)^\s*\"([^\"]+)\"\s*="#)
        let range = NSRange(source.startIndex..<source.endIndex, in: source)
        return Set(expression.matches(in: source, range: range).compactMap { match in
            guard let keyRange = Range(match.range(at: 1), in: source) else { return nil }
            return String(source[keyRange])
        })
    }
}
