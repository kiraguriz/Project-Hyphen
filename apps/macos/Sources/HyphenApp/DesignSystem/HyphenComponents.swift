import SwiftUI

// Shared primitives used across every Hyphen macOS surface. Built on the
// palette/typography foundation. Connection-state vocabulary is aligned with
// the code enums (connected / degraded / reconnecting / discovering /
// sleeping / suspended) and the design's status legend (Section A).

// MARK: - Connection state

enum HyphenConnectionState: CaseIterable {
    case connected
    case degraded
    case reconnecting
    case discovering
    case sleeping
    case suspended

    var title: String {
        switch self {
        case .connected: return "已连接"
        case .degraded: return "连接不稳定"
        case .reconnecting: return "正在重新连接"
        case .discovering: return "正在查找"
        case .sleeping: return "Mac 已休眠"
        case .suspended: return "已暂停"
        }
    }

    func color(_ p: HyphenPalette) -> Color {
        switch self {
        case .connected: return p.accent
        case .degraded, .reconnecting: return p.amber
        case .discovering: return p.blue
        case .sleeping, .suspended: return p.faint
        }
    }
}

/// Status indicator dot. `connected`/`degraded` get a soft glow ring,
/// `reconnecting`/`discovering` pulse, `suspended` is a hollow ring.
struct StatusDot: View {
    let state: HyphenConnectionState
    var size: CGFloat = 9
    @Environment(\.hyphenPalette) private var p
    @State private var pulsing = false

    var body: some View {
        let color = state.color(p)
        Group {
            switch state {
            case .connected, .degraded:
                Circle()
                    .fill(color)
                    .frame(width: size, height: size)
                    .background(
                        Circle()
                            .fill(color.opacity(0.18))
                            .frame(width: size + 8, height: size + 8)
                    )
            case .reconnecting, .discovering:
                Circle()
                    .fill(color)
                    .frame(width: size, height: size)
                    .opacity(pulsing ? 0.3 : 1)
                    .animation(.easeInOut(duration: 0.7).repeatForever(autoreverses: true), value: pulsing)
                    .onAppear { pulsing = true }
            case .sleeping:
                Circle().fill(color).frame(width: size, height: size)
            case .suspended:
                Circle().strokeBorder(color, lineWidth: 2).frame(width: size, height: size)
            }
        }
        .frame(width: size + 8, height: size + 8)
    }
}

// MARK: - Containers

/// Surface card: `--surface` fill, hairline border, rounded corners.
struct HyphenCard<Content: View>: View {
    var cornerRadius: CGFloat = 16
    var padding: CGFloat = 18
    @ViewBuilder var content: () -> Content
    @Environment(\.hyphenPalette) private var p

    var body: some View {
        content()
            .padding(padding)
            .background(p.surface)
            .overlay(
                RoundedRectangle(cornerRadius: cornerRadius)
                    .strokeBorder(p.hair, lineWidth: 1)
            )
            .clipShape(RoundedRectangle(cornerRadius: cornerRadius))
    }
}

/// macOS window chrome: traffic-light dots + centered title bar. When `onClose`
/// is supplied the red light becomes a working close button (used by the
/// borderless SwiftUI-hosted windows).
struct WindowChrome: View {
    let title: String
    var onClose: (() -> Void)?
    @Environment(\.hyphenPalette) private var p

    init(title: String, onClose: (() -> Void)? = nil) {
        self.title = title
        self.onClose = onClose
    }

    var body: some View {
        ZStack {
            HStack(spacing: 8) {
                if let onClose {
                    Button(action: onClose) {
                        Circle().fill(Color.hex(0xec6a5e)).frame(width: 12, height: 12)
                    }
                    .buttonStyle(.plain)
                } else {
                    Circle().fill(Color.hex(0xec6a5e)).frame(width: 12, height: 12)
                }
                Circle().fill(Color.hex(0xf4bf4f)).frame(width: 12, height: 12)
                Circle().fill(Color.hex(0x61c554)).frame(width: 12, height: 12)
                Spacer()
            }
            Text(title)
                .font(.hyphenBody(13, weight: .semibold))
                .foregroundColor(p.dim)
        }
        .padding(.horizontal, 14)
        .frame(height: 40)
        .background(p.surface2)
        .overlay(Rectangle().fill(p.hair).frame(height: 1), alignment: .bottom)
    }
}

// MARK: - Buttons

/// Primary (accent) button: emerald fill, accent-ink label.
struct AccentButtonStyle: ButtonStyle {
    var fontSize: CGFloat = 13
    @Environment(\.hyphenPalette) private var p
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.hyphenBody(fontSize, weight: .semibold))
            .foregroundColor(p.accentInk)
            .padding(.vertical, 10)
            .padding(.horizontal, 16)
            .frame(maxWidth: .infinity)
            .background(p.accent.opacity(configuration.isPressed ? 0.85 : 1))
            .clipShape(RoundedRectangle(cornerRadius: 9))
    }
}

/// Secondary button: surface-2 fill with hairline border.
struct SecondaryButtonStyle: ButtonStyle {
    var fontSize: CGFloat = 13
    @Environment(\.hyphenPalette) private var p
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.hyphenBody(fontSize, weight: .semibold))
            .foregroundColor(p.text)
            .padding(.vertical, 10)
            .padding(.horizontal, 16)
            .frame(maxWidth: .infinity)
            .background(p.surface2.opacity(configuration.isPressed ? 0.7 : 1))
            .overlay(RoundedRectangle(cornerRadius: 9).strokeBorder(p.hair2, lineWidth: 1))
            .clipShape(RoundedRectangle(cornerRadius: 9))
    }
}

/// Destructive text button (used for "撤销信任", "不一致", "取消").
struct DangerTextButtonStyle: ButtonStyle {
    var fontSize: CGFloat = 13
    @Environment(\.hyphenPalette) private var p
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.hyphenBody(fontSize, weight: .semibold))
            .foregroundColor(p.red.opacity(configuration.isPressed ? 0.7 : 1))
            .padding(.vertical, 10)
            .padding(.horizontal, 8)
    }
}

// MARK: - Small atoms

// STAGED: `MonoTag` and `HyphenCard` below are shared primitives with no call
// site yet — they back design surfaces that land later. Intentional foundation,
// not forgotten/dead code.

/// Monospace tag chip (e.g. 本地优先 / 可审计 / 零遥测).
struct MonoTag: View {
    let text: String
    @Environment(\.hyphenPalette) private var p
    var body: some View {
        Text(text)
            .font(.hyphenMono(11))
            .foregroundColor(p.dim)
            .padding(.vertical, 4)
            .padding(.horizontal, 8)
            .background(p.surface2)
            .overlay(RoundedRectangle(cornerRadius: 6).strokeBorder(p.hair, lineWidth: 1))
            .clipShape(RoundedRectangle(cornerRadius: 6))
    }
}

/// App glyph tile (colored rounded square with a one/two-char label).
struct AppGlyph: View {
    let label: String
    let tint: Color
    var size: CGFloat = 30
    var corner: CGFloat = 8
    var fg: Color = .white
    var body: some View {
        Text(label)
            .font(.hyphenBody(size * 0.43, weight: .semibold))
            .foregroundColor(fg)
            .frame(width: size, height: size)
            .background(tint)
            .clipShape(RoundedRectangle(cornerRadius: corner))
    }
}

/// Thin horizontal divider in the palette's hairline color.
struct Hairline: View {
    @Environment(\.hyphenPalette) private var p
    var body: some View { Rectangle().fill(p.hair).frame(height: 1) }
}
