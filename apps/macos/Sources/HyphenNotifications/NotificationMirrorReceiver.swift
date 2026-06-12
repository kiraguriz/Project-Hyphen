import Foundation
import HyphenTransport
import UserNotifications

public enum NotificationMirrorProtocol {
    public static let capability = "notifications.v1"
    public static let typePosted = "notification.posted"
    public static let typeUpdated = "notification.updated"
    public static let typeRemoved = "notification.removed"
    public static let typeDismissRequest = "notification.dismiss.request"
    public static let typeDismissResult = "notification.dismiss.result"
    public static let typeReplyRequest = "notification.reply.request"
    public static let typeReplyResult = "notification.reply.result"
}

public enum NotificationMirrorError: Error, Equatable {
    case unsupportedCapability(String?)
    case invalidPayload(String)
}

public struct NotificationPresentationRequest: Equatable {
    public let identifier: String
    public let sbnKey: String
    public let packageName: String
    public let title: String
    public let body: String
    public let category: String?
    public let replyActions: [NotificationReplyAction]

    public init(
        identifier: String,
        sbnKey: String,
        packageName: String,
        title: String,
        body: String,
        category: String?,
        replyActions: [NotificationReplyAction] = []
    ) {
        self.identifier = identifier
        self.sbnKey = sbnKey
        self.packageName = packageName
        self.title = title
        self.body = body
        self.category = category
        self.replyActions = replyActions
    }
}

public struct NotificationReplyAction: Equatable {
    public let actionIndex: Int
    public let label: String

    public init(actionIndex: Int, label: String) throws {
        guard actionIndex >= 0 else {
            throw NotificationMirrorError.invalidPayload("reply actionIndex must be non-negative")
        }
        let trimmed = label.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            throw NotificationMirrorError.invalidPayload("reply label must be a non-empty string")
        }
        self.actionIndex = actionIndex
        self.label = trimmed
    }
}

public protocol NotificationPresenter {
    func show(_ request: NotificationPresentationRequest)
    func remove(identifier: String)
}

public protocol NotificationDismissOutbox {
    @discardableResult
    func send(
        type: String,
        capability: String,
        requiresAck: Bool,
        payload: [String: Any]
    ) -> String?
}

public final class ProtocolSessionNotificationDismissOutbox: NotificationDismissOutbox {
    private let session: ProtocolSession

    public init(session: ProtocolSession) {
        self.session = session
    }

    @discardableResult
    public func send(
        type: String,
        capability: String,
        requiresAck: Bool,
        payload: [String: Any]
    ) -> String? {
        session.send(
            type: type,
            payload: payload,
            requiresAck: requiresAck,
            capability: capability
        )
    }
}

public final class NotificationDismissSender {
    private let outbox: NotificationDismissOutbox

    public init(outbox: NotificationDismissOutbox) {
        self.outbox = outbox
    }

    @discardableResult
    public func requestDismiss(sbnKey: String) -> String? {
        guard !sbnKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return nil
        }
        return outbox.send(
            type: NotificationMirrorProtocol.typeDismissRequest,
            capability: NotificationMirrorProtocol.capability,
            requiresAck: true,
            payload: ["sbnKey": sbnKey]
        )
    }
}

public final class NotificationReplySender {
    private let outbox: NotificationDismissOutbox

    public init(outbox: NotificationDismissOutbox) {
        self.outbox = outbox
    }

    @discardableResult
    public func requestReply(sbnKey: String, actionIndex: Int, text: String) -> String? {
        let trimmedKey = sbnKey.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedText = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedKey.isEmpty, actionIndex >= 0, !trimmedText.isEmpty else {
            return nil
        }
        return outbox.send(
            type: NotificationMirrorProtocol.typeReplyRequest,
            capability: NotificationMirrorProtocol.capability,
            requiresAck: true,
            payload: ["sbnKey": trimmedKey, "actionIndex": actionIndex, "text": trimmedText]
        )
    }
}

public enum NotificationMirrorAction: Equatable {
    case shown(identifier: String, sbnKey: String)
    case removed(identifier: String, sbnKey: String)
    case dismissResult(sbnKey: String, success: Bool, errorCode: String?)
    case replyResult(sbnKey: String, success: Bool, errorCode: String?)
}

public struct AndroidNotificationPayload: Equatable {
    public let sbnKey: String
    public let packageName: String
    public let title: String?
    public let text: String?
    public let category: String?
    public let replyActions: [NotificationReplyAction]

    public init(payload: [String: Any]) throws {
        guard let sbnKey = payload["sbnKey"] as? String, !sbnKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw NotificationMirrorError.invalidPayload("sbnKey must be a non-empty string")
        }
        guard let packageName = payload["packageName"] as? String,
              !packageName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        else {
            throw NotificationMirrorError.invalidPayload("packageName must be a non-empty string")
        }
        self.sbnKey = sbnKey
        self.packageName = packageName
        self.title = Self.optionalString(payload["title"])
        self.text = Self.optionalString(payload["text"])
        self.category = Self.optionalString(payload["category"])
        self.replyActions = try Self.replyActions(payload["replyActions"])
    }

    public var presentationRequest: NotificationPresentationRequest {
        NotificationPresentationRequest(
            identifier: Self.identifier(for: sbnKey),
            sbnKey: sbnKey,
            packageName: packageName,
            title: title ?? packageName,
            body: text ?? "",
            category: category,
            replyActions: replyActions
        )
    }

    public static func identifier(for sbnKey: String) -> String {
        "hyphen.notification.\(sbnKey)"
    }

    private static func optionalString(_ value: Any?) -> String? {
        guard let text = value as? String else { return nil }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    private static func replyActions(_ value: Any?) throws -> [NotificationReplyAction] {
        guard let value else { return [] }
        guard let rawActions = value as? [[String: Any]] else {
            throw NotificationMirrorError.invalidPayload("replyActions must be an array")
        }
        return try rawActions.map { action in
            guard let actionIndex = action["actionIndex"] as? Int else {
                throw NotificationMirrorError.invalidPayload("reply actionIndex must be integer")
            }
            guard let label = action["label"] as? String else {
                throw NotificationMirrorError.invalidPayload("reply label must be string")
            }
            return try NotificationReplyAction(actionIndex: actionIndex, label: label)
        }
    }
}

public final class NotificationMirrorReceiver {
    private let presenter: NotificationPresenter

    public init(presenter: NotificationPresenter) {
        self.presenter = presenter
    }

    @discardableResult
    public func handle(_ envelope: Envelope) throws -> NotificationMirrorAction? {
        switch envelope.type {
        case NotificationMirrorProtocol.typePosted, NotificationMirrorProtocol.typeUpdated:
            try requireNotificationsCapability(envelope)
            let payload = try AndroidNotificationPayload(payload: envelope.payload)
            let request = payload.presentationRequest
            presenter.show(request)
            return .shown(identifier: request.identifier, sbnKey: payload.sbnKey)

        case NotificationMirrorProtocol.typeRemoved:
            try requireNotificationsCapability(envelope)
            guard let sbnKey = envelope.payload["sbnKey"] as? String,
                  !sbnKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            else {
                throw NotificationMirrorError.invalidPayload("sbnKey must be a non-empty string")
            }
            let identifier = AndroidNotificationPayload.identifier(for: sbnKey)
            presenter.remove(identifier: identifier)
            return .removed(identifier: identifier, sbnKey: sbnKey)

        case NotificationMirrorProtocol.typeDismissResult:
            try requireNotificationsCapability(envelope)
            guard let sbnKey = envelope.payload["sbnKey"] as? String,
                  !sbnKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            else {
                throw NotificationMirrorError.invalidPayload("sbnKey must be a non-empty string")
            }
            guard let success = envelope.payload["success"] as? Bool else {
                throw NotificationMirrorError.invalidPayload("success must be boolean")
            }
            let errorCode = envelope.payload["errorCode"] as? String
            return .dismissResult(sbnKey: sbnKey, success: success, errorCode: errorCode)

        case NotificationMirrorProtocol.typeReplyResult:
            try requireNotificationsCapability(envelope)
            guard let sbnKey = envelope.payload["sbnKey"] as? String,
                  !sbnKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            else {
                throw NotificationMirrorError.invalidPayload("sbnKey must be a non-empty string")
            }
            guard let success = envelope.payload["success"] as? Bool else {
                throw NotificationMirrorError.invalidPayload("success must be boolean")
            }
            let errorCode = envelope.payload["errorCode"] as? String
            return .replyResult(sbnKey: sbnKey, success: success, errorCode: errorCode)

        default:
            return nil
        }
    }

    private func requireNotificationsCapability(_ envelope: Envelope) throws {
        guard envelope.capability == NotificationMirrorProtocol.capability else {
            throw NotificationMirrorError.unsupportedCapability(envelope.capability)
        }
    }
}

public final class UserNotificationCenterPresenter: NSObject, NotificationPresenter, UNUserNotificationCenterDelegate {
    private static let categoryIdentifier = "hyphen.notification.mirror"
    private static let replyCategoryIdentifier = "hyphen.notification.mirror.reply"
    private static let replyActionIdentifier = "hyphen.notification.reply"
    private let center: UNUserNotificationCenter
    private var onDismiss: ((String) -> Void)?
    private var onReply: ((String, Int, String) -> Void)?

    public init(
        center: UNUserNotificationCenter = .current(),
        onDismiss: ((String) -> Void)? = nil,
        onReply: ((String, Int, String) -> Void)? = nil
    ) {
        self.center = center
        self.onDismiss = onDismiss
        self.onReply = onReply
        super.init()
        center.delegate = self
        center.setNotificationCategories([
            UNNotificationCategory(
                identifier: Self.categoryIdentifier,
                actions: [],
                intentIdentifiers: [],
                options: [.customDismissAction]
            ),
            UNNotificationCategory(
                identifier: Self.replyCategoryIdentifier,
                actions: [
                    UNTextInputNotificationAction(
                        identifier: Self.replyActionIdentifier,
                        title: "Reply",
                        options: [],
                        textInputButtonTitle: "Send",
                        textInputPlaceholder: "Reply"
                    ),
                ],
                intentIdentifiers: [],
                options: [.customDismissAction]
            ),
        ])
    }

    public func setDismissHandler(_ handler: ((String) -> Void)?) {
        onDismiss = handler
    }

    public func setReplyHandler(_ handler: ((String, Int, String) -> Void)?) {
        onReply = handler
    }

    public func show(_ request: NotificationPresentationRequest) {
        ensureAuthorized { [center] in
            let content = UNMutableNotificationContent()
            content.title = request.title
            content.body = request.body
            content.subtitle = request.packageName
            content.threadIdentifier = request.sbnKey
            if request.replyActions.isEmpty {
                content.categoryIdentifier = Self.categoryIdentifier
            } else {
                content.categoryIdentifier = Self.replyCategoryIdentifier
            }
            var userInfo: [String: Any] = ["sbnKey": request.sbnKey]
            if let replyAction = request.replyActions.first {
                userInfo["replyActionIndex"] = replyAction.actionIndex
            }
            content.userInfo = userInfo
            center.add(
                UNNotificationRequest(identifier: request.identifier, content: content, trigger: nil)
            )
        }
    }

    public func remove(identifier: String) {
        center.removePendingNotificationRequests(withIdentifiers: [identifier])
        center.removeDeliveredNotifications(withIdentifiers: [identifier])
    }

    private func ensureAuthorized(_ action: @escaping () -> Void) {
        center.getNotificationSettings { [center] settings in
            switch settings.authorizationStatus {
            case .authorized, .provisional:
                action()
            case .notDetermined:
                center.requestAuthorization(options: [.alert, .sound]) { granted, _ in
                    if granted { action() }
                }
            case .denied:
                break
            @unknown default:
                break
            }
        }
    }

    public func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let userInfo = response.notification.request.content.userInfo
        if response.actionIdentifier == Self.replyActionIdentifier,
           let response = response as? UNTextInputNotificationResponse,
           let sbnKey = userInfo["sbnKey"] as? String,
           let actionIndex = userInfo["replyActionIndex"] as? Int {
            onReply?(sbnKey, actionIndex, response.userText)
        } else if response.actionIdentifier == UNNotificationDismissActionIdentifier,
                  let sbnKey = userInfo["sbnKey"] as? String {
            onDismiss?(sbnKey)
        }
        completionHandler()
    }
}
