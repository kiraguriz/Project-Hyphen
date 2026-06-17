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
}
