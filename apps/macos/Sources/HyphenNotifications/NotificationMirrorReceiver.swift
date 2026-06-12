import Foundation
import HyphenTransport
import UserNotifications

public enum NotificationMirrorProtocol {
    public static let capability = "notifications.v1"
    public static let typePosted = "notification.posted"
    public static let typeUpdated = "notification.updated"
    public static let typeRemoved = "notification.removed"
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

    public init(
        identifier: String,
        sbnKey: String,
        packageName: String,
        title: String,
        body: String,
        category: String?
    ) {
        self.identifier = identifier
        self.sbnKey = sbnKey
        self.packageName = packageName
        self.title = title
        self.body = body
        self.category = category
    }
}

public protocol NotificationPresenter {
    func show(_ request: NotificationPresentationRequest)
    func remove(identifier: String)
}

public enum NotificationMirrorAction: Equatable {
    case shown(identifier: String, sbnKey: String)
    case removed(identifier: String, sbnKey: String)
}

public struct AndroidNotificationPayload: Equatable {
    public let sbnKey: String
    public let packageName: String
    public let title: String?
    public let text: String?
    public let category: String?

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
    }

    public var presentationRequest: NotificationPresentationRequest {
        NotificationPresentationRequest(
            identifier: Self.identifier(for: sbnKey),
            sbnKey: sbnKey,
            packageName: packageName,
            title: title ?? packageName,
            body: text ?? "",
            category: category
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

public final class UserNotificationCenterPresenter: NotificationPresenter {
    private let center: UNUserNotificationCenter

    public init(center: UNUserNotificationCenter = .current()) {
        self.center = center
    }

    public func show(_ request: NotificationPresentationRequest) {
        ensureAuthorized { [center] in
            let content = UNMutableNotificationContent()
            content.title = request.title
            content.body = request.body
            content.subtitle = request.packageName
            content.threadIdentifier = request.sbnKey
            if let category = request.category {
                content.categoryIdentifier = category
            }
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
}
