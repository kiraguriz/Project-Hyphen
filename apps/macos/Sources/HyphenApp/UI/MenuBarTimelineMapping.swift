import SwiftUI
import HyphenNotifications

// Bridges the UI-agnostic activity model (`ActivityDay`/`ActivityItem`) to the
// popover's display vocabulary (`MenuBarTimelineDay`/`MenuBarTimelineItem`).
// The mapping is recomputed on every render, so capability/connection changes
// (e.g. reply becoming available once connected) take effect immediately.

enum MenuBarTimelineMapper {

    /// Build the popover timeline from grouped model days. `connected` gates the
    /// interactive reply affordance — you can't reply to a phone you're not
    /// connected to.
    static func timeline(from days: [ActivityDay], connected: Bool) -> [MenuBarTimelineDay] {
        days.map { day in
            MenuBarTimelineDay(
                id: day.id,
                title: day.title,
                items: day.items.map { item in
                    MenuBarTimelineItem(id: item.id, kind: kind(for: item, connected: connected))
                }
            )
        }
    }

    private static func kind(for item: ActivityItem, connected: Bool) -> MenuBarTimelineKind {
        let time = timeFormatter.string(from: item.timestamp)
        switch item.kind {
        case .notification(let note):
            return notificationKind(note, time: time, connected: connected)
        case .transfer(let transfer):
            return transferKind(transfer, time: time)
        case .text(let text):
            return textKind(text, time: time)
        case .pairing(let pairing):
            return .pairing(text: pairing.message, time: time)
        }
    }

    private static func notificationKind(
        _ note: NotificationFeedItem,
        time: String,
        connected: Bool
    ) -> MenuBarTimelineKind {
        let info = AppBadgeCatalog.info(package: note.appName, fallbackTitle: note.title)
        let badge = MenuBarAppBadge(label: info.label, tint: info.tint)
        // Reply uses the same routing the system notification uses, so the
        // action we surface matches what RemoteInput would target.
        let route = NotificationReplyRouting.routes(for: note.replyActions).first
        if connected, let route {
            let sender = note.title.isEmpty || note.title == info.displayName
                ? info.displayName
                : "\(info.displayName) · \(note.title)"
            return .reply(
                app: badge,
                sender: sender,
                body: note.body,
                time: time,
                ref: MenuBarNotificationRef(
                    sbnKey: note.sbnKey,
                    appName: info.displayName,
                    sender: note.title.isEmpty ? info.displayName : note.title,
                    replyActionIndex: route.actionIndex,
                    replyActionId: route.actionId
                )
            )
        }
        let title = note.title.isEmpty ? info.displayName : note.title
        let detail = note.body.isEmpty ? info.displayName : note.body
        return .notification(app: badge, title: title, detail: detail, time: time)
    }

    private static func transferKind(_ t: TransferFeedItem, time: String) -> MenuBarTimelineKind {
        let total = byteFormatter.string(fromByteCount: t.totalBytes)
        switch (t.direction, t.status) {
        case (.incoming, .active):
            return .fileReceived(name: t.filename, meta: activeMeta(t, verb: L("transfer.receiving")), time: time, progress: t.fraction)
        case (.incoming, .completed):
            let meta = t.verified ? L("transfer.received.verified", total) : L("transfer.received", total)
            return .fileReceived(name: t.filename, meta: meta, time: time, progress: nil)
        case (.incoming, .cancelled):
            return .fileReceived(name: t.filename, meta: L("transfer.cancelled"), time: time, progress: nil)
        case (.outgoing, .active):
            return .fileSent(name: t.filename, meta: activeMeta(t, verb: L("transfer.sending")), time: time, progress: t.fraction)
        case (.outgoing, .completed):
            return .fileSent(name: t.filename, meta: L("transfer.sent", total), time: time, progress: nil)
        case (.outgoing, .cancelled):
            return .fileSent(name: t.filename, meta: L("transfer.cancelled"), time: time, progress: nil)
        }
    }

    private static func activeMeta(_ t: TransferFeedItem, verb: String) -> String {
        guard t.totalBytes > 0 else { return verb }
        let done = byteFormatter.string(fromByteCount: t.completedBytes)
        let total = byteFormatter.string(fromByteCount: t.totalBytes)
        return "\(done) / \(total)"
    }

    private static func textKind(_ t: TextFeedItem, time: String) -> MenuBarTimelineKind {
        switch t.direction {
        case .sent:
            return .linkSent(
                title: t.kind == .url ? L("text.linkSent") : L("text.textSent"),
                detail: t.value,
                time: time
            )
        case .received:
            return .linkReceived(
                title: t.kind == .url ? L("text.linkOpened") : L("text.textCopied"),
                detail: t.value,
                time: time
            )
        }
    }

    private static let timeFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "H:mm"
        return f
    }()

    private static let byteFormatter: ByteCountFormatter = {
        let f = ByteCountFormatter()
        f.countStyle = .file
        return f
    }()
}

// MARK: - App badge catalog

/// Maps an Android package to a glyph badge + display name. Known apps get
/// their brand color (theme-independent); everything else gets a neutral tile
/// with the first character of a derived name.
enum AppBadgeCatalog {
    struct Info {
        let label: String
        let tint: Color
        let displayName: String
    }

    private static let known: [String: Info] = [
        "com.tencent.mm": Info(label: "微", tint: .hex(0x1f9d57), displayName: "微信"),
        "com.google.android.gm": Info(label: "G", tint: .hex(0xd6453f), displayName: "Gmail"),
        "com.whatsapp": Info(label: "W", tint: .hex(0x25d366), displayName: "WhatsApp"),
        "org.telegram.messenger": Info(label: "T", tint: .hex(0x2aabee), displayName: "Telegram"),
        "com.slack": Info(label: "S", tint: .hex(0x4a154b), displayName: "Slack"),
    ]

    static func info(package: String, fallbackTitle: String) -> Info {
        if let info = known[package] { return info }
        let name = displayName(forPackage: package, fallbackTitle: fallbackTitle)
        let label = String(name.prefix(1)).uppercased()
        return Info(label: label.isEmpty ? "·" : label, tint: .hex(0x6b7280), displayName: name)
    }

    private static func displayName(forPackage package: String, fallbackTitle: String) -> String {
        if package == "Android" { return "Android" }
        // Last dotted segment is usually the app id (e.g. com.foo.bar → bar).
        if let last = package.split(separator: ".").last, !last.isEmpty {
            return last.prefix(1).uppercased() + last.dropFirst()
        }
        return fallbackTitle.isEmpty ? package : fallbackTitle
    }
}

// MARK: - Live host

/// Observes `HyphenAppModel` and renders the popover from live state. This is
/// the runtime entry point (preview/default `MenuBarPopoverView` keeps sample
/// data for design previews).
struct MenuBarPopoverHost: View {
    @ObservedObject var model: HyphenAppModel
    var onPair: () -> Void = {}
    var onSettings: () -> Void = {}
    var onSendText: (String) -> Void = { _ in }
    var onSendFile: () -> Void = {}
    var onReply: (MenuBarNotificationRef) -> Void = { _ in }
    var onDismiss: (MenuBarNotificationRef) -> Void = { _ in }

    var body: some View {
        let connection = model.connection
        let connected = connection.state == .connected
        let name = connection.peerName ?? (connection.isPaired ? L("conn.pairedFallback") : L("conn.notPaired"))
        return MenuBarPopoverView(
            state: connection.state,
            isPaired: connection.isPaired,
            deviceName: name,
            latencyMs: connection.latencyMs,
            days: MenuBarTimelineMapper.timeline(from: model.groupedDays(), connected: connected),
            onPair: onPair,
            onSettings: onSettings,
            onSendText: onSendText,
            onSendFile: onSendFile,
            onReply: onReply,
            onDismiss: onDismiss
        )
    }
}
