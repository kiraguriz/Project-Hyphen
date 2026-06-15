import SwiftUI

// STAGED DESIGN SURFACE — defined and preview-tested, but not yet wired into a
// runtime surface (no call site outside this file). It lands when the live
// transfer-activity UI is hooked up; until then it is intentional scaffolding,
// not forgotten/dead code. The real send/receive paths stream via TransferSender.
//
// macOS file-transfer activity panel (design handoff "Hyphen Apps.dc.html",
// Section D · macOS 通知镜像与传输, lines 455-489). In-app representation of the
// transfer activity list: in-progress outgoing, resumed incoming, and a
// verified completed row. Transfer production paths stream; this is the UI
// surface only.

// MARK: - Row model

/// One transfer activity row.
struct TransferRow: Identifiable {
    enum Direction {
        /// Outgoing send (↑, accent).
        case outgoing
        /// Incoming receive (↓, amber when resumed).
        case incoming
        /// Verified / completed (✓, accent-soft).
        case completed
    }

    let id = UUID()
    var direction: Direction
    /// File name (primary line).
    var name: String
    /// Mono detail line (size/speed, resume note, or completion note).
    var detail: String
    /// Trailing percent text (nil for completed).
    var percentText: String?
    /// Progress fraction 0...1 (nil hides the progress bar).
    var progress: Double?
    /// Whether the row uses the amber accent (resumed incoming).
    var amber: Bool
    /// Footer left label (e.g. 发送到手机). nil hides the footer row.
    var footerLeft: String?
    /// Footer right action label (e.g. 取消 / 在访达中显示).
    var footerRight: String?

    /// Outgoing in-progress sample (设计稿.pdf · 64%).
    static let sampleOutgoing = TransferRow(
        direction: .outgoing,
        name: "设计稿.pdf",
        detail: "5.2 / 8.2 MB · 2.1 MB/s",
        percentText: "64%",
        progress: 0.64,
        amber: false,
        footerLeft: "发送到手机",
        footerRight: "取消"
    )

    /// Incoming resumed sample (IMG_4821.heic · 81%).
    static let sampleIncoming = TransferRow(
        direction: .incoming,
        name: "IMG_4821.heic",
        detail: "已从 48% 断点续传",
        percentText: "81%",
        progress: 0.81,
        amber: true,
        footerLeft: nil,
        footerRight: nil
    )

    /// Completed + verified sample (会议纪要.md).
    static let sampleCompleted = TransferRow(
        direction: .completed,
        name: "会议纪要.md",
        detail: "完成 · SHA‑256 校验通过",
        percentText: nil,
        progress: nil,
        amber: false,
        footerLeft: nil,
        footerRight: "在访达中显示"
    )
}

// MARK: - Progress bar

/// Reusable rounded progress bar: height 6, surface-3 track, tinted fill.
struct TransferProgressBar: View {
    /// 0...1 fraction.
    var value: Double
    /// Fill color.
    var tint: Color
    @Environment(\.hyphenPalette) private var p

    init(value: Double, tint: Color) {
        self.value = value
        self.tint = tint
    }

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: 3)
                    .fill(p.surface3)
                RoundedRectangle(cornerRadius: 3)
                    .fill(tint)
                    .frame(width: max(0, min(1, value)) * geo.size.width)
            }
        }
        .frame(height: 6)
    }
}

// MARK: - Row view

/// A single transfer activity row.
struct TransferRowView: View {
    var row: TransferRow
    @Environment(\.hyphenPalette) private var p

    private var accentColor: Color { row.amber ? p.amber : p.accent }

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack(spacing: 11) {
                glyph
                VStack(alignment: .leading, spacing: 0) {
                    Text(row.name)
                        .font(.hyphenBody(13, weight: .semibold))
                        .foregroundColor(p.text)
                    Text(row.detail)
                        .font(.hyphenMono(11))
                        .foregroundColor(row.amber ? p.amber : p.dim)
                }
                Spacer(minLength: 8)
                if let pct = row.percentText {
                    Text(pct)
                        .font(.hyphenMono(12, weight: .semibold))
                        .foregroundColor(accentColor)
                } else if row.direction == .completed, let right = row.footerRight {
                    Text(right)
                        .font(.hyphenBody(11, weight: .semibold))
                        .foregroundColor(p.dim)
                }
            }

            if let progress = row.progress {
                TransferProgressBar(value: progress, tint: accentColor)
            }

            if row.direction != .completed, (row.footerLeft != nil || row.footerRight != nil) {
                HStack {
                    if let left = row.footerLeft {
                        Text(left)
                            .font(.hyphenBody(11))
                            .foregroundColor(p.faint)
                    }
                    Spacer(minLength: 8)
                    if let right = row.footerRight {
                        Text(right)
                            .font(.hyphenBody(11, weight: .semibold))
                            .foregroundColor(p.dim)
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

// MARK: - Transfer panel

/// Transfer activity panel (width 360): header + peer chip + transfer rows.
struct TransferPanelView: View {
    var peerName: String
    var rows: [TransferRow]
    @Environment(\.hyphenPalette) private var p

    init(
        peerName: String = "Pixel 8 Pro",
        rows: [TransferRow] = [.sampleOutgoing, .sampleIncoming, .sampleCompleted]
    ) {
        self.peerName = peerName
        self.rows = rows
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            Hairline()
            ForEach(Array(rows.enumerated()), id: \.element.id) { index, row in
                TransferRowView(row: row)
                if index < rows.count - 1 {
                    Hairline()
                }
            }
        }
        .frame(width: 360)
        .background(p.surface)
        .overlay(
            RoundedRectangle(cornerRadius: 16)
                .strokeBorder(p.hair, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .shadow(color: .black.opacity(0.28), radius: 20, x: 0, y: 16)
        .hyphenThemed()
    }

    private var header: some View {
        HStack {
            Text("传输")
                .font(.hyphenBody(13, weight: .semibold))
                .foregroundColor(p.text)
            Spacer()
            HStack(spacing: 6) {
                Circle().fill(p.accent).frame(width: 7, height: 7)
                Text(peerName)
                    .font(.hyphenBody(11))
                    .foregroundColor(p.dim)
            }
        }
        .padding(.vertical, 13)
        .padding(.horizontal, 16)
    }
}

// MARK: - Preview

#if DEBUG
#Preview("Transfer panel") {
    TransferPanelView()
        .padding(28)
        .background(HyphenPalette.dark.canvas)
}
#endif
