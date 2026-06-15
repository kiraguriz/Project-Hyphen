import XCTest
@testable import HyphenTransport

final class NegotiatedCapabilitiesTests: XCTestCase {
    func testNotificationOptionAccessorsReflectNegotiatedValues() throws {
        let advertised = try SessionHandshake.NegotiatedCapabilities.advertised()
        XCTAssertTrue(advertised.notificationReplyEnabled)
        XCTAssertTrue(advertised.notificationDismissEnabled)

        let disabled = SessionHandshake.NegotiatedCapabilities([
            SessionHandshake.capabilityNotifications: [
                "reply": "off",
                "dismiss": false,
            ],
        ])
        XCTAssertFalse(disabled.notificationReplyEnabled)
        XCTAssertFalse(disabled.notificationDismissEnabled)

        let empty = SessionHandshake.NegotiatedCapabilities.empty()
        XCTAssertFalse(empty.notificationReplyEnabled)
        XCTAssertFalse(empty.notificationDismissEnabled)
    }
}
