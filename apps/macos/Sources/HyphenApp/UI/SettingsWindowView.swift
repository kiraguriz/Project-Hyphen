import SwiftUI

// Section E · macOS 设置与诊断 — settings window.
// A 1:1 port of the design handoff "Hyphen Apps.dc.html" lines 506–559:
// the 632pt settings window with a left nav sidebar and the 通知隐私
// (notification privacy) pane — global mirroring toggle plus a per-app
// privacy mode list.

// MARK: - Models

/// Per-app notification privacy level shown in the settings list.
enum SettingsPrivacyMode: CaseIterable {
    case full        // 完整
    case hideBody    // 隐藏内容
    case existsOnly  // 仅提示

    var label: String {
        switch self {
        case .full: return "完整"
        case .hideBody: return "隐藏内容"
        case .existsOnly: return "仅提示"
        }
    }
}

/// One app's privacy row: glyph tile + name + selected privacy mode.
struct SettingsAppPrivacyRow: Identifiable {
    let id = UUID()
    var name: String
    var glyph: String
    var tint: Color
    var mode: SettingsPrivacyMode

    static let defaults: [SettingsAppPrivacyRow] = [
        SettingsAppPrivacyRow(name: "微信", glyph: "微", tint: .hex(0x1f9d57), mode: .full),
        SettingsAppPrivacyRow(name: "Gmail", glyph: "G", tint: .hex(0xd6453f), mode: .full),
        SettingsAppPrivacyRow(name: "Telegram", glyph: "T", tint: .hex(0x2f6fe0), mode: .hideBody),
        SettingsAppPrivacyRow(name: "银行 App", glyph: "银", tint: .hex(0x5b626d), mode: .existsOnly)
    ]
}

/// Left-sidebar navigation sections.
enum SettingsNavSection: CaseIterable, Identifiable {
    case general       // 通用
    case devices       // 设备
    case notifications // 通知隐私
    case transfer      // 传输
    case diagnostics   // 诊断

    var id: Self { self }

    var label: String {
        switch self {
        case .general: return "通用"
        case .devices: return "设备"
        case .notifications: return "通知隐私"
        case .transfer: return "传输"
        case .diagnostics: return "诊断"
        }
    }

    var icon: String {
        switch self {
        case .general: return "⚙"
        case .devices: return "📱"
        case .notifications: return "◐"
        case .transfer: return "⤓"
        case .diagnostics: return "◔"
        }
    }
}

// MARK: - Settings window

/// Full settings window: window chrome + two-column nav/content layout, opened
/// on the 通知隐私 pane.
struct SettingsWindowView: View {
    @State private var selection: SettingsNavSection
    @State private var mirroringEnabled: Bool
    @State private var rows: [SettingsAppPrivacyRow]

    // Live wiring: the 设备 and 诊断 panes route to the real device-trust and
    // diagnostics cards. Closures default to no-ops so the window still renders
    // standalone in previews. `deviceName`/`fingerprint` are nil when no peer is
    // paired — the 设备 pane then shows a placeholder instead of a fake device.
    private let deviceName: String?
    private let fingerprint: String?
    private let includeTraceIds: Bool
    private let onClose: () -> Void
    private let onRevokeTrust: () -> Void
    private let onRenameDevice: () -> Void
    private let onExportDiagnostics: () -> Void
    private let onDeleteDiagnostics: () -> Void
    private let onRequestTraceIds: (Bool) -> Bool

    @Environment(\.hyphenPalette) private var p

    init(
        selection: SettingsNavSection = .notifications,
        mirroringEnabled: Bool = true,
        rows: [SettingsAppPrivacyRow] = SettingsAppPrivacyRow.defaults,
        deviceName: String? = "Pixel 8 Pro",
        fingerprint: String? = "3A:9F:C2:7E:5D:…:A1:E2",
        includeTraceIds: Bool = false,
        onClose: @escaping () -> Void = {},
        onRevokeTrust: @escaping () -> Void = {},
        onRenameDevice: @escaping () -> Void = {},
        onExportDiagnostics: @escaping () -> Void = {},
        onDeleteDiagnostics: @escaping () -> Void = {},
        onRequestTraceIds: @escaping (Bool) -> Bool = { $0 }
    ) {
        self._selection = State(initialValue: selection)
        self._mirroringEnabled = State(initialValue: mirroringEnabled)
        self._rows = State(initialValue: rows)
        self.deviceName = deviceName
        self.fingerprint = fingerprint
        self.includeTraceIds = includeTraceIds
        self.onClose = onClose
        self.onRevokeTrust = onRevokeTrust
        self.onRenameDevice = onRenameDevice
        self.onExportDiagnostics = onExportDiagnostics
        self.onDeleteDiagnostics = onDeleteDiagnostics
        self.onRequestTraceIds = onRequestTraceIds
    }

    var body: some View {
        VStack(spacing: 0) {
            WindowChrome(title: "Hyphen 设置", onClose: onClose)
            HStack(spacing: 0) {
                sidebar
                pane
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            }
            .frame(minHeight: 380)
        }
        .frame(width: 632)
        .background(p.surface)
        .overlay(RoundedRectangle(cornerRadius: 13).strokeBorder(p.hair, lineWidth: 1))
        .clipShape(RoundedRectangle(cornerRadius: 13))
        .shadow(color: .black.opacity(0.4), radius: 30, x: 0, y: 24)
        .hyphenThemed()
    }

    // MARK: Pane routing

    @ViewBuilder
    private var pane: some View {
        switch selection {
        case .notifications:
            notificationsPane
        case .devices:
            cardPane {
                if let deviceName, let fingerprint {
                    SettingsDeviceTrustCard(
                        deviceName: deviceName,
                        fingerprint: fingerprint,
                        onRename: onRenameDevice,
                        onRevoke: onRevokeTrust
                    )
                } else {
                    devicePlaceholder
                }
            }
        case .diagnostics:
            cardPane {
                SettingsDiagnosticsCard(
                    includeTraceIds: includeTraceIds,
                    onExport: onExportDiagnostics,
                    onDelete: onDeleteDiagnostics,
                    onRequestTraceIds: onRequestTraceIds
                )
            }
        case .general, .transfer:
            placeholderPane(selection)
        }
    }

    private func cardPane<Card: View>(@ViewBuilder _ card: () -> Card) -> some View {
        VStack(alignment: .leading) {
            card()
            Spacer(minLength: 0)
        }
        .padding(.vertical, 20)
        .padding(.horizontal, 22)
    }

    /// Shown in the 设备 pane when no peer is paired — avoids asserting trust in
    /// a device that does not exist.
    private var devicePlaceholder: some View {
        VStack(spacing: 8) {
            Text("📱").font(.system(size: 30)).foregroundColor(p.faint)
            Text("尚无已配对的设备")
                .font(.hyphenBody(13, weight: .semibold))
                .foregroundColor(p.text)
            Text("点击菜单栏的「＋」配对一台 Android 设备。")
                .font(.hyphenBody(12))
                .foregroundColor(p.dim)
        }
        .frame(maxWidth: .infinity, alignment: .center)
        .padding(.top, 24)
    }

    private func placeholderPane(_ section: SettingsNavSection) -> some View {
        VStack(spacing: 8) {
            Spacer()
            Text(section.icon).font(.system(size: 30)).foregroundColor(p.faint)
            Text("\(section.label)设置即将提供")
                .font(.hyphenBody(13))
                .foregroundColor(p.dim)
            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(20)
    }

    // MARK: Sidebar

    private var sidebar: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("Hyphen")
                .font(.hyphenBody(11, weight: .semibold))
                .foregroundColor(p.faint)
                .padding(.horizontal, 10)
                .padding(.top, 6)
                .padding(.bottom, 4)

            ForEach(SettingsNavSection.allCases) { section in
                navRow(section)
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 12)
        .padding(.horizontal, 10)
        .frame(width: 188, alignment: .topLeading)
        .frame(maxHeight: .infinity)
        .background(p.surface2)
        .overlay(Rectangle().fill(p.hair).frame(width: 1), alignment: .trailing)
    }

    private func navRow(_ section: SettingsNavSection) -> some View {
        let active = section == selection
        return Button {
            selection = section
        } label: {
            HStack(spacing: 10) {
                Text(section.icon)
                    .font(.system(size: 13))
                    .foregroundColor(active ? p.accent : p.dim)
                    .frame(width: 18)
                Text(section.label)
                    .font(.hyphenBody(13, weight: active ? .semibold : .regular))
                    .foregroundColor(active ? p.text : p.dim)
                Spacer(minLength: 0)
            }
            .padding(.vertical, 8)
            .padding(.horizontal, 10)
            .background(active ? p.accentSoft : Color.clear)
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .strokeBorder(active ? p.accent : Color.clear, lineWidth: 1)
            )
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    // MARK: 通知隐私 pane

    private var notificationsPane: some View {
        VStack(alignment: .leading, spacing: 16) {
            // Header + global mirroring toggle.
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("通知镜像")
                        .font(.hyphenTitle(16, weight: .semibold))
                        .foregroundColor(p.text)
                    Text("在 Mac 上接收手机通知。不保存通知历史。")
                        .font(.hyphenBody(12))
                        .foregroundColor(p.dim)
                }
                Spacer(minLength: 0)
                Toggle("", isOn: $mirroringEnabled)
                    .labelsHidden()
                    .toggleStyle(.switch)
                    .tint(p.accent)
            }

            Hairline()

            Text("逐应用隐私")
                .font(.hyphenBody(11, weight: .semibold))
                .tracking(11 * 0.04)
                .foregroundColor(p.faint)

            VStack(spacing: 10) {
                ForEach($rows) { $row in
                    appRow($row)
                }
            }
        }
        .padding(.vertical, 20)
        .padding(.horizontal, 22)
    }

    private func appRow(_ row: Binding<SettingsAppPrivacyRow>) -> some View {
        HStack(spacing: 12) {
            AppGlyph(label: row.wrappedValue.glyph, tint: row.wrappedValue.tint, size: 32, corner: 9)
            Text(row.wrappedValue.name)
                .font(.hyphenBody(13, weight: .semibold))
                .foregroundColor(p.text)
            Spacer(minLength: 0)
            SettingsPrivacySegmentedControl(mode: row.mode)
        }
    }
}

// MARK: - 3-segment privacy control

/// 完整 / 隐藏内容 / 仅提示 segmented control. The selected 完整 swatch uses the
/// accent fill (accent-ink label); 隐藏内容 / 仅提示 selected swatches use the
/// --text fill on a --surface label, matching the design.
struct SettingsPrivacySegmentedControl: View {
    @Binding var mode: SettingsPrivacyMode
    @Environment(\.hyphenPalette) private var p

    var body: some View {
        HStack(spacing: 0) {
            ForEach(SettingsPrivacyMode.allCases, id: \.self) { option in
                segment(option)
            }
        }
        .padding(2)
        .background(p.surface2)
        .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(p.hair, lineWidth: 1))
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    private func segment(_ option: SettingsPrivacyMode) -> some View {
        let selected = option == mode
        let fill: Color = option == .full ? p.accent : p.text
        let labelColor: Color = option == .full ? p.accentInk : p.surface
        return Text(option.label)
            .font(.hyphenBody(11, weight: .semibold))
            .foregroundColor(selected ? labelColor : p.dim)
            .padding(.vertical, 5)
            .padding(.horizontal, 10)
            .background(selected ? fill : Color.clear)
            .clipShape(RoundedRectangle(cornerRadius: 6))
            .contentShape(Rectangle())
            .onTapGesture { mode = option }
    }
}

#if DEBUG
#Preview("Settings · 通知隐私 · dark") {
    SettingsWindowView()
        .padding(32)
        .background(Color.hex(0x0b0c0f))
        .preferredColorScheme(.dark)
}

#Preview("Settings · 通知隐私 · light") {
    SettingsWindowView()
        .padding(32)
        .background(Color.hex(0xe9ebef))
        .preferredColorScheme(.light)
}
#endif
