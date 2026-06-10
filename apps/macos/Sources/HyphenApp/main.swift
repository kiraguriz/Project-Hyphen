import AppKit
import HyphenCore
import HyphenDiscovery

// Menu-bar-only skeleton (HYP-M1-010) + Bonjour advertise PoC toggle
// (HYP-M1-011). Advertising starts only on explicit user action, which is
// the Local Network Privacy rule from plan §8.3 (formalized in M1-012).

final class AppDelegate: NSObject, NSApplicationDelegate {
    private var statusItem: NSStatusItem?
    private var advertiser: BonjourAdvertiser?
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
    }

    @objc private func toggleAdvertising(_ sender: NSMenuItem) {
        if advertiser == nil {
            let deviceName = Host.current().localizedName ?? "Mac"
            let a = BonjourAdvertiser { [weak self] state in
                DispatchQueue.main.async { self?.render(state) }
            }
            advertiser = a
            a.start(deviceName: deviceName)
            advertiseItem.title = "Stop advertising"
        } else {
            advertiser?.stop()
            advertiser = nil
            advertiseItem.title = "Start advertising"
        }
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
