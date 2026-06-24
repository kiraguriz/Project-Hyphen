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

    func testTextDirectionNegotiatesFromAndroidFrameAndNeverFabricatesBidirectional() {
        let negotiate = SessionHandshake.NegotiatedCapabilities.negotiateTextDirection
        // Both bidirectional -> bidirectional (the v0 built-in case).
        XCTAssertEqual("bidirectional", negotiate("android", "bidirectional", "macos", "bidirectional"))
        // A one-directional peer is NOT silently upgraded to bidirectional.
        XCTAssertEqual("send-only", negotiate("android", "send-only", "macos", "bidirectional"))
        XCTAssertEqual("receive-only", negotiate("android", "receive-only", "macos", "bidirectional"))
        // Frame is the android endpoint: a mac that only sends => android receives only.
        XCTAssertEqual("receive-only", negotiate("android", "bidirectional", "macos", "send-only"))
        // Frame independence: swapping argument order yields the same android-frame value.
        XCTAssertEqual("send-only", negotiate("macos", "bidirectional", "android", "send-only"))
        // No compatible flow -> none.
        XCTAssertEqual("none", negotiate("android", "send-only", "macos", "send-only"))
        XCTAssertEqual("none", negotiate("android", "receive-only", "macos", "receive-only"))
    }

    func testResolveTextDirectionFinalizesAndroidFrameAndDropsTextWhenIncompatible() throws {
        let advertised = try SessionHandshake.NegotiatedCapabilities.advertised()
        let sendOnlyPeer = SessionHandshake.NegotiatedCapabilities([
            SessionHandshake.capabilityText: ["direction": "send-only"],
        ])
        // Responder (mac) intersect carry-through leaves "bidirectional";
        // resolve replaces it with the real android-frame value.
        let negotiated = advertised.intersecting(sendOnlyPeer).resolvingTextDirection(
            localKind: "macos", localDir: "bidirectional", peerKind: "android", peerDir: "send-only"
        )
        XCTAssertTrue(negotiated.contains(SessionHandshake.capabilityText))
        XCTAssertEqual("send-only", negotiated.textDirection)

        // Two incompatible single directions drop the text capability entirely.
        let dropped = advertised.intersecting(sendOnlyPeer).resolvingTextDirection(
            localKind: "macos", localDir: "receive-only", peerKind: "android", peerDir: "receive-only"
        )
        XCTAssertFalse(dropped.contains(SessionHandshake.capabilityText))
        XCTAssertNil(dropped.textDirection)
    }

    func testIntersectCarriesOneDirectionalLeftOperandThroughInsteadOfFabricatingBidirectional() throws {
        let resolvedServer = SessionHandshake.NegotiatedCapabilities([
            SessionHandshake.capabilityText: ["direction": "send-only"],
        ])
        // Mirrors the client path: serverNegotiated.intersecting(clientAdvertised).
        let clientView = resolvedServer.intersecting(try SessionHandshake.NegotiatedCapabilities.advertised())
        XCTAssertEqual("send-only", clientView.textDirection)
    }
}
