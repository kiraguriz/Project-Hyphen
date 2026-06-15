import HyphenTransport
import XCTest
@testable import HyphenNotifications

final class NotificationCapabilityGateTests: XCTestCase {
    func testMissingNotificationCapabilityDisablesAllNotificationActions() {
        let capabilities = SessionHandshake.NegotiatedCapabilities.empty()

        XCTAssertFalse(NotificationCapabilityGate.canBind(capabilities))
        XCTAssertFalse(NotificationCapabilityGate.canPresentReplyActions(capabilities))
        XCTAssertFalse(NotificationCapabilityGate.canSendReply(capabilities))
        XCTAssertFalse(NotificationCapabilityGate.canSendDismiss(capabilities))
        XCTAssertFalse(
            NotificationCapabilityGate.allowsInboundResult(
                type: NotificationMirrorProtocol.typeReplyResult,
                capabilities: capabilities
            )
        )
        XCTAssertFalse(
            NotificationCapabilityGate.allowsInboundResult(
                type: NotificationMirrorProtocol.typeDismissResult,
                capabilities: capabilities
            )
        )
        XCTAssertFalse(
            NotificationCapabilityGate.allowsInboundEnvelope(
                type: NotificationMirrorProtocol.typePosted,
                capabilities: capabilities
            )
        )
    }

    func testDisabledNotificationOptionsRejectOnlyTheirActions() {
        let capabilities = SessionHandshake.NegotiatedCapabilities([
            NotificationMirrorProtocol.capability: [
                "reply": "off",
                "dismiss": false,
            ],
        ])

        XCTAssertTrue(NotificationCapabilityGate.canBind(capabilities))
        XCTAssertFalse(NotificationCapabilityGate.canPresentReplyActions(capabilities))
        XCTAssertFalse(NotificationCapabilityGate.canSendReply(capabilities))
        XCTAssertFalse(NotificationCapabilityGate.canSendDismiss(capabilities))
        XCTAssertFalse(
            NotificationCapabilityGate.allowsInboundResult(
                type: NotificationMirrorProtocol.typeReplyResult,
                capabilities: capabilities
            )
        )
        XCTAssertFalse(
            NotificationCapabilityGate.allowsInboundResult(
                type: NotificationMirrorProtocol.typeDismissResult,
                capabilities: capabilities
            )
        )
        XCTAssertTrue(
            NotificationCapabilityGate.allowsInboundResult(
                type: NotificationMirrorProtocol.typePosted,
                capabilities: capabilities
            )
        )
        XCTAssertTrue(
            NotificationCapabilityGate.allowsInboundEnvelope(
                type: NotificationMirrorProtocol.typePosted,
                capabilities: capabilities
            )
        )
        XCTAssertFalse(
            NotificationCapabilityGate.allowsInboundEnvelope(
                type: NotificationMirrorProtocol.typeReplyResult,
                capabilities: capabilities
            )
        )
    }

    func testAdvertisedNotificationOptionsAllowActions() throws {
        let capabilities = try SessionHandshake.NegotiatedCapabilities.advertised()

        XCTAssertTrue(NotificationCapabilityGate.canBind(capabilities))
        XCTAssertTrue(NotificationCapabilityGate.canPresentReplyActions(capabilities))
        XCTAssertTrue(NotificationCapabilityGate.canSendReply(capabilities))
        XCTAssertTrue(NotificationCapabilityGate.canSendDismiss(capabilities))
        XCTAssertTrue(
            NotificationCapabilityGate.allowsInboundResult(
                type: NotificationMirrorProtocol.typeReplyResult,
                capabilities: capabilities
            )
        )
        XCTAssertTrue(
            NotificationCapabilityGate.allowsInboundResult(
                type: NotificationMirrorProtocol.typeDismissResult,
                capabilities: capabilities
            )
        )
        XCTAssertTrue(
            NotificationCapabilityGate.allowsInboundEnvelope(
                type: NotificationMirrorProtocol.typePosted,
                capabilities: capabilities
            )
        )
        XCTAssertTrue(
            NotificationCapabilityGate.allowsInboundEnvelope(
                type: NotificationMirrorProtocol.typeRemoved,
                capabilities: capabilities
            )
        )
        XCTAssertTrue(
            NotificationCapabilityGate.allowsInboundEnvelope(
                type: NotificationMirrorProtocol.typeReplyResult,
                capabilities: capabilities
            )
        )
        XCTAssertFalse(NotificationCapabilityGate.isNotificationEnvelope(type: "text.send"))
    }
}
