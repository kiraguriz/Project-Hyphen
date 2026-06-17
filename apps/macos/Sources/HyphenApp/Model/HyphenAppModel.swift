import Combine
import Foundation
import HyphenNotifications
import HyphenTransfer

// The app-level observable model (frontend UX plan M-A1). Modeled on
// `PairingWindowModel`: an `ObservableObject` the backend feeds via `apply`
// and the SwiftUI surfaces render. It owns the connection snapshot and the
// bounded `ActivityFeed`; `apply` is the single reducer entry.
//
// All mutation must happen on the main thread (SwiftUI requirement). The
// `PairingController` already hops to main before emitting, and `AppDelegate`
// emits from main-thread call sites.
final class HyphenAppModel: ObservableObject {
    @Published private(set) var connection: ConnectionSnapshot
    @Published private(set) var feed: ActivityFeed

    /// Injectable clock so day-grouping is deterministic in tests.
    private let clock: () -> Date

    init(
        connection: ConnectionSnapshot = .unpaired,
        feed: ActivityFeed = ActivityFeed(),
        clock: @escaping () -> Date = Date.init
    ) {
        self.connection = connection
        self.feed = feed
        self.clock = clock
    }

    /// Day-bucketed timeline for the popover, newest day first.
    func groupedDays(now: Date? = nil) -> [ActivityDay] {
        feed.grouped(relativeTo: now ?? clock())
    }

    // MARK: - Reducer

    func apply(_ event: ActivityEvent) {
        switch event {
        case let .peerChanged(isPaired, peerName):
            connection.isPaired = isPaired
            connection.peerName = peerName
            if !isPaired {
                connection.state = .suspended
                connection.latencyMs = nil
            }

        case let .connectionStateChanged(state, latencyMs):
            connection.state = state
            connection.latencyMs = latencyMs

        case let .notificationPosted(request, at):
            let item = NotificationFeedItem(
                sbnKey: request.sbnKey,
                identifier: request.identifier,
                appName: request.packageName,
                title: request.title,
                body: request.body,
                replyActions: request.replyActions
            )
            feed.upsert(
                ActivityItem(
                    timestamp: at,
                    kind: .notification(item),
                    dedupeKey: request.identifier
                )
            )

        case let .notificationRemoved(identifier, _):
            feed.removeNotification(identifier: identifier)

        case let .transferProgress(progress, direction, at):
            feed.upsert(transferItem(progress: progress, direction: direction, at: at))

        case let .transferCompleted(fileId, filename, sizeBytes, direction, verified, at):
            let item = TransferFeedItem(
                fileId: fileId,
                filename: filename,
                direction: direction,
                completedBytes: sizeBytes,
                totalBytes: sizeBytes,
                completedChunks: 0,
                totalChunks: 0,
                status: .completed,
                verified: verified
            )
            feed.upsert(
                ActivityItem(timestamp: at, kind: .transfer(item), dedupeKey: Self.transferKey(fileId))
            )

        case let .transferCancelled(fileId, at):
            // Only reflect a cancel for a transfer we already know about; mark it
            // cancelled in place rather than fabricating a row.
            if let existing = existingTransfer(fileId: fileId) {
                var item = existing
                item.status = .cancelled
                feed.upsert(
                    ActivityItem(timestamp: at, kind: .transfer(item), dedupeKey: Self.transferKey(fileId))
                )
            }

        case let .text(kind, direction, value, at):
            feed.upsert(
                ActivityItem(
                    timestamp: at,
                    kind: .text(TextFeedItem(kind: kind, direction: direction, value: value))
                )
            )

        case let .pairingNote(message, at):
            feed.upsert(
                ActivityItem(timestamp: at, kind: .pairing(PairingFeedItem(message: message)))
            )
        }
    }

    // MARK: - Helpers

    static func transferKey(_ fileId: String) -> String { "transfer:\(fileId)" }

    private func transferItem(progress: TransferProgress, direction: TransferDirection, at: Date) -> ActivityItem {
        let item = TransferFeedItem(
            fileId: progress.fileId,
            filename: progress.filename,
            direction: direction,
            completedBytes: progress.completedBytes,
            totalBytes: progress.totalBytes,
            completedChunks: progress.completedChunks,
            totalChunks: progress.totalChunks,
            status: progress.isComplete ? .completed : .active,
            verified: false
        )
        return ActivityItem(timestamp: at, kind: .transfer(item), dedupeKey: Self.transferKey(progress.fileId))
    }

    private func existingTransfer(fileId: String) -> TransferFeedItem? {
        let key = Self.transferKey(fileId)
        for item in feed.items {
            if item.dedupeKey == key, case .transfer(let transfer) = item.kind {
                return transfer
            }
        }
        return nil
    }
}
