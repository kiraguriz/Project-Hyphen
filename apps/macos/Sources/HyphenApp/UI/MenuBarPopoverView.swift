import SwiftUI
import HyphenNotifications

// Menu-bar popover · Variant 3 (时间线 / Timeline — the recommended direction
// from Section B of the design). A connection header, a filter tab row, a
// day-grouped activity timeline, and a footer. Built on the shared palette,
// typography, and component foundation. Every new type is `MenuBar`-prefixed
// to avoid collisions with sibling popover modules.
//
// The timeline is bound to `HyphenAppModel` at runtime via `MenuBarPopoverHost`;
// the `MenuBarTimelineDay.sample` data below is preview-only.

// MARK: - Filter tabs

enum MenuBarTimelineFilter: String, CaseIterable, Identifiable {
    case all
    case notifications
    case transfers

    var id: String { rawValue }

    /// Localized pill label (the raw value stays a stable ASCII identifier).
    var title: String {
        switch self {
        case .all: return L("popover.filter.all")
        case .notifications: return L("popover.filter.notifications")
        case .transfers: return L("popover.filter.transfers")
        }
    }
}

// MARK: - Notification routing reference

/// The identity a timeline notification row needs to route a reply/dismiss back
/// through `PairingController`. `replyAction*` is the first routable reply action
/// (RemoteInput); `nil` means the notification has no reply affordance.
struct MenuBarNotificationRef: Equatable {
    let sbnKey: String
    let appName: String
    let sender: String
    let replyActionIndex: Int?
    let replyActionId: String?

    var canReply: Bool { replyActionIndex != nil }
}

// MARK: - Timeline model

enum MenuBarTimelineKind {
    /// A mirrored notification offering a quick reply (e.g. WeChat).
    case reply(app: MenuBarAppBadge, sender: String, body: String, time: String, ref: MenuBarNotificationRef)
    /// A passive mirrored notification (e.g. Gmail) — no interactive affordance.
    case notification(app: MenuBarAppBadge, title: String, detail: String, time: String)
    /// An incoming file. `progress` non-nil → still receiving (shows a bar).
    case fileReceived(name: String, meta: String, time: String, progress: Double?)
    /// An outgoing file. `progress` non-nil → still sending (shows a bar).
    case fileSent(name: String, meta: String, time: String, progress: Double?)
    /// An outbound text / link send.
    case linkSent(title: String, detail: String, time: String)
    /// An inbound text / link the user accepted.
    case linkReceived(title: String, detail: String, time: String)
    /// A pairing / session audit note.
    case pairing(text: String, time: String)
}

struct MenuBarAppBadge {
    let label: String
    let tint: Color
}

struct MenuBarTimelineItem: Identifiable {
    let id: UUID
    let kind: MenuBarTimelineKind

    init(id: UUID = UUID(), kind: MenuBarTimelineKind) {
        self.id = id
        self.kind = kind
    }

    /// Which filter tabs this item belongs to.
    var matches: Set<MenuBarTimelineFilter> {
        switch kind {
        case .reply, .notification:
            return [.all, .notifications]
        case .fileReceived, .fileSent, .linkSent, .linkReceived:
            return [.all, .transfers]
        case .pairing:
            return [.all]
        }
    }
}

struct MenuBarTimelineDay: Identifiable {
    let id: String
    let title: String
    let items: [MenuBarTimelineItem]

    init(id: String = UUID().uuidString, title: String, items: [MenuBarTimelineItem]) {
        self.id = id
        self.title = title
        self.items = items
    }
}

extension MenuBarTimelineDay {
    /// Sample timeline matching the design's Variant 3 content (preview only).
    static let sample: [MenuBarTimelineDay] = [
        MenuBarTimelineDay(
            title: "今天",
            items: [
                MenuBarTimelineItem(kind: .reply(
                    app: MenuBarAppBadge(label: "微", tint: .hex(0x1f9d57)),
                    sender: "微信 · 张伟",
                    body: "晚上一起吃饭吗？老地方见。",
                    time: "9:41",
                    ref: MenuBarNotificationRef(
                        sbnKey: "sample", appName: "微信", sender: "张伟",
                        replyActionIndex: 0, replyActionId: nil
                    )
                )),
                MenuBarTimelineItem(kind: .fileReceived(
                    name: "设计稿.pdf",
                    meta: "8.2 MB · SHA‑256 校验通过",
                    time: "9:32",
                    progress: nil
                )),
                MenuBarTimelineItem(kind: .linkSent(
                    title: "链接已发送",
                    detail: "github.com/hyphen/spec",
                    time: "9:21"
                )),
            ]
        ),
        MenuBarTimelineDay(
            title: "昨天",
            items: [
                MenuBarTimelineItem(kind: .notification(
                    app: MenuBarAppBadge(label: "G", tint: .hex(0xd6453f)),
                    title: "Gmail",
                    detail: "您的收据 · Figma 订阅",
                    time: "18:03"
                )),
            ]
        ),
    ]
}

// MARK: - Entry view

struct MenuBarPopoverView: View {
    let state: HyphenConnectionState
    let isPaired: Bool
    let deviceName: String
    let latencyMs: Int?
    let days: [MenuBarTimelineDay]
    let onPair: () -> Void
    let onSettings: () -> Void
    let onSendText: (String) -> Void
    let onSendFile: () -> Void
    let onReply: (MenuBarNotificationRef) -> Void
    let onDismiss: (MenuBarNotificationRef) -> Void

    @State private var filter: MenuBarTimelineFilter = .all
    @State private var composing = false
    @State private var draft = ""
    @FocusState private var composeFocused: Bool
    @Environment(\.hyphenPalette) private var p

    init(
        state: HyphenConnectionState = .connected,
        isPaired: Bool = true,
        deviceName: String = "Pixel 8 Pro",
        latencyMs: Int? = nil,
        days: [MenuBarTimelineDay] = MenuBarTimelineDay.sample,
        onPair: @escaping () -> Void = {},
        onSettings: @escaping () -> Void = {},
        onSendText: @escaping (String) -> Void = { _ in },
        onSendFile: @escaping () -> Void = {},
        onReply: @escaping (MenuBarNotificationRef) -> Void = { _ in },
        onDismiss: @escaping (MenuBarNotificationRef) -> Void = { _ in }
    ) {
        self.state = state
        self.isPaired = isPaired
        self.deviceName = deviceName
        self.latencyMs = latencyMs
        self.days = days
        self.onPair = onPair
        self.onSettings = onSettings
        self.onSendText = onSendText
        self.onSendFile = onSendFile
        self.onReply = onReply
        self.onDismiss = onDismiss
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            if isPaired {
                filterRow
                timeline
                if composing {
                    composeSheet
                } else {
                    footer
                }
            } else {
                notPairedBody
            }
        }
        .frame(width: 344)
        .background(p.surface)
        .clipShape(RoundedRectangle(cornerRadius: 13))
        .overlay(RoundedRectangle(cornerRadius: 13).strokeBorder(p.hair, lineWidth: 1))
        .hyphenThemed()
        .hyphenDynamicTypeClamp()
    }

    // MARK: Header

    private var header: some View {
        HStack(spacing: 0) {
            HStack(spacing: 9) {
                StatusDot(state: state)
                    .accessibilityHidden(true)
                Text(deviceName)
                    .font(.hyphenBody(13, weight: .semibold))
                    .foregroundColor(p.text)
                if let latencyMs {
                    Text("· \(latencyMs)ms")
                        .font(.hyphenMono(13))
                        .foregroundColor(p.dim)
                }
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel(L("popover.header.axLabel", deviceName, state.title))
            Spacer(minLength: 8)
            HStack(spacing: 7) {
                MenuBarIconButton(glyph: "＋", kind: .accent, action: onPair)
                    .accessibilityLabel(L("popover.pairNewDevice"))
                MenuBarIconButton(glyph: "⚙", kind: .hairline, action: onSettings)
                    .accessibilityLabel(L("popover.settings"))
            }
        }
        .padding(.horizontal, 15)
        .padding(.top, 12)
        .padding(.bottom, 10)
    }

    // MARK: Not-paired empty state

    private var notPairedBody: some View {
        VStack(spacing: 12) {
            Text("⌁")
                .font(.hyphenBody(34, weight: .regular))
                .foregroundColor(p.faint)
            Text(L("popover.notPaired.title"))
                .font(.hyphenBody(14, weight: .semibold))
                .foregroundColor(p.text)
            Text(L("popover.notPaired.detail"))
                .font(.hyphenBody(12))
                .foregroundColor(p.dim)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            Button(action: onPair) {
                Text(L("popover.pairNewDevice"))
            }
            .buttonStyle(AccentButtonStyle(fontSize: 13))
            .padding(.top, 2)
        }
        .padding(.horizontal, 24)
        .padding(.top, 6)
        .padding(.bottom, 22)
        .frame(maxWidth: .infinity)
        .overlay(Hairline(), alignment: .top)
    }

    // MARK: Filter tabs

    private var filterRow: some View {
        HStack(spacing: 3) {
            ForEach(MenuBarTimelineFilter.allCases) { tab in
                MenuBarFilterPill(
                    title: tab.title,
                    isActive: filter == tab
                ) { filter = tab }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 12)
        .padding(.bottom, 10)
    }

    // MARK: Timeline

    private var timeline: some View {
        let days = visibleDays
        return ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if days.isEmpty {
                    emptyState
                } else {
                    ForEach(Array(days.enumerated()), id: \.element.id) { index, day in
                        Text(day.title)
                            .font(.hyphenBody(11, weight: .semibold))
                            .foregroundColor(p.faint)
                            .padding(.horizontal, 8)
                            .padding(.top, index == 0 ? 4 : 8)
                            .padding(.bottom, 4)

                        ForEach(day.items) { item in
                            MenuBarTimelineRow(
                                item: item,
                                onReply: onReply,
                                onDismiss: onDismiss
                            )
                        }
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 8)
            .padding(.bottom, 4)
        }
        .frame(maxHeight: 330)
    }

    private var emptyState: some View {
        VStack(spacing: 6) {
            Text(emptyTitle)
                .font(.hyphenBody(12, weight: .semibold))
                .foregroundColor(p.dim)
            Text(emptySubtitle)
                .font(.hyphenBody(11))
                .foregroundColor(p.faint)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, minHeight: 96)
        .padding(.horizontal, 16)
    }

    private var emptyTitle: String {
        switch state {
        case .connected, .degraded: return L("popover.empty.title.idle")
        case .reconnecting: return L("popover.empty.title.reconnecting")
        case .discovering: return L("popover.empty.title.discovering")
        case .sleeping: return L("popover.empty.title.sleeping")
        case .suspended: return L("popover.empty.title.suspended")
        }
    }

    private var emptySubtitle: String {
        switch state {
        case .connected: return L("popover.empty.sub.connected")
        case .degraded: return L("popover.empty.sub.degraded")
        case .reconnecting: return L("popover.empty.sub.reconnecting")
        case .discovering: return L("popover.empty.sub.discovering")
        case .sleeping: return L("popover.empty.sub.sleeping")
        case .suspended: return L("popover.empty.sub.suspended")
        }
    }

    private var visibleDays: [MenuBarTimelineDay] {
        days.compactMap { day in
            let items = day.items.filter { $0.matches.contains(filter) }
            return items.isEmpty ? nil : MenuBarTimelineDay(id: day.id, title: day.title, items: items)
        }
    }

    // MARK: Footer

    private var footer: some View {
        HStack {
            HStack(spacing: 6) {
                Circle().fill(state.color(p)).frame(width: 7, height: 7)
                Text(state == .connected ? L("popover.footer.liveMirror") : state.title)
                    .font(.hyphenBody(12))
                    .foregroundColor(p.dim)
            }
            Spacer()
            Button {
                draft = ""
                composing = true
                composeFocused = true
            } label: {
                Text(L("popover.footer.send"))
                    .font(.hyphenBody(12, weight: .semibold))
                    .foregroundColor(state == .connected ? p.accent : p.faint)
            }
            .buttonStyle(.plain)
            .disabled(state != .connected)
            .accessibilityLabel(L("popover.footer.sendAxLabel"))
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(p.surface)
        .overlay(Hairline(), alignment: .top)
    }

    // MARK: Compose sheet (M-A4 — send promoted into the popover)

    private var composeSheet: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(L("popover.compose.sendTo", deviceName))
                    .font(.hyphenBody(13, weight: .semibold))
                    .foregroundColor(p.text)
                Spacer()
                Button {
                    composing = false
                    draft = ""
                } label: {
                    Text(L("common.cancel"))
                        .font(.hyphenBody(12))
                        .foregroundColor(p.dim)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(L("popover.compose.cancelAxLabel"))
            }
            TextField(L("popover.compose.placeholder"), text: $draft)
                .textFieldStyle(.plain)
                .font(.hyphenBody(13))
                .foregroundColor(p.text)
                .padding(.vertical, 8)
                .padding(.horizontal, 10)
                .background(p.surface2)
                .overlay(RoundedRectangle(cornerRadius: 9).strokeBorder(p.hair2, lineWidth: 1))
                .clipShape(RoundedRectangle(cornerRadius: 9))
                .focused($composeFocused)
                .onSubmit(submitDraft)
            HStack(spacing: 8) {
                Button(action: submitDraft) {
                    Text(L("popover.compose.sendText"))
                }
                .buttonStyle(AccentButtonStyle(fontSize: 12))
                .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                Button {
                    composing = false
                    draft = ""
                    onSendFile()
                } label: {
                    Text(L("popover.compose.sendFile"))
                }
                .buttonStyle(SecondaryButtonStyle(fontSize: 12))
            }
        }
        .padding(14)
        .background(p.surface)
        .overlay(Hairline(), alignment: .top)
    }

    private func submitDraft() {
        let trimmed = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        composing = false
        draft = ""
        onSendText(trimmed)
    }
}

// MARK: - Header icon buttons

private struct MenuBarIconButton: View {
    enum Kind { case accent, hairline }
    let glyph: String
    let kind: Kind
    let action: () -> Void
    @Environment(\.hyphenPalette) private var p

    var body: some View {
        Button(action: action) {
            Text(glyph)
                .font(.hyphenBody(kind == .accent ? 14 : 13, weight: kind == .accent ? .bold : .regular))
                .foregroundColor(kind == .accent ? p.accentInk : p.dim)
                .frame(width: 24, height: 24)
                .background(kind == .accent ? p.accent : Color.clear)
                .overlay(
                    RoundedRectangle(cornerRadius: 7)
                        .strokeBorder(kind == .hairline ? p.hair : Color.clear, lineWidth: 1)
                )
                .clipShape(RoundedRectangle(cornerRadius: 7))
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Filter pill

private struct MenuBarFilterPill: View {
    let title: String
    let isActive: Bool
    let action: () -> Void
    @Environment(\.hyphenPalette) private var p

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.hyphenBody(12, weight: isActive ? .semibold : .medium))
                .foregroundColor(isActive ? p.accentInk : p.dim)
                .padding(.vertical, 5)
                .padding(.horizontal, 12)
                .background(isActive ? p.accent : Color.clear)
                .clipShape(RoundedRectangle(cornerRadius: 7))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(L("popover.filter.axLabel", title))
        .accessibilityAddTraits(isActive ? [.isSelected] : [])
    }
}

// MARK: - Timeline rows

private struct MenuBarTimelineRow: View {
    let item: MenuBarTimelineItem
    let onReply: (MenuBarNotificationRef) -> Void
    let onDismiss: (MenuBarNotificationRef) -> Void
    @Environment(\.hyphenPalette) private var p

    var body: some View {
        switch item.kind {
        case let .reply(app, sender, body, time, ref):
            replyCard(app: app, sender: sender, body: body, time: time, ref: ref)
        case let .notification(app, title, detail, time):
            simpleRow(
                glyphBadge: app,
                title: title,
                detail: detail,
                detailMono: false,
                trailing: { timeLabel(time) }
            )
            .opacity(0.85)
        case let .fileReceived(name, meta, time, progress):
            transferRow(glyph: "↓", glyphFg: p.accent, name: name, meta: meta, time: time, progress: progress)
        case let .fileSent(name, meta, time, progress):
            transferRow(glyph: "↑", glyphFg: p.dim, name: name, meta: meta, time: time, progress: progress)
        case let .linkSent(title, detail, time):
            simpleRow(
                glyph: "↑",
                glyphFg: p.dim,
                title: title,
                detail: detail,
                detailMono: true,
                trailing: { timeLabel(time) }
            )
        case let .linkReceived(title, detail, time):
            simpleRow(
                glyph: "↓",
                glyphFg: p.accent,
                title: title,
                detail: detail,
                detailMono: true,
                trailing: { timeLabel(time) }
            )
        case let .pairing(text, time):
            simpleRow(
                glyph: "⌁",
                glyphFg: p.faint,
                title: text,
                detail: "",
                detailMono: false,
                trailing: { timeLabel(time) }
            )
            .opacity(0.85)
        }
    }

    private func timeLabel(_ time: String) -> some View {
        Text(time)
            .font(.hyphenMono(11))
            .foregroundColor(p.faint)
    }

    // Highlighted quick-reply card (WeChat).
    private func replyCard(
        app: MenuBarAppBadge,
        sender: String,
        body: String,
        time: String,
        ref: MenuBarNotificationRef
    ) -> some View {
        HStack(alignment: .top, spacing: 11) {
            AppGlyph(label: app.label, tint: app.tint)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    Text(sender)
                        .font(.hyphenBody(13, weight: .semibold))
                        .foregroundColor(p.text)
                    Spacer()
                    Text(time)
                        .font(.hyphenMono(11))
                        .foregroundColor(p.faint)
                }
                if !body.isEmpty {
                    Text(body)
                        .font(.hyphenBody(12))
                        .foregroundColor(p.dim)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 2)
                }
                HStack(spacing: 7) {
                    if ref.canReply {
                        Button { onReply(ref) } label: {
                            Text(L("popover.reply"))
                                .font(.hyphenBody(11, weight: .semibold))
                                .foregroundColor(p.accentInk)
                                .padding(.vertical, 5)
                                .padding(.horizontal, 12)
                                .background(p.accent)
                                .clipShape(RoundedRectangle(cornerRadius: 6))
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(L("popover.replyTo", ref.sender))
                    }
                    Button { onDismiss(ref) } label: {
                        Text(L("popover.dismiss"))
                            .font(.hyphenBody(11, weight: .semibold))
                            .foregroundColor(p.text)
                            .padding(.vertical, 5)
                            .padding(.horizontal, 12)
                            .overlay(RoundedRectangle(cornerRadius: 6).strokeBorder(p.hair2, lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(L("popover.dismissAxLabel"))
                }
                .padding(.top, 8)
            }
        }
        .padding(.vertical, 9)
        .padding(.horizontal, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(p.surface2)
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .padding(.bottom, 6)
    }

    // Transfer row with optional progress bar (active) or completion meta.
    private func transferRow(
        glyph: String,
        glyphFg: Color,
        name: String,
        meta: String,
        time: String,
        progress: Double?
    ) -> some View {
        VStack(alignment: .leading, spacing: 7) {
            HStack(spacing: 11) {
                glyphTile(glyph, fg: glyphFg)
                VStack(alignment: .leading, spacing: 0) {
                    Text(name)
                        .font(.hyphenBody(13, weight: .semibold))
                        .foregroundColor(p.text)
                    Text(meta)
                        .font(.hyphenMono(11))
                        .foregroundColor(p.dim)
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if let progress {
                    Text("\(Int(progress * 100))%")
                        .font(.hyphenMono(12, weight: .semibold))
                        .foregroundColor(p.accent)
                } else {
                    timeLabel(time)
                }
            }
            if let progress {
                TransferProgressBar(value: progress, tint: p.accent)
            }
        }
        .padding(.vertical, 9)
        .padding(.horizontal, 8)
        .accessibilityElement(children: .combine)
    }

    // Generic single-line row with an arrow glyph tile.
    private func simpleRow<Trailing: View>(
        glyph: String,
        glyphFg: Color,
        title: String,
        detail: String,
        detailMono: Bool,
        @ViewBuilder trailing: () -> Trailing
    ) -> some View {
        rowBody(
            leading: glyphTile(glyph, fg: glyphFg),
            title: title,
            detail: detail,
            detailMono: detailMono,
            trailing: trailing()
        )
    }

    // Row variant using a colored app badge (e.g. Gmail).
    private func simpleRow<Trailing: View>(
        glyphBadge: MenuBarAppBadge,
        title: String,
        detail: String,
        detailMono: Bool,
        @ViewBuilder trailing: () -> Trailing
    ) -> some View {
        rowBody(
            leading: AnyView(AppGlyph(label: glyphBadge.label, tint: glyphBadge.tint)),
            title: title,
            detail: detail,
            detailMono: detailMono,
            trailing: trailing()
        )
    }

    private func rowBody<Leading: View, Trailing: View>(
        leading: Leading,
        title: String,
        detail: String,
        detailMono: Bool,
        trailing: Trailing
    ) -> some View {
        HStack(spacing: 11) {
            leading
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 0) {
                Text(title)
                    .font(.hyphenBody(13, weight: .semibold))
                    .foregroundColor(p.text)
                if !detail.isEmpty {
                    Text(detail)
                        .font(detailMono ? .hyphenMono(11) : .hyphenBody(12))
                        .foregroundColor(p.dim)
                        .lineLimit(1)
                        .truncationMode(.tail)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            trailing
        }
        .padding(.vertical, 9)
        .padding(.horizontal, 8)
        .accessibilityElement(children: .combine)
    }

    private func glyphTile(_ glyph: String, fg: Color) -> AnyView {
        AnyView(
            Text(glyph)
                .font(.hyphenBody(14))
                .foregroundColor(fg)
                .frame(width: 30, height: 30)
                .background(p.surface3)
                .clipShape(RoundedRectangle(cornerRadius: 8))
        )
    }
}

#if DEBUG
#Preview("Timeline · Light") {
    MenuBarPopoverView()
        .padding(24)
        .background(Color.gray.opacity(0.2))
        .environment(\.colorScheme, .light)
}

#Preview("Timeline · Dark") {
    MenuBarPopoverView()
        .padding(24)
        .background(Color.black)
        .environment(\.colorScheme, .dark)
}

#Preview("Not paired") {
    MenuBarPopoverView(state: .suspended, isPaired: false, deviceName: "尚未配对", days: [])
        .padding(24)
        .background(Color.black)
        .environment(\.colorScheme, .dark)
}
#endif
