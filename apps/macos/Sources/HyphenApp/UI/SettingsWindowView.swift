import SwiftUI
import HyphenNotifications

// Section E · macOS 设置与诊断 — settings window.
// A 1:1 port of the design handoff "Hyphen Apps.dc.html" lines 506–559:
// the 632pt settings window with a left nav sidebar and the 通知隐私
// (notification privacy) pane — global mirroring toggle plus a per-app
// privacy mode list.

// MARK: - Models

extension NotificationPrivacyMode {
    var settingsLabel: String {
        switch self {
        case .full: return L("privacy.full")
        case .hideBody: return L("privacy.hideBody")
        case .existsOnly: return L("privacy.existsOnly")
        }
    }
}

/// One app's privacy row: glyph tile + name + selected privacy mode.
struct SettingsAppPrivacyRow: Identifiable {
    var id: String { packageName }
    var name: String
    var packageName: String
    var glyph: String
    var tint: Color
    var mode: NotificationPrivacyMode
    var isExplicit: Bool = true

    // Per-package rows only. The "other apps" default lives in an explicit
    // `defaultMode` (no "*" sentinel mixed into the package list).
    static let previewRows: [SettingsAppPrivacyRow] = [
        SettingsAppPrivacyRow(name: "微信", packageName: "com.tencent.mm", glyph: "微", tint: .hex(0x1f9d57), mode: .full),
        SettingsAppPrivacyRow(name: "Gmail", packageName: "com.google.android.gm", glyph: "G", tint: .hex(0xd6453f), mode: .full),
        SettingsAppPrivacyRow(name: "Telegram", packageName: "org.telegram.messenger", glyph: "T", tint: .hex(0x2f6fe0), mode: .hideBody)
    ]

    /// Privacy-first default for apps without an explicit per-package row.
    static let defaultModeForOtherApps: NotificationPrivacyMode = .existsOnly
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
        case .general: return L("settings.nav.general")
        case .devices: return L("settings.nav.devices")
        case .notifications: return L("settings.nav.notifications")
        case .transfer: return L("settings.nav.transfer")
        case .diagnostics: return L("settings.nav.diagnostics")
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
    @ObservedObject private var activityModel: HyphenAppModel
    @State private var selection: SettingsNavSection
    @State private var mirroringEnabled: Bool
    @State private var rows: [SettingsAppPrivacyRow]
    @State private var defaultMode: NotificationPrivacyMode
    @State private var includeTraceIds: Bool

    // Live wiring: the 设备 and 诊断 panes route to the real device-trust and
    // diagnostics cards. Closures default to no-ops so the window still renders
    // standalone in previews. `deviceName`/`fingerprint` are nil when no peer is
    // paired — the 设备 pane then shows a placeholder instead of a fake device.
    private let deviceName: String?
    private let fingerprint: String?
    private let diagnosticsPreview: String
    private let privacySyncState: NotificationPrivacySyncState
    private let onClose: () -> Void
    private let onRevokeTrust: () -> Void
    private let onRenameDevice: () -> Void
    private let onExportDiagnostics: () -> Void
    private let onDeleteDiagnostics: () -> Void
    private let onRequestTraceIds: (Bool) -> Bool
    private let onNotificationPrivacyChange: (Bool, NotificationPrivacyMode, [SettingsAppPrivacyRow]) -> Void
    private let onCancelTransfer: (String) -> Void
    private let onRetryTransferSave: (String) -> Void
    private let onSelectionChange: (SettingsNavSection) -> Void

    @Environment(\.hyphenPalette) private var p

    init(
        activityModel: HyphenAppModel = HyphenAppModel(),
        selection: SettingsNavSection = .notifications,
        mirroringEnabled: Bool = true,
        rows: [SettingsAppPrivacyRow] = SettingsAppPrivacyRow.previewRows,
        defaultMode: NotificationPrivacyMode = SettingsAppPrivacyRow.defaultModeForOtherApps,
        deviceName: String? = "Pixel 8 Pro",
        fingerprint: String? = "3A:9F:C2:7E:5D:…:A1:E2",
        diagnosticsPreview: String = L("settings.diag.previewUnavailable"),
        privacySyncState: NotificationPrivacySyncState = .offline,
        includeTraceIds: Bool = false,
        onClose: @escaping () -> Void = {},
        onRevokeTrust: @escaping () -> Void = {},
        onRenameDevice: @escaping () -> Void = {},
        onExportDiagnostics: @escaping () -> Void = {},
        onDeleteDiagnostics: @escaping () -> Void = {},
        onRequestTraceIds: @escaping (Bool) -> Bool = { $0 },
        onNotificationPrivacyChange: @escaping (Bool, NotificationPrivacyMode, [SettingsAppPrivacyRow]) -> Void = { _, _, _ in },
        onCancelTransfer: @escaping (String) -> Void = { _ in },
        onRetryTransferSave: @escaping (String) -> Void = { _ in },
        onSelectionChange: @escaping (SettingsNavSection) -> Void = { _ in }
    ) {
        self.activityModel = activityModel
        self._selection = State(initialValue: selection)
        self._mirroringEnabled = State(initialValue: mirroringEnabled)
        self._rows = State(initialValue: rows)
        self._defaultMode = State(initialValue: defaultMode)
        self._includeTraceIds = State(initialValue: includeTraceIds)
        self.deviceName = deviceName
        self.fingerprint = fingerprint
        self.diagnosticsPreview = diagnosticsPreview
        self.privacySyncState = privacySyncState
        self.onClose = onClose
        self.onRevokeTrust = onRevokeTrust
        self.onRenameDevice = onRenameDevice
        self.onExportDiagnostics = onExportDiagnostics
        self.onDeleteDiagnostics = onDeleteDiagnostics
        self.onRequestTraceIds = onRequestTraceIds
        self.onNotificationPrivacyChange = onNotificationPrivacyChange
        self.onCancelTransfer = onCancelTransfer
        self.onRetryTransferSave = onRetryTransferSave
        self.onSelectionChange = onSelectionChange
    }

    var body: some View {
        HyphenCard(cornerRadius: 13, padding: 0) {
            VStack(spacing: 0) {
                WindowChrome(title: L("settings.windowTitle"), onClose: onClose)
                HStack(spacing: 0) {
                    sidebar
                    pane
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                }
                .frame(minHeight: 380)
            }
            .frame(width: 632)
        }
        .shadow(color: .black.opacity(0.4), radius: 30, x: 0, y: 24)
        .hyphenThemed()
        .hyphenDynamicTypeClamp()
        .onChange(of: selection) {
            // Report the active pane so the owner can preserve it across a
            // refresh (trace-ID consent, trust revoke/reset) instead of snapping
            // back to the default pane.
            onSelectionChange(selection)
        }
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
                    previewText: diagnosticsPreview,
                    includeTraceIds: $includeTraceIds,
                    onExport: onExportDiagnostics,
                    onDelete: onDeleteDiagnostics,
                    onRequestTraceIds: onRequestTraceIds
                )
            }
        case .transfer:
            cardPane {
                TransferPanelView(
                    peerName: activityModel.connection.peerName ?? deviceName,
                    rows: TransferRow.rows(from: activityModel.feed),
                    isConnected: activityModel.connection.state == .connected,
                    onCancel: onCancelTransfer,
                    onRetrySave: onRetryTransferSave
                )
            }
        case .general:
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
            Text(L("settings.device.placeholderTitle"))
                .font(.hyphenBody(13, weight: .semibold))
                .foregroundColor(p.text)
            Text(L("settings.device.placeholderDetail"))
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
            Text(L("settings.comingSoon", section.label))
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
                    Text(L("settings.notif.title"))
                        .font(.hyphenTitle(16, weight: .semibold))
                        .foregroundColor(p.text)
                    Text(L("settings.notif.subtitle"))
                        .font(.hyphenBody(12))
                        .foregroundColor(p.dim)
                    Text(privacySyncLabel)
                        .font(.hyphenMono(10, weight: .semibold))
                        .foregroundColor(privacySyncColor)
                }
                Spacer(minLength: 0)
                Toggle("", isOn: $mirroringEnabled)
                    .labelsHidden()
                    .toggleStyle(.switch)
                    .tint(p.accent)
                    .onChange(of: mirroringEnabled) {
                        onNotificationPrivacyChange(mirroringEnabled, defaultMode, rows)
                    }
            }

            Hairline()

            Text(L("settings.notif.perApp"))
                .font(.hyphenBody(11, weight: .semibold))
                .tracking(11 * 0.04)
                .foregroundColor(p.faint)

            VStack(spacing: 10) {
                if rows.isEmpty {
                    Text(L("settings.notif.emptyApps"))
                        .font(.hyphenBody(11))
                        .foregroundColor(p.dim)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.vertical, 6)
                } else {
                    ForEach($rows) { $row in
                        appRow($row)
                    }
                }
            }

            Hairline()

            // Explicit "other apps" default — replaces the old "*" sentinel row.
            HStack(spacing: 12) {
                AppGlyph(label: "其", tint: .hex(0x5b626d), size: 32, corner: 9)
                VStack(alignment: .leading, spacing: 1) {
                    Text(L("settings.notif.otherApps"))
                        .font(.hyphenBody(13, weight: .semibold))
                        .foregroundColor(p.text)
                    Text(L("settings.notif.otherAppsDetail"))
                        .font(.hyphenBody(11))
                        .foregroundColor(p.faint)
                }
                Spacer(minLength: 0)
                SettingsPrivacySegmentedControl(mode: $defaultMode) { mode in
                    let updatedRows = rows.map { row -> SettingsAppPrivacyRow in
                        guard !row.isExplicit else { return row }
                        var inherited = row
                        inherited.mode = mode
                        return inherited
                    }
                    rows = updatedRows
                    onNotificationPrivacyChange(mirroringEnabled, mode, updatedRows)
                }
            }
        }
        .padding(.vertical, 20)
        .padding(.horizontal, 22)
    }

    private func appRow(_ row: Binding<SettingsAppPrivacyRow>) -> some View {
        HStack(spacing: 12) {
            AppGlyph(label: row.wrappedValue.glyph, tint: row.wrappedValue.tint, size: 32, corner: 9)
            VStack(alignment: .leading, spacing: 1) {
                Text(row.wrappedValue.name)
                    .font(.hyphenBody(13, weight: .semibold))
                    .foregroundColor(p.text)
                Text(row.wrappedValue.packageName)
                    .font(.hyphenMono(9))
                    .foregroundColor(p.faint)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
            SettingsPrivacySegmentedControl(mode: row.mode) { mode in
                var updatedRows = rows
                if let index = updatedRows.firstIndex(where: { $0.id == row.wrappedValue.id }) {
                    updatedRows[index].mode = mode
                    updatedRows[index].isExplicit = mode != defaultMode
                }
                rows = updatedRows
                onNotificationPrivacyChange(mirroringEnabled, defaultMode, updatedRows)
            }
        }
    }

    private var privacySyncLabel: String {
        switch privacySyncState {
        case .offline: return L("settings.notif.sync.offline")
        case .unsupported: return L("settings.notif.sync.unsupported")
        case .queued: return L("settings.notif.sync.queued")
        case .pending: return L("settings.notif.sync.pending")
        case .applied(let localFloor):
            return L("settings.notif.sync.applied", localFloor.settingsLabel)
        }
    }

    private var privacySyncColor: Color {
        switch privacySyncState {
        case .applied: return p.accent
        case .queued, .pending: return p.amber
        case .offline, .unsupported: return p.faint
        }
    }
}

// MARK: - 3-segment privacy control

/// 完整 / 隐藏内容 / 仅提示 segmented control. The selected 完整 swatch uses the
/// accent fill (accent-ink label); 隐藏内容 / 仅提示 selected swatches use the
/// --text fill on a --surface label, matching the design.
struct SettingsPrivacySegmentedControl: View {
    @Binding var mode: NotificationPrivacyMode
    var onChange: (NotificationPrivacyMode) -> Void = { _ in }
    @Environment(\.hyphenPalette) private var p

    var body: some View {
        HStack(spacing: 0) {
            ForEach(NotificationPrivacyMode.allCases, id: \.self) { option in
                segment(option)
            }
        }
        .padding(2)
        .background(p.surface2)
        .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(p.hair, lineWidth: 1))
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    private func segment(_ option: NotificationPrivacyMode) -> some View {
        let selected = option == mode
        let fill: Color = option == .full ? p.accent : p.text
        let labelColor: Color = option == .full ? p.accentInk : p.surface
        return Text(option.settingsLabel)
            .font(.hyphenBody(11, weight: .semibold))
            .foregroundColor(selected ? labelColor : p.dim)
            .padding(.vertical, 5)
            .padding(.horizontal, 10)
            .background(selected ? fill : Color.clear)
            .clipShape(RoundedRectangle(cornerRadius: 6))
            .contentShape(Rectangle())
            .onTapGesture {
                mode = option
                onChange(option)
            }
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
