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

private final class CoalescingNotificationPresenter: NotificationPresenter {
    private var deliveredByIdentifier: [String: NotificationPresentationRequest] = [:]
    private(set) var showCount = 0
    private(set) var removeCount = 0

    var deliveredCount: Int {
        deliveredByIdentifier.count
    }

    var deliveredIdentifiers: Set<String> {
        Set(deliveredByIdentifier.keys)
    }

    func body(for identifier: String) -> String? {
        deliveredByIdentifier[identifier]?.body
    }

    func show(_ request: NotificationPresentationRequest) {
        showCount += 1
        deliveredByIdentifier[request.identifier] = request
    }

    func remove(identifier: String) {
        removeCount += 1
        deliveredByIdentifier.removeValue(forKey: identifier)
    }
}

private final class RecordingDismissOutbox: NotificationDismissOutbox {
    var type: String?
    var capability: String?
    var requiresAck: Bool?
    var payload: [String: Any]?

    func send(
        type: String,
        capability: String,
        requiresAck: Bool,
        payload: [String: Any]
    ) -> String? {
        self.type = type
        self.capability = capability
        self.requiresAck = requiresAck
        self.payload = payload
        return "01JZ0000000000000000000001"
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

    func testReplyActionsAreParsedIntoPresentationRequests() throws {
        let presenter = RecordingNotificationPresenter()
        let receiver = NotificationMirrorReceiver(presenter: presenter)

        _ = try receiver.handle(
            envelope(
                payload: payload(
                    sbnKey: "0|com.chat|7|thread-123|10101",
                    text: "hello",
                    replyActions: [["actionIndex": 2, "label": "Reply", "actionId": "reply:1:reply:android.reply"]]
                )
            )
        )

        XCTAssertEqual(presenter.shown.count, 1)
        XCTAssertEqual(
            presenter.shown[0].replyActions,
            [try NotificationReplyAction(actionIndex: 2, label: "Reply", actionId: "reply:1:reply:android.reply")]
        )
    }

    func testReplyActionsAreHiddenWhenNegotiatedReplyIsDisabled() throws {
        let presenter = RecordingNotificationPresenter()
        let receiver = NotificationMirrorReceiver(
            presenter: presenter,
            replyActionsEnabled: { false }
        )

        _ = try receiver.handle(
            envelope(
                payload: payload(
                    sbnKey: "0|com.chat|7|thread-123|10101",
                    text: "hello",
                    replyActions: [["actionIndex": 2, "label": "Reply", "actionId": "reply:1:reply:android.reply"]]
                )
            )
        )

        XCTAssertEqual(presenter.shown.count, 1)
        XCTAssertEqual(presenter.shown[0].replyActions, [])
    }

    func testGlobalPrivacyPolicySuppressesMirroring() throws {
        let presenter = RecordingNotificationPresenter()
        let receiver = NotificationMirrorReceiver(
            presenter: presenter,
            privacyPolicy: { NotificationPrivacyPolicy(isMirroringEnabled: false) }
        )

        let action = try receiver.handle(
            envelope(payload: payload(sbnKey: "0|com.chat|7|thread-123|10101", text: "secret"))
        )

        XCTAssertNil(action)
        XCTAssertTrue(presenter.shown.isEmpty)
    }

    func testHideBodyPrivacyPolicyRemovesBodyTextAndReplyActions() throws {
        let presenter = RecordingNotificationPresenter()
        let receiver = NotificationMirrorReceiver(
            presenter: presenter,
            privacyPolicy: {
                NotificationPrivacyPolicy(perPackageModes: ["com.chat": .hideBody])
            }
        )

        _ = try receiver.handle(
            envelope(
                payload: payload(
                    sbnKey: "0|com.chat|7|thread-123|10101",
                    text: "secret",
                    replyActions: [["actionIndex": 2, "label": "Reply", "actionId": "reply:1:reply:android.reply"]]
                )
            )
        )

        XCTAssertEqual(presenter.shown.count, 1)
        XCTAssertEqual(presenter.shown[0].title, "Alice")
        XCTAssertEqual(presenter.shown[0].body, "")
        XCTAssertEqual(presenter.shown[0].packageName, "com.chat")
        // A reply action label can leak content, so hideBody drops actions too.
        XCTAssertEqual(presenter.shown[0].replyActions, [])
    }

    func testExistsOnlyPrivacyPolicyRemovesVisibleSourceContentAndActionsButKeepsIdentity() throws {
        let presenter = RecordingNotificationPresenter()
        let receiver = NotificationMirrorReceiver(
            presenter: presenter,
            privacyPolicy: {
                NotificationPrivacyPolicy(perPackageModes: ["com.chat": .existsOnly])
            }
        )

        _ = try receiver.handle(
            envelope(
                payload: payload(
                    sbnKey: "0|com.chat|7|thread-123|10101",
                    text: "secret",
                    replyActions: [["actionIndex": 2, "label": "Reply", "actionId": "reply:1:reply:android.reply"]]
                )
            )
        )

        XCTAssertEqual(presenter.shown.count, 1)
        XCTAssertEqual(presenter.shown[0].identifier, "hyphen.notification.0|com.chat|7|thread-123|10101")
        XCTAssertEqual(presenter.shown[0].sbnKey, "0|com.chat|7|thread-123|10101")
        XCTAssertEqual(presenter.shown[0].packageName, "Android")
        XCTAssertEqual(presenter.shown[0].title, "Android notification")
        XCTAssertEqual(presenter.shown[0].body, "")
        XCTAssertNil(presenter.shown[0].category)
        XCTAssertEqual(presenter.shown[0].replyActions, [])
    }

    func testDefaultPrivacyModeAppliesToUnknownPackages() throws {
        let presenter = RecordingNotificationPresenter()
        let receiver = NotificationMirrorReceiver(
            presenter: presenter,
            privacyPolicy: {
                NotificationPrivacyPolicy(defaultMode: .existsOnly)
            }
        )

        _ = try receiver.handle(
            envelope(payload: payload(sbnKey: "0|com.unknown|7|thread-123|10101", packageName: "com.unknown", text: "secret"))
        )

        XCTAssertEqual(presenter.shown.count, 1)
        XCTAssertEqual(presenter.shown[0].title, "Android notification")
        XCTAssertEqual(presenter.shown[0].body, "")
        XCTAssertEqual(presenter.shown[0].packageName, "Android")
    }

    func testFullPrivacyModeKeepsReplyActionsUnderExistingCapabilityGate() throws {
        let presenter = RecordingNotificationPresenter()
        let receiver = NotificationMirrorReceiver(
            presenter: presenter,
            replyActionsEnabled: { true },
            privacyPolicy: {
                NotificationPrivacyPolicy(perPackageModes: ["com.chat": .full])
            }
        )

        _ = try receiver.handle(
            envelope(
                payload: payload(
                    sbnKey: "0|com.chat|7|thread-123|10101",
                    text: "secret",
                    replyActions: [["actionIndex": 2, "label": "Reply", "actionId": "reply:1:reply:android.reply"]]
                )
            )
        )

        // full mode defers reply visibility entirely to the capability gate.
        XCTAssertEqual(
            presenter.shown[0].replyActions,
            [try NotificationReplyAction(actionIndex: 2, label: "Reply", actionId: "reply:1:reply:android.reply")]
        )
    }

    func testHideBodyDropsReplyActionsEvenWhenCapabilityGateAllowsThem() throws {
        let presenter = RecordingNotificationPresenter()
        let receiver = NotificationMirrorReceiver(
            presenter: presenter,
            replyActionsEnabled: { true },
            privacyPolicy: {
                NotificationPrivacyPolicy(perPackageModes: ["com.chat": .hideBody])
            }
        )

        _ = try receiver.handle(
            envelope(
                payload: payload(
                    sbnKey: "0|com.chat|7|thread-123|10101",
                    text: "secret",
                    replyActions: [["actionIndex": 2, "label": "Reply", "actionId": "reply:1:reply:android.reply"]]
                )
            )
        )

        XCTAssertEqual(presenter.shown[0].replyActions, [])
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

    func testNotificationStormCoalescesByAndroidKeyAndRemovalsStayBounded() throws {
        let presenter = CoalescingNotificationPresenter()
        let receiver = NotificationMirrorReceiver(presenter: presenter)
        let keys = (0..<25).map { index in "0|com.chat|\(index)|thread-\(index)|10101" }
        var seen = Set<String>()

        for index in 0..<1_000 {
            let key = keys[index % keys.count]
            let type = seen.insert(key).inserted
                ? NotificationMirrorProtocol.typePosted
                : NotificationMirrorProtocol.typeUpdated

            _ = try receiver.handle(
                envelope(
                    type: type,
                    payload: payload(sbnKey: key, text: "message-\(index)")
                )
            )
        }

        XCTAssertEqual(presenter.showCount, 1_000)
        XCTAssertEqual(presenter.deliveredCount, keys.count)
        XCTAssertEqual(
            presenter.deliveredIdentifiers,
            Set(keys.map(AndroidNotificationPayload.identifier(for:)))
        )
        XCTAssertEqual(
            presenter.body(for: AndroidNotificationPayload.identifier(for: keys[24])),
            "message-999"
        )

        for key in keys.prefix(10) {
            _ = try receiver.handle(
                envelope(type: NotificationMirrorProtocol.typeRemoved, payload: ["sbnKey": key])
            )
        }

        XCTAssertEqual(presenter.removeCount, 10)
        XCTAssertEqual(presenter.deliveredCount, 15)
        XCTAssertEqual(
            presenter.deliveredIdentifiers,
            Set(keys.dropFirst(10).map(AndroidNotificationPayload.identifier(for:)))
        )
    }

    func testOtherEnvelopeTypesAreIgnored() throws {
        let presenter = RecordingNotificationPresenter()
        let receiver = NotificationMirrorReceiver(presenter: presenter)

        let action = try receiver.handle(envelope(type: "text.send", capability: "text.v1", payload: [:]))

        XCTAssertNil(action)
        XCTAssertTrue(presenter.shown.isEmpty)
        XCTAssertTrue(presenter.removed.isEmpty)
    }

    func testDismissResultIsParsedForStatusReporting() throws {
        let receiver = NotificationMirrorReceiver(presenter: RecordingNotificationPresenter())

        let success = try receiver.handle(
            envelope(
                type: NotificationMirrorProtocol.typeDismissResult,
                payload: ["sbnKey": "0|com.chat|7|thread-123|10101", "success": true]
            )
        )
        let failure = try receiver.handle(
            envelope(
                type: NotificationMirrorProtocol.typeDismissResult,
                payload: [
                    "sbnKey": "0|com.chat|7|thread-123|10101",
                    "success": false,
                    "errorCode": "permission/notifications-denied",
                ]
            )
        )

        XCTAssertEqual(
            success,
            .dismissResult(sbnKey: "0|com.chat|7|thread-123|10101", success: true, errorCode: nil)
        )
        XCTAssertEqual(
            failure,
            .dismissResult(
                sbnKey: "0|com.chat|7|thread-123|10101",
                success: false,
                errorCode: "permission/notifications-denied"
            )
        )
    }

    func testReplyResultIsParsedForStatusReporting() throws {
        let receiver = NotificationMirrorReceiver(presenter: RecordingNotificationPresenter())

        let success = try receiver.handle(
            envelope(
                type: NotificationMirrorProtocol.typeReplyResult,
                payload: ["sbnKey": "0|com.chat|7|thread-123|10101", "success": true]
            )
        )
        let failure = try receiver.handle(
            envelope(
                type: NotificationMirrorProtocol.typeReplyResult,
                payload: [
                    "sbnKey": "0|com.chat|7|thread-123|10101",
                    "success": false,
                    "errorCode": "plugin/reply-unavailable",
                ]
            )
        )

        XCTAssertEqual(
            success,
            .replyResult(sbnKey: "0|com.chat|7|thread-123|10101", success: true, errorCode: nil)
        )
        XCTAssertEqual(
            failure,
            .replyResult(
                sbnKey: "0|com.chat|7|thread-123|10101",
                success: false,
                errorCode: "plugin/reply-unavailable"
            )
        )
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

    func testDismissSenderUsesNotificationsCapabilityAndRequiresAck() {
        let outbox = RecordingDismissOutbox()
        let id = NotificationDismissSender(outbox: outbox).requestDismiss(
            sbnKey: "0|com.chat|7|thread-123|10101"
        )

        XCTAssertEqual(id, "01JZ0000000000000000000001")
        XCTAssertEqual(outbox.type, NotificationMirrorProtocol.typeDismissRequest)
        XCTAssertEqual(outbox.capability, NotificationMirrorProtocol.capability)
        XCTAssertEqual(outbox.requiresAck, true)
        XCTAssertEqual(outbox.payload?["sbnKey"] as? String, "0|com.chat|7|thread-123|10101")
    }

    func testReplySenderUsesNotificationsCapabilityAndRequiresAck() {
        let outbox = RecordingDismissOutbox()
        let id = NotificationReplySender(outbox: outbox).requestReply(
            sbnKey: "0|com.chat|7|thread-123|10101",
            actionIndex: 2,
            actionId: "reply:1:reply:android.reply",
            text: "On my way"
        )

        XCTAssertEqual(id, "01JZ0000000000000000000001")
        XCTAssertEqual(outbox.type, NotificationMirrorProtocol.typeReplyRequest)
        XCTAssertEqual(outbox.capability, NotificationMirrorProtocol.capability)
        XCTAssertEqual(outbox.requiresAck, true)
        XCTAssertEqual(outbox.payload?["sbnKey"] as? String, "0|com.chat|7|thread-123|10101")
        XCTAssertEqual(outbox.payload?["actionIndex"] as? Int, 2)
        XCTAssertEqual(outbox.payload?["actionId"] as? String, "reply:1:reply:android.reply")
        XCTAssertEqual(outbox.payload?["text"] as? String, "On my way")
    }

    func testReplySenderKeepsLegacyIndexPayloadWhenActionIdIsBlank() {
        let outbox = RecordingDismissOutbox()

        _ = NotificationReplySender(outbox: outbox).requestReply(
            sbnKey: "0|com.chat|7|thread-123|10101",
            actionIndex: 2,
            actionId: " ",
            text: "On my way"
        )

        XCTAssertEqual(outbox.payload?["actionIndex"] as? Int, 2)
        XCTAssertNil(outbox.payload?["actionId"])
    }

    func testReplyRoutingKeepsMultipleActionsDistinct() throws {
        let actions = [
            try NotificationReplyAction(actionIndex: 2, label: "Reply", actionId: "reply:1:reply:android.reply"),
            try NotificationReplyAction(actionIndex: 4, label: "Reply privately", actionId: "reply:1:reply-privately:android.reply"),
        ]
        let routes = NotificationReplyRouting.routes(for: actions)
        let userInfo = NotificationReplyRouting.userInfo(sbnKey: "0|com.chat|7|thread-123|10101", routes: routes)

        XCTAssertEqual(routes.map(\.actionIdentifier), ["hyphen.notification.reply.0", "hyphen.notification.reply.1"])
        XCTAssertEqual(routes.map(\.actionIndex), [2, 4])
        XCTAssertEqual(
            NotificationReplyRouting.route(actionIdentifier: routes[0].actionIdentifier, userInfo: userInfo)?.actionId,
            "reply:1:reply:android.reply"
        )
        XCTAssertEqual(
            NotificationReplyRouting.route(actionIdentifier: routes[1].actionIdentifier, userInfo: userInfo)?.actionIndex,
            4
        )
    }

    func testReplyRoutingOmitsMultiActionLegacyChoicesWithoutActionId() throws {
        let actions = [
            try NotificationReplyAction(actionIndex: 2, label: "Reply"),
            try NotificationReplyAction(actionIndex: 4, label: "Reply privately"),
        ]

        XCTAssertEqual(NotificationReplyRouting.routes(for: actions), [])
        XCTAssertEqual(
            NotificationReplyRouting.routes(for: [actions[0]]),
            [NotificationReplyRoute(actionIdentifier: "hyphen.notification.reply.0", actionIndex: 2, actionId: nil, label: "Reply")]
        )
    }

    func testDismissSenderRejectsBlankKeys() {
        let outbox = RecordingDismissOutbox()

        XCTAssertNil(NotificationDismissSender(outbox: outbox).requestDismiss(sbnKey: " "))
        XCTAssertNil(outbox.type)
    }

    func testReplySenderRejectsBlankTextAndBadActionIndex() {
        let outbox = RecordingDismissOutbox()

        XCTAssertNil(NotificationReplySender(outbox: outbox).requestReply(sbnKey: "key", actionIndex: 0, text: " "))
        XCTAssertNil(NotificationReplySender(outbox: outbox).requestReply(sbnKey: "key", actionIndex: -1, text: "hi"))
        XCTAssertNil(outbox.type)
    }

    func testPrivacyPolicySenderEmitsCanonicalPayloadShape() {
        let outbox = RecordingDismissOutbox()
        let policy = NotificationPrivacyPolicy(
            isMirroringEnabled: true,
            defaultMode: .existsOnly,
            perPackageModes: [
                "com.google.android.gm": .full,
                "com.tencent.mm": .full,
                "org.telegram.messenger": .hideBody,
            ]
        )

        let id = NotificationPrivacyPolicySender(outbox: outbox).send(policy: policy)

        XCTAssertEqual(id, "01JZ0000000000000000000001")
        XCTAssertEqual(outbox.type, NotificationMirrorProtocol.typePrivacyPolicy)
        XCTAssertEqual(outbox.capability, NotificationMirrorProtocol.capability)
        XCTAssertEqual(outbox.requiresAck, true)
        XCTAssertEqual(outbox.payload?["defaultMode"] as? String, "existsOnly")
        // Per-package modes are an array of {packageName, mode}, sorted by package.
        let perPackage = outbox.payload?["perPackageModes"] as? [[String: String]]
        XCTAssertEqual(perPackage, [
            ["packageName": "com.google.android.gm", "mode": "full"],
            ["packageName": "com.tencent.mm", "mode": "full"],
            ["packageName": "org.telegram.messenger", "mode": "hideBody"],
        ])
    }

    private func payload(
        sbnKey: String,
        packageName: String = "com.chat",
        text: String,
        replyActions: [[String: Any]] = []
    ) -> [String: Any] {
        var payload: [String: Any] = [
            "sbnKey": sbnKey,
            "packageName": packageName,
            "title": "Alice",
            "text": text,
            "category": "msg",
            "clearable": true,
            "ongoing": false,
        ]
        if !replyActions.isEmpty {
            payload["replyActions"] = replyActions
        }
        return payload
    }
}
