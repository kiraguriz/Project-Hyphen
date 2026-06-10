import AppKit
import HyphenCore
import HyphenDiscovery
import HyphenPower

// Menu-bar-only skeleton (HYP-M1-010) + Bonjour advertise PoC toggle
// (HYP-M1-011) + sleep/wake observer (HYP-M1-013). Advertising starts only
// on explicit user action per the LNP rule from plan §8.3 (HYP-M1-012).

/// Placeholder until HYP-M1-014's reconnect state machine conforms.
final class LoggingReconnectTrigger: ReconnectTrigger {
    var onWake: (() -> Void)?

    func wakeOccurred() {
        NSLog("hyphen-power: wake -> reconnect attempt requested (state machine: HYP-M1-014)")
        onWake?()
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    private var statusItem: NSStatusItem?
    private var advertiser: BonjourAdvertiser?
    private var sleepWakeObserver: SleepWakeObserver?
    private let reconnectTrigger = LoggingReconnectTrigger()
    private let lnpGate = LocalNetworkOnboardingGate(store: UserDefaults.standard)
    private let advertiseItem = NSMenuItem(
        title: "Start advertising",
        action: #selector(toggleAdvertising(_:)),
        keyEquivalent: "a"
    )
    private let stateItem = NSMenuItem(title: "Not advertising", action: nil, keyEquivalent: "")

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

        menu.addItem(header)
        menu.addItem(.separator())
        menu.addItem(stateItem)
        menu.addItem(advertiseItem)
        menu.addItem(.separator())
        menu.addItem(
            NSMenuItem(
                title: "Quit Hyphen",
                action: #selector(NSApplication.terminate(_:)),
                keyEquivalent: "q"
            )
        )
        item.menu = menu
        statusItem = item

        // Sleep/wake logging + wake-triggered reconnect hook (HYP-M1-013).
        reconnectTrigger.onWake = { [weak self] in
            self?.stateItem.title = "Woke — reconnect pending (HYP-M1-014)"
        }
        let observer = SleepWakeObserver(reconnect: reconnectTrigger) { event in
            NSLog("hyphen-power: \(event)")
        }
        observer.start()
        sleepWakeObserver = observer
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
        let alert = NSAlert()
        alert.messageText = LocalNetworkCopy.title
        alert.informativeText = LocalNetworkCopy.body
        alert.alertStyle = .informational
        alert.addButton(withTitle: LocalNetworkCopy.continueTitle)
        alert.addButton(withTitle: LocalNetworkCopy.notNowTitle)
        return alert.runModal() == .alertFirstButtonReturn
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
}

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
// Accessory: menu-bar presence only, no Dock icon (plan §8.2 menu-bar app).
app.setActivationPolicy(.accessory)
app.run()
