import HyphenTransport
import XCTest
@testable import HyphenNotifications

private final class RecordingNotificationPresenter: NotificationPresenter {
    var shown: [NotificationPresentationRequest] = []
    var removed: [String] = []

    func show(_ request: NotificationPresentationRequest) {
        shown.append(request)
    }

    func remove(identifier: String) {
        removed.append(identifier)
    }
}

final class NotificationMirrorReceiverTests: XCTestCase {
    private func envelope(
        type: String = NotificationMirrorProtocol.typePosted,
        capability: String? = NotificationMirrorProtocol.capability,
        payload: [String: Any]
    ) -> Envelope {
        Envelope(
            messageId: "01JZ0000000000000000000000",
            sessionId: "s_test1",
            type: type,
            capability: capability,
            seq: 2,
            sentAtUnixMs: 1_781_020_800_000,
            requiresAck: true,
            payload: payload
        )
    }

    func testPostedAndUpdatedUseTheSameMacNotificationIdentifier() throws {
        let presenter = RecordingNotificationPresenter()
        let receiver = NotificationMirrorReceiver(presenter: presenter)
        let first = payload(sbnKey: "0|com.chat|7|thread-123|10101", text: "hello")
        let second = payload(sbnKey: "0|com.chat|7|thread-123|10101", text: "hello again")

        let posted = try receiver.handle(envelope(type: NotificationMirrorProtocol.typePosted, payload: first))
        let updated = try receiver.handle(envelope(type: NotificationMirrorProtocol.typeUpdated, payload: second))

        let expectedIdentifier = "hyphen.notification.0|com.chat|7|thread-123|10101"
        XCTAssertEqual(posted, .shown(identifier: expectedIdentifier, sbnKey: "0|com.chat|7|thread-123|10101"))
        XCTAssertEqual(updated, .shown(identifier: expectedIdentifier, sbnKey: "0|com.chat|7|thread-123|10101"))
        XCTAssertEqual(presenter.shown.map(\.identifier), [expectedIdentifier, expectedIdentifier])
        XCTAssertEqual(presenter.shown.last?.body, "hello again")
    }

    func testRemovedClosesTheMappedMacNotificationIdentifier() throws {
        let presenter = RecordingNotificationPresenter()
        let receiver = NotificationMirrorReceiver(presenter: presenter)

        let action = try receiver.handle(
            envelope(
                type: NotificationMirrorProtocol.typeRemoved,
                payload: ["sbnKey": "0|com.mail|42|null|10101"]
            )
        )

        XCTAssertEqual(
            action,
            .removed(
                identifier: "hyphen.notification.0|com.mail|42|null|10101",
                sbnKey: "0|com.mail|42|null|10101"
            )
        )
        XCTAssertEqual(presenter.removed, ["hyphen.notification.0|com.mail|42|null|10101"])
        XCTAssertTrue(presenter.shown.isEmpty)
    }

    func testOtherEnvelopeTypesAreIgnored() throws {
        let presenter = RecordingNotificationPresenter()
        let receiver = NotificationMirrorReceiver(presenter: presenter)

        let action = try receiver.handle(envelope(type: "text.send", capability: "text.v1", payload: [:]))

        XCTAssertNil(action)
        XCTAssertTrue(presenter.shown.isEmpty)
        XCTAssertTrue(presenter.removed.isEmpty)
    }

    func testWrongCapabilityIsRejected() {
        let receiver = NotificationMirrorReceiver(presenter: RecordingNotificationPresenter())

        XCTAssertThrowsError(
            try receiver.handle(envelope(capability: "text.v1", payload: payload(sbnKey: "k", text: "hi")))
        )
    }

    func testBlankPayloadIdentityIsRejected() {
        let receiver = NotificationMirrorReceiver(presenter: RecordingNotificationPresenter())

        XCTAssertThrowsError(
            try receiver.handle(envelope(payload: ["sbnKey": " ", "packageName": "com.app"]))
        )
        XCTAssertThrowsError(
            try receiver.handle(envelope(payload: ["sbnKey": "key", "packageName": ""]))
        )
        XCTAssertThrowsError(
            try receiver.handle(envelope(type: NotificationMirrorProtocol.typeRemoved, payload: ["sbnKey": ""]))
        )
    }

    private func payload(sbnKey: String, text: String) -> [String: Any] {
        [
            "sbnKey": sbnKey,
            "packageName": "com.chat",
            "title": "Alice",
            "text": text,
            "category": "msg",
            "clearable": true,
            "ongoing": false,
        ]
    }
}
