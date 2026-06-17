import SwiftUI

// Menu-bar popover · Variant 3 (时间线 / Timeline — the recommended direction
// from Section B of the design). A connection header, a filter tab row, a
// day-grouped activity timeline, and a footer. Built on the shared palette,
// typography, and component foundation. Every new type is `MenuBar`-prefixed
// to avoid collisions with sibling popover modules.

// MARK: - Filter tabs

enum MenuBarTimelineFilter: String, CaseIterable, Identifiable {
    case all = "全部"
    case notifications = "通知"
    case transfers = "传输"

    var id: String { rawValue }
}

// MARK: - Timeline model

enum MenuBarTimelineKind {
    /// A mirrored notification offering a quick reply (e.g. WeChat).
    case reply(app: MenuBarAppBadge, sender: String, body: String, time: String)
    /// A received file with verification metadata.
    case fileReceived(name: String, meta: String)
    /// An outbound link / text send.
    case linkSent(title: String, detail: String, time: String)
    /// A passive mirrored notification (e.g. Gmail).
    case notification(app: MenuBarAppBadge, title: String, detail: String)
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
        case .fileReceived, .linkSent:
            return [.all, .transfers]
        }
    }
}

struct MenuBarTimelineDay: Identifiable {
    let id: UUID
    let title: String
    let items: [MenuBarTimelineItem]

    init(id: UUID = UUID(), title: String, items: [MenuBarTimelineItem]) {
        self.id = id
        self.title = title
        self.items = items
    }
}

extension MenuBarTimelineDay {
    /// Sample timeline matching the design's Variant 3 content.
    static let sample: [MenuBarTimelineDay] = [
        MenuBarTimelineDay(
            title: "今天",
            items: [
                MenuBarTimelineItem(kind: .reply(
                    app: MenuBarAppBadge(label: "微", tint: .hex(0x1f9d57)),
                    sender: "微信 · 张伟",
                    body: "晚上一起吃饭吗？老地方见。",
                    time: "9:41"
                )),
                MenuBarTimelineItem(kind: .fileReceived(
                    name: "设计稿.pdf",
                    meta: "8.2 MB · SHA‑256 校验通过"
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
                    detail: "您的收据 · Figma 订阅"
                )),
            ]
        ),
    ]
}

// MARK: - Entry view

struct MenuBarPopoverView: View {
    let state: HyphenConnectionState
    let deviceName: String
    let latencyMs: Int?
    let days: [MenuBarTimelineDay]
    let onPair: () -> Void
    let onSettings: () -> Void
    let onSend: () -> Void
    let onReply: (String) -> Void
    let onDismiss: (String) -> Void

    @State private var filter: MenuBarTimelineFilter = .all
    @Environment(\.hyphenPalette) private var p

    init(
        state: HyphenConnectionState = .connected,
        deviceName: String = "Pixel 8 Pro",
        latencyMs: Int? = 18,
        days: [MenuBarTimelineDay] = MenuBarTimelineDay.sample,
        onPair: @escaping () -> Void = {},
        onSettings: @escaping () -> Void = {},
        onSend: @escaping () -> Void = {},
        onReply: @escaping (String) -> Void = { _ in },
        onDismiss: @escaping (String) -> Void = { _ in }
    ) {
        self.state = state
        self.deviceName = deviceName
        self.latencyMs = latencyMs
        self.days = days
        self.onPair = onPair
        self.onSettings = onSettings
        self.onSend = onSend
        self.onReply = onReply
        self.onDismiss = onDismiss
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            filterRow
            timeline
            footer
        }
        .frame(width: 344)
        .background(p.surface)
        .clipShape(RoundedRectangle(cornerRadius: 13))
        .overlay(RoundedRectangle(cornerRadius: 13).strokeBorder(p.hair, lineWidth: 1))
        .hyphenThemed()
    }

    // MARK: Header

    private var header: some View {
        HStack(spacing: 0) {
            HStack(spacing: 9) {
                StatusDot(state: state)
                Text(deviceName)
                    .font(.hyphenBody(13, weight: .semibold))
                    .foregroundColor(p.text)
                if let latencyMs {
                    Text("· \(latencyMs)ms")
                        .font(.hyphenMono(13))
                        .foregroundColor(p.dim)
                }
            }
            Spacer(minLength: 8)
            HStack(spacing: 7) {
                MenuBarIconButton(glyph: "＋", kind: .accent, action: onPair)
                MenuBarIconButton(glyph: "⚙", kind: .hairline, action: onSettings)
            }
        }
        .padding(.horizontal, 15)
        .padding(.top, 12)
        .padding(.bottom, 10)
    }

    // MARK: Filter tabs

    private var filterRow: some View {
        HStack(spacing: 3) {
            ForEach(MenuBarTimelineFilter.allCases) { tab in
                MenuBarFilterPill(
                    title: tab.rawValue,
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
                    Text("暂无活动")
                        .font(.hyphenBody(12))
                        .foregroundColor(p.faint)
                        .frame(maxWidth: .infinity, minHeight: 86)
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
                Text(state == .connected ? "实时镜像" : state.title)
                    .font(.hyphenBody(12))
                    .foregroundColor(p.dim)
            }
            Spacer()
            Button(action: onSend) {
                Text("发送…")
                    .font(.hyphenBody(12, weight: .semibold))
                    .foregroundColor(p.accent)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(p.surface)
        .overlay(Hairline(), alignment: .top)
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
    }
}

// MARK: - Timeline rows

private struct MenuBarTimelineRow: View {
    let item: MenuBarTimelineItem
    let onReply: (String) -> Void
    let onDismiss: (String) -> Void
    @Environment(\.hyphenPalette) private var p

    var body: some View {
        switch item.kind {
        case let .reply(app, sender, body, time):
            replyCard(app: app, sender: sender, body: body, time: time)
        case let .fileReceived(name, meta):
            simpleRow(
                glyph: "↓",
                glyphFg: p.accent,
                title: name,
                detail: meta,
                detailMono: true,
                trailing: { trailingLabel("显示", color: p.dim) }
            )
        case let .linkSent(title, detail, time):
            simpleRow(
                glyph: "↑",
                glyphFg: p.dim,
                title: title,
                detail: detail,
                detailMono: true,
                trailing: {
                    Text(time)
                        .font(.hyphenMono(11))
                        .foregroundColor(p.faint)
                }
            )
        case let .notification(app, title, detail):
            simpleRow(
                glyphBadge: app,
                title: title,
                detail: detail,
                detailMono: false,
                trailing: { EmptyView() }
            )
            .opacity(0.8)
        }
    }

    // Highlighted quick-reply card (WeChat).
    private func replyCard(app: MenuBarAppBadge, sender: String, body: String, time: String) -> some View {
        HStack(alignment: .top, spacing: 11) {
            AppGlyph(label: app.label, tint: app.tint)
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
                Text(body)
                    .font(.hyphenBody(12))
                    .foregroundColor(p.dim)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 2)
                    .padding(.bottom, 8)
                HStack(spacing: 7) {
                    Button { onReply(sender) } label: {
                        Text("回复")
                            .font(.hyphenBody(11, weight: .semibold))
                            .foregroundColor(p.accentInk)
                            .padding(.vertical, 5)
                            .padding(.horizontal, 12)
                            .background(p.accent)
                            .clipShape(RoundedRectangle(cornerRadius: 6))
                    }
                    .buttonStyle(.plain)
                    Button { onDismiss(sender) } label: {
                        Text("关闭")
                            .font(.hyphenBody(11, weight: .semibold))
                            .foregroundColor(p.text)
                            .padding(.vertical, 5)
                            .padding(.horizontal, 12)
                            .overlay(RoundedRectangle(cornerRadius: 6).strokeBorder(p.hair2, lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .padding(.vertical, 9)
        .padding(.horizontal, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(p.surface2)
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .padding(.bottom, 6)
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
            VStack(alignment: .leading, spacing: 0) {
                Text(title)
                    .font(.hyphenBody(13, weight: .semibold))
                    .foregroundColor(p.text)
                Text(detail)
                    .font(detailMono ? .hyphenMono(11) : .hyphenBody(12))
                    .foregroundColor(p.dim)
                    .lineLimit(1)
                    .truncationMode(.tail)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            trailing
        }
        .padding(.vertical, 9)
        .padding(.horizontal, 8)
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

    private func trailingLabel(_ text: String, color: Color) -> some View {
        Text(text)
            .font(.hyphenBody(11, weight: .semibold))
            .foregroundColor(color)
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
#endif
