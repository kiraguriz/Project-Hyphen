import AppKit
import SwiftUI
import HyphenCore
import HyphenDiagnostics
import HyphenDiscovery
import HyphenNotifications
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
    // App-level observable model (frontend UX plan M-A1): the single source of
    // truth for the popover's connection + activity rendering. The
    // PairingController feeds it ActivityEvents; AppDelegate feeds it the
    // pairing-identity changes it owns (launch / pair / forget / reset).
    private let appModel = HyphenAppModel()
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
    // The settings pane is hoisted here so a window refresh (trace-ID consent,
    // trust revoke/reset) preserves the user's current pane instead of resetting
    // to the default. Updated via SettingsWindowView.onSelectionChange.
    private var settingsSelection: SettingsNavSection = .notifications
    // Cached notification-privacy policy. Built at launch and after each settings
    // edit; the notification hot path reads this snapshot (off the main thread)
    // instead of recomputing from UserDefaults per mirrored notification.
    private let privacyPolicyLock = NSLock()
    private var cachedNotificationPrivacyPolicy = NotificationPrivacyPolicy.allowAll
    // Outside-click dismissal for the popover. A global monitor only sees events
    // routed to *other* apps, so a click on the status button never reaches it —
    // which is exactly why this avoids the `.transient` race where the button
    // could not toggle the popover shut.
    private var popoverMonitor: Any?

    func applicationDidFinishLaunching(_ notification: Notification) {
        migrateNotificationPrivacyIfNeeded()
        rebuildNotificationPrivacyCache()
        refreshPairingState()

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
        let popoverHost = NSHostingController(rootView: makeMenuBarPopoverView())
        popover.contentViewController = popoverHost
        popover.delegate = self
        item.button?.action = #selector(statusItemClicked(_:))
        item.button?.target = self
        item.button?.sendAction(on: [.leftMouseUp, .rightMouseUp])
        statusItem = item

        // Wake drives the reconnect state machine (HYP-M1-013/014) and the
        // steady-session listener that accepts trusted-phone redials.
        let machine = ReconnectStateMachine(
            scheduler: DispatchRetryScheduler(),
            startConnect: { [weak self] in
                self?.pairingController?.requestReconnect()
            },
            onState: { [weak self] state in
                DispatchQueue.main.async {
                    self?.renderReconnect(state)
                }
            }
        )
        reconnectMachine = machine

        let observer = SleepWakeObserver(reconnect: machine) { [weak self] event in
            NSLog("hyphen-power: \(event)")
            if event == .willSleep {
                machine.sleepOccurred()
            }
            // Surface the real power signal in the popover (only when paired —
            // an unpaired Mac stays in the not-paired presentation). Wake returns
            // to the paired-idle state; a live session re-asserts `.connected`
            // via PairingController.
            DispatchQueue.main.async {
                guard let self, self.appModel.connection.isPaired else { return }
                if self.pairingController?.hasActiveSession == true { return }
                switch event {
                case .willSleep:
                    self.appModel.apply(.connectionStateChanged(.sleeping, latencyMs: nil))
                case .didWake:
                    self.appModel.apply(.connectionStateChanged(.suspended, latencyMs: nil))
                }
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
            closePopover()
            menu.popUp(positioning: nil, at: NSPoint(x: 0, y: sender.bounds.height + 4), in: sender)
            return
        }
        if popover.isShown {
            closePopover()
        } else {
            NSApp.activate(ignoringOtherApps: true)
            popover.contentViewController = NSHostingController(rootView: makeMenuBarPopoverView())
            popover.show(relativeTo: sender.bounds, of: sender, preferredEdge: .minY)
            // Remove any stale monitor before installing a new one. A re-open
            // before `popoverDidClose` fires would otherwise overwrite the single
            // `popoverMonitor` slot and leak the previous global monitor.
            installPopoverMonitor()
        }
    }

    private func installPopoverMonitor() {
        if let popoverMonitor {
            NSEvent.removeMonitor(popoverMonitor)
            self.popoverMonitor = nil
        }
        popoverMonitor = NSEvent.addGlobalMonitorForEvents(
            matching: [.leftMouseDown, .rightMouseDown]
        ) { [weak self] _ in
            self?.closePopover()
        }
    }

    private func closePopover() {
        popover.performClose(nil)
    }

    private func makeMenuBarPopoverView() -> MenuBarPopoverHost {
        // The host observes `appModel`, so the popover re-renders live while it
        // is open (no more `days: []`). Connection + activity are the model's,
        // not derived inline from a synchronous Keychain read.
        MenuBarPopoverHost(
            model: appModel,
            onPair: { [weak self] in
                guard let self else { return }
                self.closePopover()
                self.beginPairing(self.pairItem)
            },
            onSettings: { [weak self] in
                self?.closePopover()
                self?.openSettingsWindow()
            },
            onSendText: { [weak self] text in
                self?.sendComposedText(text)
            },
            onSendFile: { [weak self] in
                self?.closePopover()
                self?.sendFile(self?.sendFileItem ?? NSMenuItem())
            },
            onReply: { [weak self] ref in
                self?.closePopover()
                self?.presentNotificationReplyComposer(ref)
            },
            onDismiss: { [weak self] ref in
                self?.requestNotificationDismiss(ref)
            }
        )
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

    private func refreshSettingsWindow() {
        guard let settingsWindow else { return }
        settingsWindow.present { [weak settingsWindow] in
            self.settingsView(onClose: { settingsWindow?.requestClose() })
        }
    }

    private func settingsView(onClose: @escaping () -> Void) -> SettingsWindowView {
        let peer = currentTrustedPeer()
        return SettingsWindowView(
            selection: settingsSelection,
            mirroringEnabled: notificationMirroringEnabled(),
            rows: notificationPrivacyRows(),
            defaultMode: notificationDefaultMode(),
            deviceName: peer.map { $0.displayName.isEmpty ? L("conn.pairedFallback") : $0.displayName },
            fingerprint: peer.map { HyphenFingerprintDisplay.string(for: $0.spkiFingerprint, style: .shortPrefix) },
            includeTraceIds: betaDiagnosticsEnabled(),
            onClose: onClose,
            onRevokeTrust: { [weak self] in
                guard let self else { return }
                self.managePeers(self.managePeersItem)
            },
            onRenameDevice: { [weak self] in self?.presentRenameUnavailable() },
            onExportDiagnostics: { [weak self] in
                guard let self else { return }
                self.exportDiagnostics(self.exportDiagnosticsItem)
            },
            onDeleteDiagnostics: { [weak self] in
                guard let self else { return }
                self.deleteDiagnostics(self.deleteDiagnosticsItem)
            },
            onRequestTraceIds: { [weak self] desired in self?.setBetaDiagnosticsWithConsent(desired) ?? false },
            onNotificationPrivacyChange: { [weak self] enabled, defaultMode, rows in
                self?.setNotificationPrivacy(mirroringEnabled: enabled, defaultMode: defaultMode, rows: rows)
            },
            onSelectionChange: { [weak self] section in self?.settingsSelection = section }
        )
    }

    // Cached trusted peer. The popover no longer reads the Keychain on its hot
    // path (it renders from `appModel`), but the settings view still resolves the
    // peer and can rebuild several times per open (trace-ID consent, trust
    // edits). Cache the read and invalidate on trust mutations.
    private var trustedPeerCacheValid = false
    private var cachedTrustedPeer: TrustedPeer?

    private func currentTrustedPeer() -> TrustedPeer? {
        if trustedPeerCacheValid { return cachedTrustedPeer }
        let peer = (try? KeychainTrustStore().allPeers())?.first
        cachedTrustedPeer = peer
        trustedPeerCacheValid = true
        return peer
    }

    private func invalidateTrustedPeerCache() {
        trustedPeerCacheValid = false
        cachedTrustedPeer = nil
    }

    /// Push the current pairing identity into the app model. Called at launch
    /// and after pair / forget / reset so the popover's not-paired vs paired
    /// presentation tracks the trust store. Live connection state (connected /
    /// suspended) is driven separately by `PairingController` events.
    private func refreshPairingState() {
        let peer = currentTrustedPeer()
        let name = peer.map { $0.displayName.isEmpty ? L("conn.pairedFallback") : $0.displayName }
        appModel.apply(.peerChanged(isPaired: peer != nil, peerName: name))
    }

    private func notificationMirroringEnabled() -> Bool {
        guard UserDefaults.standard.object(forKey: Self.notificationMirroringEnabledKey) != nil else {
            return true
        }
        return UserDefaults.standard.bool(forKey: Self.notificationMirroringEnabledKey)
    }

    private func notificationDefaultMode() -> NotificationPrivacyMode {
        guard let raw = UserDefaults.standard.string(forKey: Self.notificationDefaultModeKey),
              let mode = NotificationPrivacyMode(rawValue: raw) else {
            return SettingsAppPrivacyRow.defaultModeForOtherApps
        }
        return mode
    }

    private func storedPerPackageModes() -> [String: NotificationPrivacyMode] {
        let raw = UserDefaults.standard.dictionary(forKey: Self.notificationPerPackageModesKey) as? [String: String] ?? [:]
        return raw.reduce(into: [:]) { result, entry in
            if let mode = NotificationPrivacyMode(rawValue: entry.value) {
                result[entry.key] = mode
            }
        }
    }

    private func notificationPrivacyRows() -> [SettingsAppPrivacyRow] {
        let storedModes = storedPerPackageModes()
        return SettingsAppPrivacyRow.defaults.map { row in
            var row = row
            if let mode = storedModes[row.packageName] {
                row.mode = mode
            }
            return row
        }
    }

    private func setNotificationPrivacy(
        mirroringEnabled: Bool,
        defaultMode: NotificationPrivacyMode,
        rows: [SettingsAppPrivacyRow]
    ) {
        UserDefaults.standard.set(mirroringEnabled, forKey: Self.notificationMirroringEnabledKey)
        UserDefaults.standard.set(defaultMode.rawValue, forKey: Self.notificationDefaultModeKey)
        let modes = Dictionary(uniqueKeysWithValues: rows.map { ($0.packageName, $0.mode.rawValue) })
        UserDefaults.standard.set(modes, forKey: Self.notificationPerPackageModesKey)
        rebuildNotificationPrivacyCache()
        // Push the edited policy to a paired Android peer for source-side
        // filtering (no-op unless a session is active and privacyPolicy was
        // negotiated). The macOS receiver scrubber already uses the new cache.
        pairingController?.syncNotificationPrivacyPolicy()
    }

    /// One-time migration from the unreleased single-dictionary format (which
    /// stored the default under a "*" key) to explicit default + per-package
    /// keys. Reads only when the new keys are absent and the old key is present.
    private func migrateNotificationPrivacyIfNeeded() {
        let defaults = UserDefaults.standard
        guard defaults.object(forKey: Self.notificationDefaultModeKey) == nil,
              defaults.object(forKey: Self.notificationPerPackageModesKey) == nil,
              var legacy = defaults.dictionary(forKey: Self.legacyNotificationPrivacyModesKey) as? [String: String]
        else { return }
        let defaultRaw = legacy.removeValue(forKey: "*") ?? SettingsAppPrivacyRow.defaultModeForOtherApps.rawValue
        defaults.set(defaultRaw, forKey: Self.notificationDefaultModeKey)
        defaults.set(legacy, forKey: Self.notificationPerPackageModesKey)
        defaults.removeObject(forKey: Self.legacyNotificationPrivacyModesKey)
    }

    private func computeNotificationPrivacyPolicy() -> NotificationPrivacyPolicy {
        NotificationPrivacyPolicy(
            isMirroringEnabled: notificationMirroringEnabled(),
            defaultMode: notificationDefaultMode(),
            perPackageModes: storedPerPackageModes()
        )
    }

    /// Rebuild the cached policy. Called once at launch and after every settings
    /// edit so the notification hot path reads a snapshot instead of recomputing
    /// from `UserDefaults` (off the main thread) on every mirrored notification.
    private func rebuildNotificationPrivacyCache() {
        let policy = computeNotificationPrivacyPolicy()
        privacyPolicyLock.lock()
        cachedNotificationPrivacyPolicy = policy
        privacyPolicyLock.unlock()
    }

    private func notificationPrivacyPolicy() -> NotificationPrivacyPolicy {
        privacyPolicyLock.lock()
        defer { privacyPolicyLock.unlock() }
        return cachedNotificationPrivacyPolicy
    }

    private func presentRenameUnavailable() {
        let alert = NSAlert()
        alert.messageText = L("rename.title")
        alert.informativeText = L("rename.body")
        alert.addButton(withTitle: L("common.ok"))
        NSApp.activate(ignoringOtherApps: true)
        alert.runModal()
    }

    /// Send text/link composed in the popover (M-A4). The capability gate +
    /// URL-vs-text classification live in `PairingController.sendTextLink`.
    private func sendComposedText(_ text: String) {
        guard let pairingController, pairingController.hasActiveSession else {
            stateItem.title = L("menu.sendNeedsSession")
            return
        }
        pairingController.sendTextLink(raw: text)
    }

    /// Inline-style reply composer for a mirrored notification (M-A3). Uses the
    /// proven NSTextField-in-NSAlert pattern (same as `sendTextLink`); the
    /// capability gate + send live in `PairingController`.
    private func presentNotificationReplyComposer(_ ref: MenuBarNotificationRef) {
        guard let pairingController, pairingController.hasActiveSession else {
            stateItem.title = L("menu.replyNeedsSession")
            return
        }
        guard let actionIndex = ref.replyActionIndex else {
            stateItem.title = L("menu.replyUnsupported")
            return
        }
        let input = NSTextField(frame: NSRect(x: 0, y: 0, width: 320, height: 24))
        input.placeholderString = L("reply.placeholder", ref.sender)

        let alert = NSAlert()
        alert.messageText = L("reply.title", ref.appName)
        alert.informativeText = L("reply.body")
        alert.alertStyle = .informational
        alert.accessoryView = input
        alert.addButton(withTitle: L("reply.send"))
        alert.addButton(withTitle: L("common.cancel"))
        NSApp.activate(ignoringOtherApps: true)

        guard alert.runModal() == .alertFirstButtonReturn else {
            stateItem.title = L("reply.cancelled")
            return
        }
        pairingController.requestNotificationReply(
            sbnKey: ref.sbnKey,
            actionIndex: actionIndex,
            actionId: ref.replyActionId,
            text: input.stringValue
        )
    }

    private func requestNotificationDismiss(_ ref: MenuBarNotificationRef) {
        guard let pairingController, pairingController.hasActiveSession else {
            stateItem.title = L("menu.dismissNeedsSession")
            return
        }
        pairingController.requestNotificationDismiss(sbnKey: ref.sbnKey)
    }

    private func renderReconnect(_ state: ReconnectState) {
        switch state {
        case .waitingRetry(let attempt, let delay):
            stateItem.title = "Reconnect in \(Int(delay))s (attempt \(attempt + 1))"
        case .connecting:
            stateItem.title = "Connecting…"
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
                self.pairingController = PairingController(
                    diagnosticLogs: self.diagnosticLogs,
                    notificationPrivacyPolicy: { [weak self] in
                        self?.notificationPrivacyPolicy() ?? .allowAll
                    },
                    onActivity: { [weak self] event in
                        // A confirmed pairing writes a new trusted peer just
                        // before the session connects; drop the cache on either
                        // the peer-identity change or the connect so the settings
                        // view always resolves the fresh peer.
                        switch event {
                        case .peerChanged, .connectionStateChanged(.connected, _):
                            self?.invalidateTrustedPeerCache()
                        default:
                            break
                        }
                        self?.appModel.apply(event)
                    }
                ) { [weak self] status in
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
        pairingController?.stopAfterTrustChange()
        pairingController?.endPairing()
        stateItem.title = "Forgot \(peer.displayName.isEmpty ? "peer" : peer.displayName) (removed=\(removed))"
        invalidateTrustedPeerCache()
        refreshSettingsWindow()
        refreshPairingState()
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
            pairingController?.stopAfterTrustChange()
            pairingController?.endPairing()
            stateItem.title = "Paired devices reset (\(count) removed)"
            invalidateTrustedPeerCache()
            refreshSettingsWindow()
            refreshPairingState()
        } catch {
            stateItem.title = "Peer reset failed: \(error)"
        }
    }

    private func peerLabel(_ peer: TrustedPeer) -> String {
        let name = peer.displayName.isEmpty ? "Unnamed peer" : peer.displayName
        return "\(name) (\(HyphenFingerprintDisplay.string(for: peer.spkiFingerprint, style: .shortPrefix)))"
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
        refreshSettingsWindow()
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
    private static let notificationMirroringEnabledKey = "dev.hyphen.notifications.mirroringEnabled"
    private static let notificationDefaultModeKey = "dev.hyphen.notifications.defaultPrivacyMode"
    private static let notificationPerPackageModesKey = "dev.hyphen.notifications.perPackagePrivacyModes"
    private static let legacyNotificationPrivacyModesKey = "dev.hyphen.notifications.privacyModes"
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
