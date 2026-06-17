import Foundation
import HyphenNotifications

// Decorates the system notification presenter so every mirrored notification
// that is actually presented also lands in the activity feed. The receiver
// applies the privacy policy BEFORE calling `show`, so the request handed here
// is already scrubbed (existsOnly → "Android notification", hideBody → no body):
// the feed shows exactly what the user permitted, no more.
final class ActivityFeedNotificationPresenter: NotificationPresenter {
    private let wrapped: NotificationPresenter
    private let onShow: (NotificationPresentationRequest) -> Void
    private let onRemove: (String) -> Void

    init(
        wrapped: NotificationPresenter,
        onShow: @escaping (NotificationPresentationRequest) -> Void,
        onRemove: @escaping (String) -> Void
    ) {
        self.wrapped = wrapped
        self.onShow = onShow
        self.onRemove = onRemove
    }

    func show(_ request: NotificationPresentationRequest) {
        onShow(request)
        wrapped.show(request)
    }

    func remove(identifier: String) {
        onRemove(identifier)
        wrapped.remove(identifier: identifier)
    }
}
