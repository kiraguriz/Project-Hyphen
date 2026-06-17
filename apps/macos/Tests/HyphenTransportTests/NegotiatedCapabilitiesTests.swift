import XCTest
@testable import HyphenTransport

final class NegotiatedCapabilitiesTests: XCTestCase {
    func testNotificationOptionAccessorsReflectNegotiatedValues() throws {
        let advertised = try SessionHandshake.NegotiatedCapabilities.advertised()
        XCTAssertTrue(advertised.notificationReplyEnabled)
        XCTAssertTrue(advertised.notificationDismissEnabled)
        XCTAssertTrue(advertised.notificationPrivacyPolicyEnabled)

        let disabled = SessionHandshake.NegotiatedCapabilities([
            SessionHandshake.capabilityNotifications: [
                "reply": "off",
                "dismiss": false,
                "privacyPolicy": false,
            ],
        ])
        XCTAssertFalse(disabled.notificationReplyEnabled)
        XCTAssertFalse(disabled.notificationDismissEnabled)
        XCTAssertFalse(disabled.notificationPrivacyPolicyEnabled)

        let empty = SessionHandshake.NegotiatedCapabilities.empty()
        XCTAssertFalse(empty.notificationReplyEnabled)
        XCTAssertFalse(empty.notificationDismissEnabled)
        XCTAssertFalse(empty.notificationPrivacyPolicyEnabled)
    }

    func testPrivacyPolicyNegotiatesTrueOnlyWhenBothPeersAdvertiseIt() throws {
        let supports = try SessionHandshake.NegotiatedCapabilities.advertised()
        let legacy = SessionHandshake.NegotiatedCapabilities([
            SessionHandshake.capabilityNotifications: ["reply": "beta", "dismiss": true],
        ])

        XCTAssertTrue(supports.intersecting(supports).notificationPrivacyPolicyEnabled)
        XCTAssertFalse(supports.intersecting(legacy).notificationPrivacyPolicyEnabled)
        XCTAssertFalse(legacy.intersecting(supports).notificationPrivacyPolicyEnabled)
    }
}
