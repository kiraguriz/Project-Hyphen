import Foundation
import HyphenNotifications
import HyphenText
import HyphenTransfer

// Phase 0 — the observable "connection + activity" model (frontend UX plan
// §2). This file is the UI-agnostic core: pure value types plus a bounded,
// in-memory `ActivityFeed` and the structured `ActivityEvent` the backend
// emits. `HyphenAppModel` (the ObservableObject) wraps these for SwiftUI.
//
// Convention: notification history is NEVER persisted (CLAUDE.md). The feed is
// in-memory only and capped; `HyphenAppModel` holds the single instance for a
// running session and it is dropped on quit.

// MARK: - Connection

/// Live connection snapshot the popover header renders. `latencyMs` stays
/// `nil` until a real RTT source exists — we do not fabricate a latency number.
struct ConnectionSnapshot: Equatable {
    var state: HyphenConnectionState
    var isPaired: Bool
    var peerName: String?
    var latencyMs: Int?

    static let unpaired = ConnectionSnapshot(
        state: .suspended,
        isPaired: false,
        peerName: nil,
        latencyMs: nil
    )
}

// MARK: - Feed item payloads

enum TransferDirection: Equatable {
    case incoming
    case outgoing
}

enum TransferFeedStatus: Equatable {
    case active
    case completed
    case cancelled
}

enum TextFeedKind: Equatable {
    case text
    case url
}

enum TextFeedDirection: Equatable {
    case sent
    case received
}

/// A mirrored notification, post privacy-scrub (the body/app are already
/// redacted by `NotificationPrivacyPolicy` before reaching the feed).
struct NotificationFeedItem: Equatable {
    /// `StatusBarNotification.getKey()` — the durable notification identity.
    let sbnKey: String
    /// `hyphen.notification.<sbnKey>` — what the system notification uses.
    let identifier: String
    let appName: String
    let title: String
    let body: String
    let replyActions: [NotificationReplyAction]
}

struct TransferFeedItem: Equatable {
    let fileId: String
    let filename: String
    let direction: TransferDirection
    var completedBytes: Int64
    var totalBytes: Int64
    var completedChunks: Int
    var totalChunks: Int
    var status: TransferFeedStatus
    var verified: Bool

    /// 0...1 progress, or nil when the total size is unknown.
    var fraction: Double? {
        guard totalBytes > 0 else { return nil }
        return min(1, max(0, Double(completedBytes) / Double(totalBytes)))
    }
}

struct TextFeedItem: Equatable {
    let kind: TextFeedKind
    let direction: TextFeedDirection
    let value: String
}

/// A pairing / session lifecycle note (e.g. "已配对"). Short audit line, no
/// interactive affordances.
struct PairingFeedItem: Equatable {
    let message: String
}

// MARK: - Activity item

enum ActivityKind: Equatable {
    case notification(NotificationFeedItem)
    case transfer(TransferFeedItem)
    case text(TextFeedItem)
    case pairing(PairingFeedItem)
}

struct ActivityItem: Identifiable, Equatable {
    let id: UUID
    var timestamp: Date
    var kind: ActivityKind
    /// Identity for in-place updates. Notifications dedupe by `identifier`,
    /// transfers by `transfer:<fileId>`. `nil` means always-append (text,
    /// pairing notes).
    var dedupeKey: String?

    init(id: UUID = UUID(), timestamp: Date, kind: ActivityKind, dedupeKey: String? = nil) {
        self.id = id
        self.timestamp = timestamp
        self.kind = kind
        self.dedupeKey = dedupeKey
    }
}

/// A day bucket for the timeline. `title` is a display label ("今天"/"昨天"/date).
struct ActivityDay: Identifiable, Equatable {
    let id: String
    let title: String
    let items: [ActivityItem]
}

// MARK: - Activity feed

/// Bounded, newest-first, in-memory activity log. Pure value type so the
/// append/upsert/bounding/grouping logic is unit-testable without SwiftUI.
struct ActivityFeed: Equatable {
    private(set) var items: [ActivityItem]
    let capacity: Int

    init(capacity: Int = 200, items: [ActivityItem] = []) {
        self.capacity = max(1, capacity)
        self.items = items
    }

    /// Insert or update by `dedupeKey`. A keyed item replaces the existing one
    /// (preserving its id so SwiftUI identity is stable) and moves to the front;
    /// an unkeyed item is always prepended. The list is trimmed to `capacity`.
    mutating func upsert(_ item: ActivityItem) {
        var item = item
        if let key = item.dedupeKey,
           let existingIndex = items.firstIndex(where: { $0.dedupeKey == key }) {
            item = ActivityItem(
                id: items[existingIndex].id,
                timestamp: item.timestamp,
                kind: item.kind,
                dedupeKey: key
            )
            items.remove(at: existingIndex)
        }
        items.insert(item, at: 0)
        if items.count > capacity {
            items.removeLast(items.count - capacity)
        }
    }

    /// Remove a mirrored notification by its system identifier (Android cleared
    /// it). No-op if absent.
    mutating func removeNotification(identifier: String) {
        items.removeAll { item in
            if case .notification(let note) = item.kind {
                return note.identifier == identifier
            }
            return false
        }
    }

    mutating func clear() {
        items.removeAll()
    }

    /// Group newest-first items into day buckets relative to `now`.
    func grouped(relativeTo now: Date, calendar: Calendar = .current) -> [ActivityDay] {
        guard !items.isEmpty else { return [] }
        var order: [String] = []
        var buckets: [String: [ActivityItem]] = [:]
        for item in items {
            let key = Self.dayKey(for: item.timestamp, calendar: calendar)
            if buckets[key] == nil {
                buckets[key] = []
                order.append(key)
            }
            buckets[key]?.append(item)
        }
        return order.map { key in
            ActivityDay(
                id: key,
                title: Self.dayTitle(forKey: key, now: now, calendar: calendar),
                items: buckets[key] ?? []
            )
        }
    }

    private static func dayKey(for date: Date, calendar: Calendar) -> String {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return "\(c.year ?? 0)-\(c.month ?? 0)-\(c.day ?? 0)"
    }

    private static func dayTitle(forKey key: String, now: Date, calendar: Calendar) -> String {
        let todayKey = dayKey(for: now, calendar: calendar)
        if key == todayKey { return L("feed.today") }
        if let yesterday = calendar.date(byAdding: .day, value: -1, to: now),
           key == dayKey(for: yesterday, calendar: calendar) {
            return L("feed.yesterday")
        }
        let parts = key.split(separator: "-").compactMap { Int($0) }
        if parts.count == 3 {
            return L("feed.dateMonthDay", parts[1], parts[2])
        }
        return key
    }
}

// MARK: - Activity event

/// The structured event the backend (`PairingController`, `AppDelegate`) emits
/// into the model. This is the contract that replaces the legacy `onStatus`
/// string lines as the UI's source of truth.
enum ActivityEvent {
    /// Pairing identity changed (launch / pair / forget / reset). Drives the
    /// not-paired vs paired presentation.
    case peerChanged(isPaired: Bool, peerName: String?)
    /// Live connection state changed (session connect / close / reconnect).
    case connectionStateChanged(HyphenConnectionState, latencyMs: Int?)
    /// A notification was mirrored (posted or updated) — already privacy-scrubbed.
    case notificationPosted(NotificationPresentationRequest, at: Date)
    /// Android cleared a mirrored notification; remove it from the feed.
    case notificationRemoved(identifier: String, at: Date)
    /// Transfer progress (start or chunk advance), in either direction.
    case transferProgress(TransferProgress, direction: TransferDirection, at: Date)
    /// A transfer finished; `verified` reflects the SHA-256 check on receive.
    case transferCompleted(
        fileId: String,
        filename: String,
        sizeBytes: Int64,
        direction: TransferDirection,
        verified: Bool,
        at: Date
    )
    /// A transfer was cancelled (either side).
    case transferCancelled(fileId: String, at: Date)
    /// A text/link crossed the session.
    case text(kind: TextFeedKind, direction: TextFeedDirection, value: String, at: Date)
    /// A pairing / session lifecycle note for the audit timeline.
    case pairingNote(message: String, at: Date)
}
