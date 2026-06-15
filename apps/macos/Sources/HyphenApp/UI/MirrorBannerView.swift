import SwiftUI

// STAGED DESIGN SURFACE — defined and preview-tested, but not yet wired into a
// runtime surface (no call site outside this file). It lands when the live
// notification-mirror UI is hooked up; until then it is intentional scaffolding,
// not forgotten/dead code. Do not assume mirrored-notification banners are live.
//
// macOS notification-mirror banners (design handoff "Hyphen Apps.dc.html",
// Section D · macOS 通知镜像与传输, lines 398-452). In-app representations of
// mirrored-notification banners plus the reply/dismiss interaction states.
// Privacy modes follow ADR/conventions: discovery is not trust, and the
// existsOnly mode reveals nothing but presence.

// MARK: - Privacy modes

/// Three privacy modes for a mirrored Android notification banner.
enum MirrorPrivacyMode {
    /// App + title + body + reply/dismiss actions.
    case full
    /// App name only with a generic "new message" line and a dismiss action.
    case hideBody
    /// Presence only — neutral glyph, no content.
    case existsOnly

    /// Mono caption describing the mode (shown optionally under the banner).
    var caption: String {
        switch self {
        case .full: return "完整 · 应用 + 标题 + 内容"
        case .hideBody: return "隐藏内容 · 仅应用名"
        case .existsOnly: return "仅提示 · 不含任何内容"
        }
    }
}

// MARK: - Banner surface

/// Shared banner container: width 344, surface background, rounded 17,
/// soft shadow, hairline border.
private struct MirrorBannerSurface<Content: View>: View {
    var alignVCenter: Bool = false
    @ViewBuilder var content: () -> Content
    @Environment(\.hyphenPalette) private var p

    var body: some View {
        HStack(alignment: alignVCenter ? .center : .top, spacing: 12) {
            content()
        }
        .padding(.vertical, 13)
        .padding(.horizontal, 15)
        .frame(width: 344, alignment: .leading)
        .background(p.surface)
        .overlay(
            RoundedRectangle(cornerRadius: 17)
                .strokeBorder(p.hair, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: 17))
        .shadow(color: .black.opacity(0.32), radius: 20, x: 0, y: 16)
    }
}

// MARK: - Mirror banner

/// A mirrored-notification banner driven by `MirrorPrivacyMode`.
struct MirrorBannerView: View {
    var mode: MirrorPrivacyMode
    /// When true, renders the mono caption under the banner.
    var showCaption: Bool

    @Environment(\.hyphenPalette) private var p

    init(mode: MirrorPrivacyMode = .full, showCaption: Bool = true) {
        self.mode = mode
        self.showCaption = showCaption
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            banner
            if showCaption {
                Text(mode.caption)
                    .font(.hyphenMono(11))
                    .foregroundColor(p.faint)
                    .padding(.leading, 2)
            }
        }
        .hyphenThemed()
    }

    @ViewBuilder
    private var banner: some View {
        switch mode {
        case .full: fullBanner
        case .hideBody: hideBodyBanner
        case .existsOnly: existsOnlyBanner
        }
    }

    // .full
    private var fullBanner: some View {
        MirrorBannerSurface {
            AppGlyph(label: "微", tint: .hex(0x1f9d57), size: 38, corner: 10)
            VStack(alignment: .leading, spacing: 1) {
                HStack(alignment: .firstTextBaseline) {
                    Text("微信 · 来自 Pixel")
                        .font(.hyphenBody(11))
                        .foregroundColor(p.faint)
                    Spacer(minLength: 8)
                    Text("现在")
                        .font(.hyphenMono(11))
                        .foregroundColor(p.faint)
                }
                Text("张伟")
                    .font(.hyphenBody(13, weight: .semibold))
                    .foregroundColor(p.text)
                Text("晚上一起吃饭吗？老地方见。")
                    .font(.hyphenBody(13))
                    .foregroundColor(p.dim)
                HStack(spacing: 7) {
                    Button("回复") {}
                        .buttonStyle(MirrorBannerAccentButtonStyle())
                    Button("关闭") {}
                        .buttonStyle(MirrorBannerHairlineButtonStyle())
                }
                .padding(.top, 10)
            }
        }
    }

    // .hideBody
    private var hideBodyBanner: some View {
        MirrorBannerSurface(alignVCenter: true) {
            AppGlyph(label: "微", tint: .hex(0x1f9d57), size: 38, corner: 10)
            VStack(alignment: .leading, spacing: 0) {
                Text("微信")
                    .font(.hyphenBody(13, weight: .semibold))
                    .foregroundColor(p.text)
                Text("有一条新消息")
                    .font(.hyphenBody(13))
                    .foregroundColor(p.dim)
            }
            Spacer(minLength: 8)
            Button("关闭") {}
                .buttonStyle(MirrorBannerHairlineButtonStyle())
        }
    }

    // .existsOnly
    private var existsOnlyBanner: some View {
        MirrorBannerSurface(alignVCenter: true) {
            ZStack {
                RoundedRectangle(cornerRadius: 10)
                    .fill(p.surface3)
                    .frame(width: 38, height: 38)
                RoundedRectangle(cornerRadius: 2)
                    .fill(p.accent)
                    .frame(width: 16, height: 4)
            }
            VStack(alignment: .leading, spacing: 0) {
                Text("Android 有一条通知")
                    .font(.hyphenBody(13, weight: .semibold))
                    .foregroundColor(p.text)
                Text("内容已隐藏")
                    .font(.hyphenBody(12))
                    .foregroundColor(p.faint)
            }
            Spacer(minLength: 0)
        }
    }
}

// MARK: - Reply expanded

/// Inline reply composer banner (lines 430-440): surface-2 field with accent
/// border, blinking caret, accent send button, and a RemoteInput hint.
struct MirrorReplyExpandedView: View {
    /// Pre-filled draft text shown before the blinking caret.
    var draft: String
    @Environment(\.hyphenPalette) private var p
    @State private var caretOn = true

    init(draft: String = "好的，七点见") {
        self.draft = draft
    }

    var body: some View {
        MirrorBannerSurface {
            AppGlyph(label: "微", tint: .hex(0x1f9d57), size: 38, corner: 10)
            VStack(alignment: .leading, spacing: 0) {
                Text("微信 · 张伟")
                    .font(.hyphenBody(11))
                    .foregroundColor(p.faint)

                HStack(spacing: 8) {
                    HStack(alignment: .center, spacing: 0) {
                        Text(draft)
                            .font(.hyphenBody(13))
                            .foregroundColor(p.text)
                        Rectangle()
                            .fill(p.accent)
                            .frame(width: 1.5, height: 15)
                            .opacity(caretOn ? 1 : 0)
                            .padding(.leading, 1)
                        Spacer(minLength: 0)
                    }
                    Button {
                    } label: {
                        Text("↑")
                            .font(.hyphenBody(14))
                            .foregroundColor(p.accentInk)
                            .frame(width: 28, height: 28)
                            .background(p.accent)
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                    }
                    .buttonStyle(.plain)
                }
                .padding(.vertical, 8)
                .padding(.horizontal, 10)
                .background(p.surface2)
                .overlay(RoundedRectangle(cornerRadius: 11).strokeBorder(p.accent, lineWidth: 1))
                .clipShape(RoundedRectangle(cornerRadius: 11))
                .padding(.top, 9)

                HStack(spacing: 5) {
                    Circle().fill(p.accent).frame(width: 6, height: 6)
                    Text("仅对已测试支持 RemoteInput 的应用可用")
                        .font(.hyphenBody(11))
                        .foregroundColor(p.faint)
                }
                .padding(.top, 7)
            }
        }
        .hyphenThemed()
        .onAppear {
            withAnimation(.easeInOut(duration: 0.5).repeatForever(autoreverses: true)) {
                caretOn = false
            }
        }
    }
}

// MARK: - Dismiss toast

/// Two dismiss-result toasts (lines 442-451).
enum MirrorToastKind {
    /// Notification cleared on the phone.
    case dismissed
    /// Phone offline; will retry on reconnect.
    case dismissFailed
}

/// A dismiss-result toast banner driven by `MirrorToastKind`.
struct MirrorToastView: View {
    var kind: MirrorToastKind
    @Environment(\.hyphenPalette) private var p

    init(kind: MirrorToastKind = .dismissed) {
        self.kind = kind
    }

    var body: some View {
        MirrorBannerSurface(alignVCenter: true) {
            icon
            VStack(alignment: .leading, spacing: 0) {
                Text(title)
                    .font(.hyphenBody(13, weight: .semibold))
                    .foregroundColor(p.text)
                Text(subtitle)
                    .font(.hyphenBody(12))
                    .foregroundColor(p.dim)
            }
            Spacer(minLength: 8)
            if kind == .dismissFailed {
                Button("重试") {}
                    .buttonStyle(MirrorBannerHairlineButtonStyle())
            }
        }
        .hyphenThemed()
    }

    @ViewBuilder
    private var icon: some View {
        switch kind {
        case .dismissed:
            Text("✓")
                .font(.hyphenBody(15))
                .foregroundColor(p.accent)
                .frame(width: 34, height: 34)
                .background(p.accentSoft)
                .clipShape(Circle())
        case .dismissFailed:
            Text("↺")
                .font(.hyphenBody(15))
                .foregroundColor(p.red)
                .frame(width: 34, height: 34)
                .background(p.redSoft)
                .clipShape(Circle())
        }
    }

    private var title: String {
        switch kind {
        case .dismissed: return "已在 Pixel 8 Pro 上清除"
        case .dismissFailed: return "未能清除"
        }
    }

    private var subtitle: String {
        switch kind {
        case .dismissed: return "通知已在手机端关闭"
        case .dismissFailed: return "手机暂时离线 · 将在重连后重试"
        }
    }
}

// MARK: - Banner button styles

/// Accent small button used inside banners (回复).
private struct MirrorBannerAccentButtonStyle: ButtonStyle {
    @Environment(\.hyphenPalette) private var p
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.hyphenBody(12, weight: .semibold))
            .foregroundColor(p.accentInk)
            .padding(.vertical, 6)
            .padding(.horizontal, 14)
            .background(p.accent.opacity(configuration.isPressed ? 0.85 : 1))
            .clipShape(RoundedRectangle(cornerRadius: 7))
    }
}

/// Hairline small button used inside banners (关闭 / 重试).
private struct MirrorBannerHairlineButtonStyle: ButtonStyle {
    @Environment(\.hyphenPalette) private var p
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.hyphenBody(12, weight: .semibold))
            .foregroundColor(p.text)
            .padding(.vertical, 6)
            .padding(.horizontal, 12)
            .background(Color.clear)
            .overlay(RoundedRectangle(cornerRadius: 7).strokeBorder(p.hair2, lineWidth: 1))
            .opacity(configuration.isPressed ? 0.7 : 1)
    }
}

// MARK: - Preview

#if DEBUG
#Preview("Mirror · privacy modes") {
    VStack(alignment: .leading, spacing: 13) {
        MirrorBannerView(mode: .full)
        MirrorBannerView(mode: .hideBody)
        MirrorBannerView(mode: .existsOnly)
    }
    .padding(28)
    .background(HyphenPalette.dark.canvas)
}

#Preview("Mirror · interactions") {
    VStack(alignment: .leading, spacing: 13) {
        MirrorReplyExpandedView()
        MirrorToastView(kind: .dismissed)
        MirrorToastView(kind: .dismissFailed)
    }
    .padding(28)
    .background(HyphenPalette.dark.canvas)
}
#endif
