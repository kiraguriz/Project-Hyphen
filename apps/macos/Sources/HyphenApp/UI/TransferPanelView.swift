import SwiftUI

// Live macOS transfer activity surface. Rows are derived from the in-memory
// ActivityFeed, so the panel never invents files or persists transfer history.

struct TransferRow: Identifiable {
    enum Direction: Equatable {
        case outgoing
        case incoming
        case completed
    }

    enum Action: Equatable {
        case cancel
        case retrySave
    }

    let id: String
    let fileId: String
    var direction: Direction
    var name: String
    var detail: String
    var percentText: String?
    var progress: Double?
    var amber: Bool
    var footerLeft: String?
    var footerRight: String?
    var action: Action?

    static func rows(from feed: ActivityFeed) -> [TransferRow] {
        feed.items.compactMap { activity in
            guard case .transfer(let transfer) = activity.kind else { return nil }
            return row(from: transfer)
        }
    }

    private static func row(from transfer: TransferFeedItem) -> TransferRow {
        let total = byteFormatter.string(fromByteCount: transfer.totalBytes)
        let done = byteFormatter.string(fromByteCount: transfer.completedBytes)
        let baseDirection: Direction = transfer.direction == .incoming ? .incoming : .outgoing
        let directionLabel = transfer.direction == .incoming
            ? L("transfer.direction.fromPhone")
            : L("transfer.direction.toPhone")

        switch transfer.status {
        case .active:
            return TransferRow(
                id: transfer.fileId,
                fileId: transfer.fileId,
                direction: baseDirection,
                name: transfer.filename,
                detail: transfer.totalBytes > 0 ? "\(done) / \(total)" : directionLabel,
                percentText: percent(transfer.fraction),
                progress: transfer.fraction,
                amber: false,
                footerLeft: directionLabel,
                footerRight: L("transfer.action.cancel"),
                action: .cancel
            )
        case .resuming:
            return TransferRow(
                id: transfer.fileId,
                fileId: transfer.fileId,
                direction: baseDirection,
                name: transfer.filename,
                detail: L("transfer.resuming.detail", done, total),
                percentText: percent(transfer.fraction),
                progress: transfer.fraction,
                amber: true,
                footerLeft: directionLabel,
                footerRight: L("transfer.action.cancel"),
                action: .cancel
            )
        case .completed:
            let detail = transfer.verified
                ? L("transfer.received.verified", total)
                : (transfer.direction == .incoming ? L("transfer.received", total) : L("transfer.sent", total))
            return TransferRow(
                id: transfer.fileId,
                fileId: transfer.fileId,
                direction: .completed,
                name: transfer.filename,
                detail: detail,
                percentText: nil,
                progress: nil,
                amber: false,
                footerLeft: nil,
                footerRight: nil,
                action: nil
            )
        case .cancelled:
            return TransferRow(
                id: transfer.fileId,
                fileId: transfer.fileId,
                direction: baseDirection,
                name: transfer.filename,
                detail: L("transfer.cancelled"),
                percentText: nil,
                progress: nil,
                amber: false,
                footerLeft: nil,
                footerRight: nil,
                action: nil
            )
        case .retryRequired:
            return TransferRow(
                id: transfer.fileId,
                fileId: transfer.fileId,
                direction: .incoming,
                name: transfer.filename,
                detail: L("transfer.saveRetryRequired"),
                percentText: nil,
                progress: nil,
                amber: true,
                footerLeft: L("transfer.verifiedStaged"),
                footerRight: L("transfer.action.retrySave"),
                action: .retrySave
            )
        }
    }

    private static func percent(_ fraction: Double?) -> String? {
        fraction.map { "\(Int(($0 * 100).rounded(.down)))%" }
    }

    private static let byteFormatter: ByteCountFormatter = {
        let formatter = ByteCountFormatter()
        formatter.countStyle = .file
        return formatter
    }()

    static let sampleOutgoing = TransferRow(
        id: "preview-out",
        fileId: "preview-out",
        direction: .outgoing,
        name: "设计稿.pdf",
        detail: "5.2 / 8.2 MB",
        percentText: "64%",
        progress: 0.64,
        amber: false,
        footerLeft: "发送到手机",
        footerRight: "取消",
        action: .cancel
    )

    static let sampleIncoming = TransferRow(
        id: "preview-in",
        fileId: "preview-in",
        direction: .incoming,
        name: "IMG_4821.heic",
        detail: "已恢复 · 48 / 60 MB",
        percentText: "81%",
        progress: 0.81,
        amber: true,
        footerLeft: "来自手机",
        footerRight: "取消",
        action: .cancel
    )
}

struct TransferProgressBar: View {
    var value: Double
    var tint: Color
    @Environment(\.hyphenPalette) private var p

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: 3).fill(p.surface3)
                RoundedRectangle(cornerRadius: 3)
                    .fill(tint)
                    .frame(width: max(0, min(1, value)) * geo.size.width)
            }
        }
        .frame(height: 6)
    }
}

struct TransferRowView: View {
    var row: TransferRow
    var actionEnabled: Bool
    var onAction: (TransferRow.Action, String) -> Void
    @Environment(\.hyphenPalette) private var p

    private var accentColor: Color { row.amber ? p.amber : p.accent }

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack(spacing: 11) {
                glyph
                VStack(alignment: .leading, spacing: 1) {
                    Text(row.name)
                        .font(.hyphenBody(13, weight: .semibold))
                        .foregroundColor(p.text)
                        .lineLimit(1)
                    Text(row.detail)
                        .font(.hyphenMono(11))
                        .foregroundColor(row.amber ? p.amber : p.dim)
                        .lineLimit(1)
                }
                Spacer(minLength: 8)
                if let percentText = row.percentText {
                    Text(percentText)
                        .font(.hyphenMono(12, weight: .semibold))
                        .foregroundColor(accentColor)
                }
            }

            if let progress = row.progress {
                TransferProgressBar(value: progress, tint: accentColor)
            }

            if row.footerLeft != nil || row.footerRight != nil {
                HStack {
                    if let footerLeft = row.footerLeft {
                        Text(footerLeft)
                            .font(.hyphenBody(11))
                            .foregroundColor(p.faint)
                    }
                    Spacer(minLength: 8)
                    if let footerRight = row.footerRight, let action = row.action {
                        Button(footerRight) { onAction(action, row.fileId) }
                            .buttonStyle(.plain)
                            .font(.hyphenBody(11, weight: .semibold))
                            .foregroundColor(actionEnabled ? p.accent : p.faint)
                            .disabled(!actionEnabled)
                    }
                }
            }
        }
        .padding(.vertical, 14)
        .padding(.horizontal, 16)
    }

    @ViewBuilder
    private var glyph: some View {
        switch row.direction {
        case .outgoing:
            transferGlyph("↑", fg: p.accent, bg: p.surface3)
        case .incoming:
            transferGlyph("↓", fg: row.amber ? p.amber : p.accent, bg: p.surface3)
        case .completed:
            transferGlyph("✓", fg: p.accent, bg: p.accentSoft)
        }
    }

    private func transferGlyph(_ symbol: String, fg: Color, bg: Color) -> some View {
        Text(symbol)
            .font(.hyphenBody(14))
            .foregroundColor(fg)
            .frame(width: 34, height: 34)
            .background(bg)
            .clipShape(RoundedRectangle(cornerRadius: 9))
    }
}

struct TransferPanelView: View {
    var peerName: String?
    var rows: [TransferRow]
    var isConnected: Bool
    var onCancel: (String) -> Void
    var onRetrySave: (String) -> Void
    @Environment(\.hyphenPalette) private var p

    init(
        peerName: String? = nil,
        rows: [TransferRow] = [],
        isConnected: Bool = false,
        onCancel: @escaping (String) -> Void = { _ in },
        onRetrySave: @escaping (String) -> Void = { _ in }
    ) {
        self.peerName = peerName
        self.rows = rows
        self.isConnected = isConnected
        self.onCancel = onCancel
        self.onRetrySave = onRetrySave
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            Hairline()
            if rows.isEmpty {
                Text(L("transfer.panel.empty"))
                    .font(.hyphenBody(12))
                    .foregroundColor(p.dim)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 28)
                    .padding(.horizontal, 16)
            } else {
                ForEach(Array(rows.enumerated()), id: \.element.id) { index, row in
                    TransferRowView(
                        row: row,
                        actionEnabled: true,
                        onAction: handleAction
                    )
                    if index < rows.count - 1 { Hairline() }
                }
            }
        }
        .frame(width: 360)
        .background(p.surface)
        .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(p.hair, lineWidth: 1))
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .hyphenThemed()
    }

    private var header: some View {
        HStack {
            Text(L("transfer.panel.title"))
                .font(.hyphenBody(13, weight: .semibold))
                .foregroundColor(p.text)
            Spacer()
            HStack(spacing: 6) {
                Circle().fill(isConnected ? p.accent : p.faint).frame(width: 7, height: 7)
                Text(peerName ?? L("conn.notPaired"))
                    .font(.hyphenBody(11))
                    .foregroundColor(p.dim)
            }
        }
        .padding(.vertical, 13)
        .padding(.horizontal, 16)
    }

    private func handleAction(_ action: TransferRow.Action, fileId: String) {
        switch action {
        case .cancel: onCancel(fileId)
        case .retrySave: onRetrySave(fileId)
        }
    }
}

#if DEBUG
#Preview("Transfer panel") {
    TransferPanelView(
        peerName: "Pixel 8 Pro",
        rows: [.sampleOutgoing, .sampleIncoming],
        isConnected: true
    )
    .padding(28)
    .background(HyphenPalette.dark.canvas)
}
#endif
