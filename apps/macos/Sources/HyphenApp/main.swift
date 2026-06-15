import AppKit
import SwiftUI
import HyphenCore
import HyphenDiagnostics
import HyphenDiscovery
import HyphenPower
import UniformTypeIdentifiers

// Menu-bar-only skeleton (HYP-M1-010) + Bonjour advertise PoC toggle
// (HYP-M1-011) + sleep/wake observer (HYP-M1-013). Advertising starts only
// on explicit user action per the LNP rule from plan §8.3 (HYP-M1-012).

final class AppDelegate: NSObject, NSApplicationDelegate {
    private var statusItem: NSStatusItem?
    private var advertiser: BonjourAdvertiser?
    private var sleepWakeObserver: SleepWakeObserver?
    private var reconnectMachine: ReconnectStateMachine?
    private var pairingController: PairingController?
    private let diagnosticLogs = LocalStructuredLogStore()
    private let lnpGate = LocalNetworkOnboardingGate(store: UserDefaults.standard)
    private let pairItem = NSMenuItem(
        title: "Pair New Device…",
        action: #selector(beginPairing(_:)),
        keyEquivalent: "p"
    )
    private let managePeersItem = NSMenuItem(
        title: "Manage Paired Devices…",
        action: #selector(managePeers(_:)),
        keyEquivalent: ""
    )
    private let sendTextItem = NSMenuItem(
        title: "Send Text/Link to Android…",
        action: #selector(sendTextLink(_:)),
        keyEquivalent: "t"
    )
    private let sendFileItem = NSMenuItem(
        title: "Send File to Android…",
        action: #selector(sendFile(_:)),
        keyEquivalent: "f"
    )
    private let cancelTransferItem = NSMenuItem(
        title: "Cancel Active Transfer",
        action: #selector(cancelActiveTransfer(_:)),
        keyEquivalent: ""
    )
    private let advertiseItem = NSMenuItem(
        title: "Start advertising",
        action: #selector(toggleAdvertising(_:)),
        keyEquivalent: "a"
    )
    private let previewDiagnosticsItem = NSMenuItem(
        title: "Preview Diagnostics…",
        action: #selector(previewDiagnostics(_:)),
        keyEquivalent: "d"
    )
    private let exportDiagnosticsItem = NSMenuItem(
        title: "Export Diagnostics…",
        action: #selector(exportDiagnostics(_:)),
        keyEquivalent: "e"
    )
    private let deleteDiagnosticsItem = NSMenuItem(
        title: "Delete Diagnostics",
        action: #selector(deleteDiagnostics(_:)),
        keyEquivalent: ""
    )
    private let betaDiagnosticsItem = NSMenuItem(
        title: "Beta Diagnostics: Off",
        action: #selector(toggleBetaDiagnostics(_:)),
        keyEquivalent: ""
    )
    private let stateItem = NSMenuItem(title: "Not advertising", action: nil, keyEquivalent: "")

    // Section B · 变体 3 (timeline) menu-bar popover is the primary click target;
    // the legacy NSMenu is preserved on right-click so every backend action
    // stays reachable.
    private let popover = NSPopover()
    private var statusMenu: NSMenu?
    private var settingsWindow: DesignWindowController?
    // Outside-click dismissal for the popover. A global monitor only sees events
    // routed to *other* apps, so a click on the status button never reaches it —
    // which is exactly why this avoids the `.transient` race where the button
    // could not toggle the popover shut.
    private var popoverMonitor: Any?

    func applicationDidFinishLaunching(_ notification: Notification) {
        let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        item.button?.title = "⌁"
        item.button?.toolTip = "Hyphen \(HyphenCore.version) — pre-alpha"

        let menu = NSMenu()
        let header = NSMenuItem(
            title: "Hyphen \(HyphenCore.version) — pre-alpha skeleton",
            action: nil,
            keyEquivalent: ""
        )
        header.isEnabled = false
        stateItem.isEnabled = false
        advertiseItem.target = self

        pairItem.target = self
        managePeersItem.target = self
        sendTextItem.target = self
        sendFileItem.target = self
        cancelTransferItem.target = self
        previewDiagnosticsItem.target = self
        exportDiagnosticsItem.target = self
        deleteDiagnosticsItem.target = self
        betaDiagnosticsItem.target = self
        renderBetaDiagnosticsItem()

        menu.addItem(header)
        menu.addItem(.separator())
        menu.addItem(stateItem)
        menu.addItem(advertiseItem)
        menu.addItem(pairItem)
        menu.addItem(managePeersItem)
        menu.addItem(sendTextItem)
        menu.addItem(sendFileItem)
        menu.addItem(cancelTransferItem)
        menu.addItem(.separator())
        menu.addItem(betaDiagnosticsItem)
        menu.addItem(previewDiagnosticsItem)
        menu.addItem(exportDiagnosticsItem)
        menu.addItem(deleteDiagnosticsItem)
        menu.addItem(.separator())
        menu.addItem(
            NSMenuItem(
                title: "Quit Hyphen",
                action: #selector(NSApplication.terminate(_:)),
                keyEquivalent: "q"
            )
        )
        // Left-click opens the SwiftUI Variant-3 popover; right-click / control
        // -click opens the full legacy menu so nothing is lost.
        statusMenu = menu
        let popoverHost = NSHostingController(rootView: MenuBarPopoverView(
            onPair: { [weak self] in self?.popover.performClose(nil); self?.beginPairing(self!.pairItem) },
            onSettings: { [weak self] in self?.popover.performClose(nil); self?.openSettingsWindow() },
            onSend: { [weak self] in self?.popover.performClose(nil); self?.presentSendChooser() },
            onReply: { [weak self] _ in self?.stateItem.title = "回复需要活动会话（示例时间线）" },
            onDismiss: { [weak self] _ in self?.stateItem.title = "清除需要活动会话（示例时间线）" }
        ))
        popover.contentViewController = popoverHost
        popover.delegate = self
        item.button?.action = #selector(statusItemClicked(_:))
        item.button?.target = self
        item.button?.sendAction(on: [.leftMouseUp, .rightMouseUp])
        statusItem = item

        // Wake drives the reconnect state machine (HYP-M1-013/014);
        // the actual connect attempt arrives with the M2 transport.
        let machine = ReconnectStateMachine(
            scheduler: DispatchRetryScheduler(),
            startConnect: {
                NSLog("hyphen-power: connect attempt (transport lands in HYP-M2-007)")
            },
            onState: { [weak self] state in
                DispatchQueue.main.async {
                    self?.renderReconnect(state)
                }
            }
        )
        reconnectMachine = machine

        let observer = SleepWakeObserver(reconnect: machine) { event in
            NSLog("hyphen-power: \(event)")
            if event == .willSleep {
                machine.sleepOccurred()
            }
        }
        observer.start()
        sleepWakeObserver = observer
    }

    @objc private func statusItemClicked(_ sender: NSStatusBarButton) {
        let event = NSApp.currentEvent
        let isSecondary = event?.type == .rightMouseUp
            || event?.modifierFlags.contains(.control) == true
        if isSecondary, let menu = statusMenu {
            menu.popUp(positioning: nil, at: NSPoint(x: 0, y: sender.bounds.height + 4), in: sender)
            return
        }
        if popover.isShown {
            closePopover()
        } else {
            NSApp.activate(ignoringOtherApps: true)
            popover.show(relativeTo: sender.bounds, of: sender, preferredEdge: .minY)
            popoverMonitor = NSEvent.addGlobalMonitorForEvents(
                matching: [.leftMouseDown, .rightMouseDown]
            ) { [weak self] _ in
                self?.closePopover()
            }
        }
    }

    private func closePopover() {
        popover.performClose(nil)
    }

    private func openSettingsWindow() {
        if let settingsWindow {
            settingsWindow.present { [weak settingsWindow] in
                self.settingsView(onClose: { settingsWindow?.requestClose() })
            }
            return
        }
        let controller = DesignWindowController(onClosed: { [weak self] in
            self?.settingsWindow = nil
        })
        settingsWindow = controller
        controller.present { [weak controller] in
            self.settingsView(onClose: { controller?.requestClose() })
        }
    }

    private func settingsView(onClose: @escaping () -> Void) -> SettingsWindowView {
        let peer = (try? KeychainTrustStore().allPeers())?.first
        return SettingsWindowView(
            deviceName: peer.map { $0.displayName.isEmpty ? "已配对设备" : $0.displayName },
            fingerprint: peer.map { fingerprintPrefix($0.spkiFingerprint) },
            includeTraceIds: betaDiagnosticsEnabled(),
            onClose: onClose,
            onRevokeTrust: { [weak self] in self?.managePeers(self!.managePeersItem) },
            onRenameDevice: { [weak self] in self?.presentRenameUnavailable() },
            onExportDiagnostics: { [weak self] in self?.exportDiagnostics(self!.exportDiagnosticsItem) },
            onDeleteDiagnostics: { [weak self] in self?.deleteDiagnostics(self!.deleteDiagnosticsItem) },
            onRequestTraceIds: { [weak self] desired in self?.setBetaDiagnosticsWithConsent(desired) ?? false }
        )
    }

    private func presentRenameUnavailable() {
        let alert = NSAlert()
        alert.messageText = "重命名设备"
        alert.informativeText = "设备重命名将在后续版本提供。"
        alert.addButton(withTitle: "好")
        NSApp.activate(ignoringOtherApps: true)
        alert.runModal()
    }

    private func presentSendChooser() {
        let menu = NSMenu()
        menu.addItem(withTitle: "发送文件…", action: #selector(sendFile(_:)), keyEquivalent: "")
        menu.addItem(withTitle: "发送文本 / 链接…", action: #selector(sendTextLink(_:)), keyEquivalent: "")
        menu.items.forEach { $0.target = self }
        if let button = statusItem?.button {
            menu.popUp(positioning: nil, at: NSPoint(x: 0, y: button.bounds.height + 4), in: button)
        }
    }

    private func renderReconnect(_ state: ReconnectState) {
        switch state {
        case .waitingRetry(let attempt, let delay):
            stateItem.title = "Reconnect in \(Int(delay))s (attempt \(attempt + 1))"
        case .connecting:
            stateItem.title = "Connecting… (no transport until M2)"
        case .connected, .idle, .sleeping, .suspended:
            break // advertising state owns the line otherwise
        }
    }

    @objc private func beginPairing(_ sender: NSMenuItem) {
        // Same explain-first gate as advertising: the pairing listener is
        // a network-touching action (HYP-M1-012 rule).
        lnpGate.run(action: { [weak self] in
            guard let self else { return }
            if self.pairingController == nil {
                self.pairingController = PairingController(diagnosticLogs: self.diagnosticLogs) { [weak self] status in
                    self?.stateItem.title = status
                }
            }
            self.pairingController?.beginPairing()
        }) { complete in
            complete(Self.presentLocalNetworkExplanation())
        }
    }

    @objc private func sendTextLink(_ sender: NSMenuItem) {
        guard let pairingController else {
            stateItem.title = "text/link: no active Android session"
            return
        }
        let input = NSTextField(frame: NSRect(x: 0, y: 0, width: 360, height: 24))
        input.placeholderString = "Text or https:// link"

        let alert = NSAlert()
        alert.messageText = "Send to Android"
        alert.informativeText = "Text will be copied on Android after confirmation; links open after confirmation."
        alert.alertStyle = .informational
        alert.accessoryView = input
        alert.addButton(withTitle: "Send")
        alert.addButton(withTitle: "Cancel")
        NSApp.activate(ignoringOtherApps: true)

        guard alert.runModal() == .alertFirstButtonReturn else {
            stateItem.title = "text/link send cancelled"
            return
        }
        pairingController.sendTextLink(raw: input.stringValue)
    }

    @objc private func sendFile(_ sender: NSMenuItem) {
        guard let pairingController else {
            stateItem.title = "transfer/send: no active Android session"
            return
        }
        let panel = NSOpenPanel()
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = false
        panel.canCreateDirectories = false
        NSApp.activate(ignoringOtherApps: true)

        guard panel.runModal() == .OK, let url = panel.url else {
            stateItem.title = "transfer/send cancelled"
            return
        }
        pairingController.sendFile(url: url)
    }

    @objc private func managePeers(_ sender: NSMenuItem) {
        do {
            let store = KeychainTrustStore()
            let peers = try store.allPeers().sorted { lhs, rhs in
                lhs.displayName.localizedCaseInsensitiveCompare(rhs.displayName) == .orderedAscending
            }
            if peers.isEmpty {
                let alert = NSAlert()
                alert.messageText = "Paired devices"
                alert.informativeText = "No trusted peers are stored on this Mac."
                alert.alertStyle = .informational
                alert.addButton(withTitle: "OK")
                NSApp.activate(ignoringOtherApps: true)
                alert.runModal()
                stateItem.title = "No paired devices"
                return
            }

            let picker = NSPopUpButton(frame: NSRect(x: 0, y: 0, width: 360, height: 26), pullsDown: false)
            peers.forEach { picker.addItem(withTitle: peerLabel($0)) }

            let alert = NSAlert()
            alert.messageText = "Paired devices"
            alert.informativeText = "Forgetting a peer removes its pinned fingerprint and stops any active session. Pair again to reconnect."
            alert.alertStyle = .warning
            alert.accessoryView = picker
            alert.addButton(withTitle: "Forget Selected")
            alert.addButton(withTitle: "Reset All")
            alert.addButton(withTitle: "Close")
            NSApp.activate(ignoringOtherApps: true)

            switch alert.runModal() {
            case .alertFirstButtonReturn:
                try forgetPeer(peers[picker.indexOfSelectedItem], store: store)
            case .alertSecondButtonReturn:
                confirmResetPeers(count: peers.count, store: store)
            default:
                stateItem.title = "Paired devices unchanged"
            }
        } catch {
            stateItem.title = "Peer management failed: \(error)"
        }
    }

    private func forgetPeer(_ peer: TrustedPeer, store: KeychainTrustStore) throws {
        let removed = try store.remove(fingerprint: peer.spkiFingerprint)
        pairingController?.endPairing()
        stateItem.title = "Forgot \(peer.displayName.isEmpty ? "peer" : peer.displayName) (removed=\(removed))"
    }

    private func confirmResetPeers(count: Int, store: KeychainTrustStore) {
        let alert = NSAlert()
        alert.messageText = "Reset paired devices?"
        alert.informativeText = "This removes \(count) trusted peer(s) and stops any active session. Pair again before any device can reconnect."
        alert.alertStyle = .critical
        alert.addButton(withTitle: "Reset")
        alert.addButton(withTitle: "Cancel")
        NSApp.activate(ignoringOtherApps: true)

        guard alert.runModal() == .alertFirstButtonReturn else {
            stateItem.title = "Paired devices unchanged"
            return
        }
        do {
            try store.removeAll()
            pairingController?.endPairing()
            stateItem.title = "Paired devices reset (\(count) removed)"
        } catch {
            stateItem.title = "Peer reset failed: \(error)"
        }
    }

    private func peerLabel(_ peer: TrustedPeer) -> String {
        let name = peer.displayName.isEmpty ? "Unnamed peer" : peer.displayName
        return "\(name) (\(fingerprintPrefix(peer.spkiFingerprint)))"
    }

    private func fingerprintPrefix(_ fingerprint: Data) -> String {
        fingerprint.prefix(6).map { String(format: "%02x", $0) }.joined()
    }

    @objc private func cancelActiveTransfer(_ sender: NSMenuItem) {
        guard let pairingController else {
            stateItem.title = "transfer cancel: no active Android session"
            return
        }
        pairingController.cancelActiveTransfer()
    }

    @objc private func previewDiagnostics(_ sender: NSMenuItem) {
        do {
            let json = try diagnosticsExporter().previewJSON()
            let textView = NSTextView(frame: NSRect(x: 0, y: 0, width: 520, height: 260))
            textView.isEditable = false
            textView.font = .monospacedSystemFont(ofSize: 11, weight: .regular)
            textView.string = json

            let scroll = NSScrollView(frame: NSRect(x: 0, y: 0, width: 520, height: 260))
            scroll.hasVerticalScroller = true
            scroll.documentView = textView

            let alert = NSAlert()
            alert.messageText = "Diagnostics preview"
            alert.informativeText = diagnosticsPreviewExplanation()
            alert.alertStyle = .informational
            alert.accessoryView = scroll
            alert.addButton(withTitle: "OK")
            NSApp.activate(ignoringOtherApps: true)
            alert.runModal()
            stateItem.title = "Diagnostics previewed (\(diagnosticLogs.snapshot().count) event(s), beta \(betaDiagnosticsStatus()))"
        } catch {
            stateItem.title = "Diagnostics preview failed: \(error)"
        }
    }

    @objc private func exportDiagnostics(_ sender: NSMenuItem) {
        do {
            let json = try diagnosticsExporter().exportText()
            let panel = NSSavePanel()
            panel.nameFieldStringValue = "hyphen-diagnostics.json"
            panel.allowedContentTypes = [.json]
            panel.canCreateDirectories = true
            NSApp.activate(ignoringOtherApps: true)
            guard panel.runModal() == .OK, let url = panel.url else {
                stateItem.title = "Diagnostics export cancelled"
                return
            }
            try json.write(to: url, atomically: true, encoding: .utf8)
            stateItem.title = "Diagnostics exported"
        } catch {
            stateItem.title = "Diagnostics export failed: \(error)"
        }
    }

    @objc private func deleteDiagnostics(_ sender: NSMenuItem) {
        let count = diagnosticLogs.snapshot().count
        diagnosticsExporter().deleteLocalDiagnostics()
        stateItem.title = "Diagnostics deleted (\(count) event(s))"
    }

    @objc private func toggleBetaDiagnostics(_ sender: NSMenuItem) {
        setBetaDiagnosticsWithConsent(!betaDiagnosticsEnabled())
    }

    /// Applies a beta-diagnostics opt-in change behind the privacy consent gate:
    /// enabling requires confirming the explainer; disabling is immediate.
    /// Returns the state actually in effect afterwards so a caller (e.g. the
    /// settings toggle) can revert its UI if consent was declined.
    @discardableResult
    private func setBetaDiagnosticsWithConsent(_ desired: Bool) -> Bool {
        guard desired != betaDiagnosticsEnabled() else { return desired }

        if !desired {
            setBetaDiagnosticsEnabled(false)
            stateItem.title = "Beta diagnostics disabled"
            return false
        }

        let alert = NSAlert()
        alert.messageText = "Enable beta diagnostics?"
        alert.informativeText = """
        Beta diagnostics are off by default.

        Enable only when debugging a beta issue. Local previews and exports may include trace IDs for failure correlation. Notification bodies, file names, URLs, and IP suffixes stay redacted.

        Hyphen never uploads diagnostics automatically. Export stays user-triggered, and this menu item disables beta extras immediately.
        """
        alert.alertStyle = .informational
        alert.addButton(withTitle: "Enable")
        alert.addButton(withTitle: "Cancel")
        NSApp.activate(ignoringOtherApps: true)

        guard alert.runModal() == .alertFirstButtonReturn else {
            stateItem.title = "Beta diagnostics left off"
            return false
        }
        setBetaDiagnosticsEnabled(true)
        stateItem.title = "Beta diagnostics enabled"
        return true
    }

    private func setBetaDiagnosticsEnabled(_ enabled: Bool) {
        UserDefaults.standard.set(enabled, forKey: Self.betaDiagnosticsOptInKey)
        renderBetaDiagnosticsItem()
    }

    private func betaDiagnosticsEnabled() -> Bool {
        UserDefaults.standard.bool(forKey: Self.betaDiagnosticsOptInKey)
    }

    private func renderBetaDiagnosticsItem() {
        let enabled = betaDiagnosticsEnabled()
        betaDiagnosticsItem.title = "Beta Diagnostics: \(enabled ? "On" : "Off")"
        betaDiagnosticsItem.state = enabled ? .on : .off
    }

    private func betaDiagnosticsStatus() -> String {
        betaDiagnosticsEnabled() ? "on" : "off"
    }

    private func diagnosticsPreviewExplanation() -> String {
        if betaDiagnosticsEnabled() {
            return "Local, redacted bundle. Beta diagnostics are on, so local trace IDs are included when present. No automatic upload."
        }
        return "Local, redacted bundle. Beta diagnostics are off, so trace IDs stay hidden. No automatic upload."
    }

    private func diagnosticsExporter() -> RedactedDiagnosticsExporter {
        let os = ProcessInfo.processInfo.operatingSystemVersion
        return RedactedDiagnosticsExporter(
            logs: diagnosticLogs,
            appVersion: HyphenCore.version,
            osMajor: os.majorVersion,
            osMinor: os.minorVersion,
            osPatch: os.patchVersion,
            includeTraceIds: betaDiagnosticsEnabled()
        )
    }

    @objc private func toggleAdvertising(_ sender: NSMenuItem) {
        if advertiser == nil {
            // Explain-first gate (HYP-M1-012): the network action — and
            // therefore the macOS Local Network prompt — runs only after
            // the user reads the explanation and continues.
            lnpGate.run(action: { [weak self] in self?.startAdvertising() }) { complete in
                complete(Self.presentLocalNetworkExplanation())
            }
        } else {
            advertiser?.stop()
            advertiser = nil
            advertiseItem.title = "Start advertising"
        }
    }

    private func startAdvertising() {
        let deviceName = Host.current().localizedName ?? "Mac"
        let a = BonjourAdvertiser { [weak self] state in
            DispatchQueue.main.async { self?.render(state) }
        }
        advertiser = a
        a.start(deviceName: deviceName)
        advertiseItem.title = "Stop advertising"
    }

    private static func presentLocalNetworkExplanation() -> Bool {
        // Section C · 本地网络授权 — the SwiftUI explain-first dialog (frozen
        // copy lives in PairingLocalNetworkDialogView) replaces the old NSAlert.
        LocalNetworkDialog.runModal()
    }

    private func render(_ state: BonjourAdvertiser.State) {
        switch state {
        case .idle:
            stateItem.title = "Not advertising"
        case .starting:
            stateItem.title = "Starting…"
        case .advertising(let port):
            stateItem.title = "Advertising \(HyphenCore.bonjourServiceType) on port \(port)"
        case .failed(let reason):
            stateItem.title = "Failed: \(reason)"
        case .stopped:
            stateItem.title = "Not advertising"
        }
    }

    private static let betaDiagnosticsOptInKey = "dev.hyphen.betaDiagnostics.includeTraceIds"
}

extension AppDelegate: NSPopoverDelegate {
    /// Tears down the outside-click monitor whenever the popover closes — via the
    /// toggle, the monitor itself, or a popover action that calls `performClose`.
    func popoverDidClose(_ notification: Notification) {
        if let popoverMonitor {
            NSEvent.removeMonitor(popoverMonitor)
            self.popoverMonitor = nil
        }
    }
}

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
// Accessory: menu-bar presence only, no Dock icon (plan §8.2 menu-bar app).
app.setActivationPolicy(.accessory)
app.run()
