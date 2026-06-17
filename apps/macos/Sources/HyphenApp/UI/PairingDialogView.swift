import SwiftUI
import AppKit
import HyphenCore

// Section C · macOS 配对与权限 — Local Network Permission onboarding.
// A 1:1 port of the design handoff "Hyphen Apps.dc.html" lines 311–333:
// the pre-permission explainer dialog (376pt) that runs before macOS surfaces
// the system "Local Network" prompt, plus the amber "denied repair" banner.

// MARK: - Local Network onboarding dialog

/// Pre-permission explainer shown before triggering the macOS Local Network
/// system prompt. Surfaces what Hyphen will (and will not) do on the LAN.
struct PairingLocalNetworkDialogView: View {
    var onContinue: () -> Void
    var onNotNow: () -> Void

    @Environment(\.hyphenPalette) private var p

    init(
        onContinue: @escaping () -> Void = {},
        onNotNow: @escaping () -> Void = {}
    ) {
        self.onContinue = onContinue
        self.onNotNow = onNotNow
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            // Brand mark row (three abstract glyphs from the design).
            HStack(spacing: 8) {
                RoundedRectangle(cornerRadius: 5)
                    .fill(p.surface3)
                    .overlay(RoundedRectangle(cornerRadius: 5).strokeBorder(p.hair2, lineWidth: 1))
                    .frame(width: 14, height: 14)
                RoundedRectangle(cornerRadius: 3)
                    .fill(p.accent)
                    .frame(width: 16, height: 4)
                RoundedRectangle(cornerRadius: 4)
                    .fill(p.surface3)
                    .overlay(RoundedRectangle(cornerRadius: 4).strokeBorder(p.hair2, lineWidth: 1))
                    .frame(width: 11, height: 16)
            }

            Text(L("localnet.title"))
                .font(.hyphenTitle(18, weight: .semibold))
                .tracking(-0.18)
                .lineSpacing(18 * 0.35)
                .foregroundColor(p.text)
                .fixedSize(horizontal: false, vertical: true)

            Text(L("localnet.body1"))
                .font(.hyphenBody(13))
                .lineSpacing(13 * 0.65)
                .foregroundColor(p.dim)
                .fixedSize(horizontal: false, vertical: true)

            Text(L("localnet.body2"))
                .font(.hyphenBody(13))
                .lineSpacing(13 * 0.65)
                .foregroundColor(p.dim)
                .fixedSize(horizontal: false, vertical: true)

            // Surface-2 info box: no-permission fallback. Colored runs are kept
            // separate so each localizes independently (spacing baked into runs).
            (
                Text(L("localnet.alt.prefix"))
                    .foregroundColor(p.faint)
                + Text(L("localnet.alt.scanQR")).foregroundColor(p.text)
                + Text(L("localnet.alt.or")).foregroundColor(p.faint)
                + Text(L("localnet.alt.enterAddr")).foregroundColor(p.text)
                + Text(L("localnet.alt.suffix")).foregroundColor(p.faint)
            )
            .font(.hyphenBody(12))
            .lineSpacing(12 * 0.6)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.vertical, 11)
            .padding(.horizontal, 13)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(p.surface2)
            .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(p.hair, lineWidth: 1))
            .clipShape(RoundedRectangle(cornerRadius: 10))

            HStack(spacing: 10) {
                Spacer()
                LocalNetworkDialogActionButton(
                    title: L("common.notNow"),
                    kind: .secondary,
                    action: onNotNow
                )
                LocalNetworkDialogActionButton(
                    title: L("localnet.continue"),
                    kind: .accent,
                    action: onContinue
                )
            }
            .padding(.top, 2)
        }
        .padding(EdgeInsets(top: 26, leading: 26, bottom: 20, trailing: 26))
        .frame(width: 376)
        .background(p.surface)
        .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(p.hair, lineWidth: 1))
        .clipShape(RoundedRectangle(cornerRadius: 18))
        .shadow(color: .black.opacity(0.4), radius: 30, x: 0, y: 24)
        .hyphenThemed()
        .hyphenDynamicTypeClamp()
    }
}

private struct LocalNetworkDialogActionButton: NSViewRepresentable {
    enum Kind {
        case secondary
        case accent
    }

    let title: String
    let kind: Kind
    let action: () -> Void

    @Environment(\.colorScheme) private var colorScheme

    func makeCoordinator() -> Coordinator {
        Coordinator(action: action)
    }

    func makeNSView(context: Context) -> NSButton {
        let button = NSButton(title: title, target: context.coordinator, action: #selector(Coordinator.activate))
        button.setButtonType(NSButton.ButtonType.momentaryPushIn)
        button.bezelStyle = NSButton.BezelStyle.regularSquare
        button.isBordered = false
        button.focusRingType = NSFocusRingType.none
        button.wantsLayer = true
        button.translatesAutoresizingMaskIntoConstraints = false
        button.heightAnchor.constraint(equalToConstant: 40).isActive = true
        button.widthAnchor.constraint(greaterThanOrEqualToConstant: 58).isActive = true
        button.setContentHuggingPriority(NSLayoutConstraint.Priority.required, for: NSLayoutConstraint.Orientation.horizontal)
        button.setContentCompressionResistancePriority(
            NSLayoutConstraint.Priority.required,
            for: NSLayoutConstraint.Orientation.horizontal
        )
        applyStyle(to: button)
        return button
    }

    func updateNSView(_ button: NSButton, context: Context) {
        context.coordinator.action = action
        button.title = title
        applyStyle(to: button)
    }

    private func applyStyle(to button: NSButton) {
        // Colors derive from the shared HyphenPalette (single source of truth);
        // no hex literals are duplicated here.
        let palette = HyphenPalette.forScheme(colorScheme)
        let foreground = palette.nsColor(kind == .accent ? \.accentInk : \.text)
        button.attributedTitle = NSAttributedString(
            string: title,
            attributes: [
                .font: NSFont.systemFont(ofSize: 13, weight: .semibold),
                .foregroundColor: foreground
            ]
        )
        button.layer?.cornerRadius = 9
        button.layer?.masksToBounds = true
        switch kind {
        case .secondary:
            button.layer?.backgroundColor = palette.nsColor(\.surface2).cgColor
            button.layer?.borderWidth = 1
            button.layer?.borderColor = palette.nsColor(\.hair2).cgColor
        case .accent:
            button.layer?.backgroundColor = palette.nsColor(\.accent).cgColor
            button.layer?.borderWidth = 0
            button.layer?.borderColor = nil
        }
        button.setAccessibilityTitle(title)
        button.setAccessibilityLabel(title)
    }

    final class Coordinator: NSObject {
        var action: () -> Void

        init(action: @escaping () -> Void) {
            self.action = action
        }

        @objc func activate() {
            action()
        }
    }
}

// MARK: - Denied repair banner

/// Amber banner shown when Local Network access was denied. Points the user at
/// QR pairing or the System Settings toggle to re-enable Hyphen.
///
/// STAGED DESIGN SURFACE — no call site yet. It lands when the denied-LAN repair
/// flow is wired (the explain-first dialog in this file is already live). Until
/// then this is intentional scaffolding, not forgotten/dead code.
struct PairingDeniedBanner: View {
    @Environment(\.hyphenPalette) private var p

    init() {}

    var body: some View {
        HStack(alignment: .top, spacing: 11) {
            Text("!")
                .font(.hyphenBody(12, weight: .bold))
                .foregroundColor(Color.hex(0x0b0c0f))
                .frame(width: 18, height: 18)
                .background(p.amber)
                .clipShape(Circle())

            (
                Text(L("denied.prefix"))
                    .foregroundColor(p.text)
                + Text(L("denied.path")).foregroundColor(p.dim)
                + Text(L("denied.suffix")).foregroundColor(p.text)
            )
            .font(.hyphenBody(12))
            .lineSpacing(12 * 0.55)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.vertical, 12)
        .padding(.horizontal, 14)
        .frame(width: 376)
        .background(p.amberSoft)
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(p.amber, lineWidth: 1))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .hyphenThemed()
    }
}

// MARK: - QR code

/// Real QR rendered from `payload` via CoreImage and drawn nearest-neighbor
/// (no smoothing) so modules stay crisp. Dark modules `#15171c` on white,
/// inside a white rounded-12 card with padding (matching the design's QR card).
struct PairingQRCodeView: View {
    let payload: String
    var size: CGFloat

    init(
        payload: String = "hyphen://pair?addr=192.168.1.24:7420",
        size: CGFloat = 132
    ) {
        self.payload = payload
        self.size = size
    }

    var body: some View {
        Group {
            if let image = Self.makeQRImage(from: payload) {
                Image(decorative: image, scale: 1, orientation: .up)
                    .interpolation(.none)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
            } else {
                // Defensive fallback for the shared Core renderer so the SwiftUI view stays total.
                Color.white
            }
        }
        .frame(width: size, height: size)
        .padding(12)
        .background(Color.white)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .shadow(color: .black.opacity(0.18), radius: 8, x: 0, y: 4)
    }

    private static func makeQRImage(from payload: String) -> CGImage? {
        QRCodeRenderer.image(for: payload)
    }
}

#if DEBUG
#Preview("Local Network · dark") {
    VStack(spacing: 11) {
        PairingLocalNetworkDialogView()
        PairingDeniedBanner()
    }
    .padding(32)
    .background(Color.hex(0x0b0c0f))
    .preferredColorScheme(.dark)
}

#Preview("Local Network · light") {
    VStack(spacing: 11) {
        PairingLocalNetworkDialogView()
        PairingDeniedBanner()
    }
    .padding(32)
    .background(Color.hex(0xe9ebef))
    .preferredColorScheme(.light)
}

#Preview("QR") {
    PairingQRCodeView()
        .padding(40)
        .background(Color.hex(0x1c1f25))
        .preferredColorScheme(.dark)
}
#endif
