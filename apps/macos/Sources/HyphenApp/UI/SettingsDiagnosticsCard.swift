import SwiftUI

// Section E · macOS 设置与诊断 — device-trust and diagnostics cards.
// A 1:1 port of the design handoff "Hyphen Apps.dc.html" lines 566–589:
// the 344pt device-trust card (pinned fingerprint, rename/revoke) and the
// 344pt diagnostics card (trace-ID opt-in, redacted log preview, export/delete).

// MARK: - Device trust card

/// Trusted-device card: 📱 + name + accent "已信任 · 已连接" status, a pinned
/// monospace fingerprint box, and rename / revoke-trust actions.
struct SettingsDeviceTrustCard: View {
    var deviceName: String
    var fingerprint: String
    var onRename: () -> Void
    var onRevoke: () -> Void

    @Environment(\.hyphenPalette) private var p

    init(
        deviceName: String = "Pixel 8 Pro",
        fingerprint: String = "3A:9F:C2:7E:5D:…:A1:E2",
        onRename: @escaping () -> Void = {},
        onRevoke: @escaping () -> Void = {}
    ) {
        self.deviceName = deviceName
        self.fingerprint = fingerprint
        self.onRename = onRename
        self.onRevoke = onRevoke
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 13) {
            // Device identity row.
            HStack(spacing: 12) {
                Text("📱")
                    .font(.system(size: 17))
                    .frame(width: 38, height: 38)
                    .background(p.surface3)
                    .clipShape(RoundedRectangle(cornerRadius: 11))

                VStack(alignment: .leading, spacing: 3) {
                    Text(deviceName)
                        .font(.hyphenBody(14, weight: .semibold))
                        .foregroundColor(p.text)
                    HStack(spacing: 5) {
                        Circle().fill(p.accent).frame(width: 6, height: 6)
                        // Trust and the pinned fingerprint are facts at this
                        // surface; live connection state is not plumbed here, so
                        // we don't claim "已连接".
                        Text("已信任 · 指纹已固定")
                            .font(.hyphenBody(11))
                            .foregroundColor(p.accent)
                    }
                }
                Spacer(minLength: 0)
            }

            // Pinned fingerprint box (auditable mono detail).
            VStack(alignment: .leading, spacing: 0) {
                Text("指纹 已固定")
                Text("SHA‑256 \(fingerprint)")
            }
            .font(.hyphenMono(11))
            .foregroundColor(p.dim)
            .lineSpacing(11 * 0.6)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.vertical, 9)
            .padding(.horizontal, 11)
            .background(p.surface2)
            .overlay(RoundedRectangle(cornerRadius: 9).strokeBorder(p.hair, lineWidth: 1))
            .clipShape(RoundedRectangle(cornerRadius: 9))

            // Actions: rename (secondary) + revoke trust (red-outlined).
            HStack(spacing: 9) {
                Button("重命名", action: onRename)
                    .buttonStyle(SettingsCompactSecondaryButtonStyle())
                Button("撤销信任", action: onRevoke)
                    .buttonStyle(SettingsRevokeButtonStyle())
            }
        }
        .padding(18)
        .frame(width: 344)
        .background(p.surface)
        .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(p.hair, lineWidth: 1))
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .hyphenThemed()
    }
}

// MARK: - Diagnostics card

/// Diagnostics card: local-and-redacted-by-default subtitle, a trace-ID opt-in
/// toggle row (off by default), a redacted mono log preview, and export/delete.
struct SettingsDiagnosticsCard: View {
    var onExport: () -> Void
    var onDelete: () -> Void
    /// Applies the requested trace-ID opt-in behind the host's consent gate and
    /// returns the state actually in effect — the toggle reverts to this if the
    /// user declines, so the control can never disagree with what export does.
    var onRequestTraceIds: (Bool) -> Bool
    @State private var includeTraceIds: Bool

    @Environment(\.hyphenPalette) private var p

    init(
        includeTraceIds: Bool = false,
        onExport: @escaping () -> Void = {},
        onDelete: @escaping () -> Void = {},
        onRequestTraceIds: @escaping (Bool) -> Bool = { $0 }
    ) {
        self._includeTraceIds = State(initialValue: includeTraceIds)
        self.onExport = onExport
        self.onDelete = onDelete
        self.onRequestTraceIds = onRequestTraceIds
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 13) {
            // Title + privacy subtitle.
            VStack(alignment: .leading, spacing: 2) {
                Text("诊断")
                    .font(.hyphenTitle(14, weight: .semibold))
                    .foregroundColor(p.text)
                Text("默认本地保存并脱敏。无遥测、不上传。")
                    .font(.hyphenBody(12))
                    .foregroundColor(p.dim)
                    .fixedSize(horizontal: false, vertical: true)
            }

            // Trace-ID opt-in row (off by default).
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("包含跟踪 ID")
                        .font(.hyphenBody(12, weight: .semibold))
                        .foregroundColor(p.text)
                    Text("选择加入 · 仅用于导出")
                        .font(.hyphenBody(11))
                        .foregroundColor(p.faint)
                }
                Spacer(minLength: 0)
                Toggle("", isOn: $includeTraceIds)
                    .labelsHidden()
                    .toggleStyle(.switch)
                    .tint(p.accent)
                    .onChange(of: includeTraceIds) {
                        let applied = onRequestTraceIds(includeTraceIds)
                        if applied != includeTraceIds { includeTraceIds = applied }
                    }
            }
            .padding(.vertical, 11)
            .padding(.horizontal, 13)
            .background(p.surface2)
            .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(p.hair, lineWidth: 1))
            .clipShape(RoundedRectangle(cornerRadius: 10))

            // Redacted log preview (verbatim, with [已脱敏]/xxx in accent).
            VStack(alignment: .leading, spacing: 0) {
                Text("pair.fail · code=TLS_PIN_MISMATCH")
                    .foregroundColor(p.dim)
                (
                    Text("peer=").foregroundColor(p.dim)
                    + Text("[已脱敏]").foregroundColor(p.accent)
                    + Text(" ip=192.168.1.").foregroundColor(p.dim)
                    + Text("xxx").foregroundColor(p.accent)
                )
                (
                    Text("notif.body=").foregroundColor(p.dim)
                    + Text("[已脱敏]").foregroundColor(p.accent)
                )
            }
            .font(.hyphenMono(10.5))
            .lineSpacing(10.5 * 0.65)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.vertical, 10)
            .padding(.horizontal, 11)
            .background(p.surface2)
            .overlay(RoundedRectangle(cornerRadius: 9).strokeBorder(p.hair, lineWidth: 1))
            .clipShape(RoundedRectangle(cornerRadius: 9))

            // Actions: export (accent) + delete (hairline).
            HStack(spacing: 9) {
                Button("导出诊断包", action: onExport)
                    .buttonStyle(SettingsCompactAccentButtonStyle())
                Button("删除日志", action: onDelete)
                    .buttonStyle(SettingsHairlineButtonStyle())
            }
        }
        .padding(18)
        .frame(width: 344)
        .background(p.surface)
        .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(p.hair, lineWidth: 1))
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .hyphenThemed()
    }
}

// MARK: - Card-local button styles

// The shared button styles stretch to full width and use 10/16 padding. The
// Section E cards use compact, side-by-side buttons (8–9pt padding, 12pt
// labels) per lines 573–574 and 586–587, so these are local variants.

/// Accent fill, accent-ink label — compact (9pt) for "导出诊断包".
private struct SettingsCompactAccentButtonStyle: ButtonStyle {
    @Environment(\.hyphenPalette) private var p
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.hyphenBody(12, weight: .semibold))
            .foregroundColor(p.accentInk)
            .padding(.vertical, 9)
            .frame(maxWidth: .infinity)
            .background(p.accent.opacity(configuration.isPressed ? 0.85 : 1))
            .clipShape(RoundedRectangle(cornerRadius: 8))
    }
}

/// Surface-2 fill, hair-2 border — compact for "重命名".
private struct SettingsCompactSecondaryButtonStyle: ButtonStyle {
    @Environment(\.hyphenPalette) private var p
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.hyphenBody(12, weight: .semibold))
            .foregroundColor(p.text)
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity)
            .background(p.surface2.opacity(configuration.isPressed ? 0.7 : 1))
            .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(p.hair2, lineWidth: 1))
            .clipShape(RoundedRectangle(cornerRadius: 8))
    }
}

/// Transparent fill, red border + red label for "撤销信任".
private struct SettingsRevokeButtonStyle: ButtonStyle {
    @Environment(\.hyphenPalette) private var p
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.hyphenBody(12, weight: .semibold))
            .foregroundColor(p.red.opacity(configuration.isPressed ? 0.7 : 1))
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity)
            .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(p.red, lineWidth: 1))
            .clipShape(RoundedRectangle(cornerRadius: 8))
    }
}

/// Transparent fill, hair-2 border — hugs its label for "删除日志".
private struct SettingsHairlineButtonStyle: ButtonStyle {
    @Environment(\.hyphenPalette) private var p
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.hyphenBody(12, weight: .semibold))
            .foregroundColor(p.text)
            .padding(.vertical, 9)
            .padding(.horizontal, 12)
            .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(p.hair2, lineWidth: 1))
            .clipShape(RoundedRectangle(cornerRadius: 8))
    }
}

#if DEBUG
#Preview("Device trust + Diagnostics · dark") {
    VStack(spacing: 16) {
        SettingsDeviceTrustCard()
        SettingsDiagnosticsCard()
    }
    .padding(32)
    .background(Color.hex(0x0b0c0f))
    .preferredColorScheme(.dark)
}

#Preview("Device trust + Diagnostics · light") {
    VStack(spacing: 16) {
        SettingsDeviceTrustCard()
        SettingsDiagnosticsCard()
    }
    .padding(32)
    .background(Color.hex(0xe9ebef))
    .preferredColorScheme(.light)
}
#endif
