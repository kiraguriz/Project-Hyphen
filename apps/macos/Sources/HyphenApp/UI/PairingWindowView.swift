import SwiftUI

// Section C · macOS 配对与权限 — Pairing window (512pt) from the design handoff
// "Hyphen Apps.dc.html" lines 336–380. Two-column layout: a QR + LAN address
// on the left (surface-2), and the SAS verification flow on the right where the
// user confirms both devices show the same code before trust is established.

// MARK: - Step model

/// One node in the 3-step pairing progress indicator (扫码 → 校验 → 完成).
private struct PairingStepBadge: View {
    enum Kind { case done, current, upcoming }
    let kind: Kind
    let number: String
    let label: String
    @Environment(\.hyphenPalette) private var p

    var body: some View {
        HStack(spacing: 5) {
            badge
            Text(label)
                .font(.hyphenBody(11, weight: .semibold))
                .foregroundColor(labelColor)
        }
    }

    @ViewBuilder private var badge: some View {
        switch kind {
        case .done:
            Text("✓")
                .font(.hyphenBody(10))
                .foregroundColor(p.accentInk)
                .frame(width: 16, height: 16)
                .background(p.accent)
                .clipShape(Circle())
        case .current:
            Text(number)
                .font(.hyphenBody(10))
                .foregroundColor(p.surface)
                .frame(width: 16, height: 16)
                .background(p.text)
                .clipShape(Circle())
        case .upcoming:
            Text(number)
                .font(.hyphenBody(10))
                .foregroundColor(p.faint)
                .frame(width: 16, height: 16)
                .overlay(Circle().strokeBorder(p.hair2, lineWidth: 1))
        }
    }

    private var labelColor: Color {
        switch kind {
        case .done: return p.accent
        case .current: return p.text
        case .upcoming: return p.faint
        }
    }
}

// MARK: - Pairing window

/// QR + SAS pairing window. The left column carries the QR and LAN address; the
/// right column walks the SAS verification (扫码 ✓ → 校验 → 完成) and lets the
/// user confirm or reject the match.
struct PairingWindowView: View {
    let sasCodes: [String]
    let address: String
    let peerName: String
    let fingerprint: String
    let qrPayload: String
    let awaitingConfirmation: Bool
    var onClose: () -> Void
    var onConfirm: () -> Void
    var onReject: () -> Void

    @Environment(\.hyphenPalette) private var p

    init(
        sasCodes: [String] = ["47", "29", "16"],
        address: String = "192.168.1.24:7420",
        peerName: String = "Pixel 8 Pro",
        fingerprint: String = "SHA‑256 · 3A:9F:C2:7E:…:E2",
        qrPayload: String = "hyphen://pair?addr=192.168.1.24:7420",
        awaitingConfirmation: Bool = true,
        onClose: @escaping () -> Void = {},
        onConfirm: @escaping () -> Void = {},
        onReject: @escaping () -> Void = {}
    ) {
        self.sasCodes = sasCodes
        self.address = address
        self.peerName = peerName
        self.fingerprint = fingerprint
        self.qrPayload = qrPayload
        self.awaitingConfirmation = awaitingConfirmation
        self.onClose = onClose
        self.onConfirm = onConfirm
        self.onReject = onReject
    }

    var body: some View {
        VStack(spacing: 0) {
            WindowChrome(title: "配对新设备", onClose: onClose)
            HStack(alignment: .top, spacing: 0) {
                leftColumn
                rightColumn
            }
        }
        .frame(width: 512)
        .background(p.surface)
        .overlay(RoundedRectangle(cornerRadius: 13).strokeBorder(p.hair, lineWidth: 1))
        .clipShape(RoundedRectangle(cornerRadius: 13))
        .shadow(color: .black.opacity(0.4), radius: 30, x: 0, y: 24)
        .hyphenThemed()
    }

    // MARK: Left column

    private var leftColumn: some View {
        VStack(spacing: 14) {
            PairingQRCodeView(payload: qrPayload, size: 132)
            VStack(spacing: 3) {
                Text("在手机上扫描")
                    .font(.hyphenBody(11))
                    .foregroundColor(p.faint)
                Text(address)
                    .font(.hyphenMono(11))
                    .foregroundColor(p.dim)
            }
        }
        .frame(width: 200)
        .padding(22)
        .background(p.surface2)
        .overlay(Rectangle().fill(p.hair).frame(width: 1), alignment: .trailing)
    }

    // MARK: Right column

    private var rightColumn: some View {
        VStack(alignment: .leading, spacing: 16) {
            stepIndicator

            VStack(alignment: .leading, spacing: 5) {
                Text("确认两台设备的校验码一致")
                    .font(.hyphenTitle(17, weight: .semibold))
                    .tracking(-0.17)
                    .foregroundColor(p.text)
                Text("若数字相同，即可在双方之间建立可信连接（SAS）。")
                    .font(.hyphenBody(12))
                    .lineSpacing(12 * 0.5)
                    .foregroundColor(p.dim)
                    .fixedSize(horizontal: false, vertical: true)
            }

            sasTiles
            peerRow

            Spacer(minLength: 0)

            HStack(spacing: 10) {
                Button("一致，建立信任", action: onConfirm)
                    .buttonStyle(AccentButtonStyle())
                    .disabled(!awaitingConfirmation)
                Button("不一致", action: onReject)
                    .buttonStyle(DangerTextButtonStyle())
                    .fixedSize()
                    .disabled(!awaitingConfirmation)
            }
        }
        .padding(22)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var stepIndicator: some View {
        HStack(spacing: 6) {
            PairingStepBadge(kind: .done, number: "1", label: "扫码")
            Rectangle().fill(p.hair2).frame(width: 14, height: 1)
            PairingStepBadge(kind: .current, number: "2", label: "校验")
            Rectangle().fill(p.hair2).frame(width: 14, height: 1)
            PairingStepBadge(kind: .upcoming, number: "3", label: "完成")
        }
    }

    private var sasTiles: some View {
        HStack(spacing: 9) {
            ForEach(Array(sasCodes.enumerated()), id: \.offset) { _, code in
                Text(code)
                    .font(.hyphenMono(26, weight: .semibold))
                    .tracking(26 * 0.06)
                    .foregroundColor(p.accent)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
                    .background(p.surface2)
                    .overlay(RoundedRectangle(cornerRadius: 11).strokeBorder(p.hair, lineWidth: 1))
                    .clipShape(RoundedRectangle(cornerRadius: 11))
            }
        }
    }

    private var peerRow: some View {
        HStack(spacing: 9) {
            Text("📱")
                .font(.hyphenBody(12))
                .frame(width: 24, height: 24)
                .background(p.surface3)
                .clipShape(RoundedRectangle(cornerRadius: 7))
            VStack(alignment: .leading, spacing: 1) {
                Text(peerName)
                    .font(.hyphenBody(12, weight: .semibold))
                    .foregroundColor(p.text)
                Text(fingerprint)
                    .font(.hyphenMono(11))
                    .foregroundColor(p.dim)
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 10)
        .padding(.horizontal, 12)
        .background(p.surface2)
        .clipShape(RoundedRectangle(cornerRadius: 10))
    }
}

#if DEBUG
#Preview("Pairing window · dark") {
    PairingWindowView()
        .padding(40)
        .background(Color.hex(0x0b0c0f))
        .preferredColorScheme(.dark)
}

#Preview("Pairing window · light") {
    PairingWindowView()
        .padding(40)
        .background(Color.hex(0xe9ebef))
        .preferredColorScheme(.light)
}
#endif
