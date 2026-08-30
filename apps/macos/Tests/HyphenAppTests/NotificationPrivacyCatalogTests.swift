import XCTest
import HyphenNotifications
@testable import HyphenApp

final class NotificationPrivacyCatalogTests: XCTestCase {
    func testRowsContainOnlyObservedOrConfiguredPackagesInStableOrder() {
        let rows = NotificationPrivacyCatalog.rows(
            observedPackages: ["org.telegram.messenger", "com.unknown.chat"],
            storedModes: [
                "com.google.android.gm": .hideBody,
                "com.unknown.chat": .full,
            ],
            defaultMode: .full
        )

        XCTAssertEqual(
            rows.map(\.packageName),
            ["com.google.android.gm", "com.unknown.chat", "org.telegram.messenger"]
        )
        XCTAssertEqual(rows[0].name, "Gmail")
        XCTAssertEqual(rows[0].mode, .hideBody)
        XCTAssertEqual(rows[1].name, "com.unknown.chat")
        XCTAssertEqual(rows[1].mode, .full)
        XCTAssertEqual(rows[2].mode, .full)
        XCTAssertTrue(rows[0].isExplicit)
        XCTAssertTrue(rows[1].isExplicit)
        XCTAssertFalse(rows[2].isExplicit)
    }

    func testEmptyRuntimeAndConfigurationDoNotFabricateAppRows() {
        XCTAssertTrue(
            NotificationPrivacyCatalog.rows(
                observedPackages: [],
                storedModes: [:],
                defaultMode: .existsOnly
            ).isEmpty
        )
    }
}
