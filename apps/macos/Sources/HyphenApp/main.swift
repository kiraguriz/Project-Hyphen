import AppKit
import HyphenCore
import HyphenDiscovery
import HyphenPower

// Menu-bar-only skeleton (HYP-M1-010) + Bonjour advertise PoC toggle
// (HYP-M1-011) + sleep/wake observer (HYP-M1-013). Advertising starts only
// on explicit user action per the LNP rule from plan §8.3 (HYP-M1-012).

final class AppDelegate: NSObject, NSApplicationDelegate {
    private var statusItem: NSStatusItem?
    private var advertiser: BonjourAdvertiser?
    private var sleepWakeObserver: SleepWakeObserver?
    private var reconnectMachine: ReconnectStateMachine?
    private var pairingController: PairingController?
    private let lnpGate = LocalNetworkOnboardingGate(store: UserDefaults.standard)
    private let pairItem = NSMenuItem(
        title: "Pair New Device…",
        action: #selector(beginPairing(_:)),
        keyEquivalent: "p"
    )
    private let sendTextItem = NSMenuItem(
        title: "Send Text/Link to Android…",
        action: #selector(sendTextLink(_:)),
        keyEquivalent: "t"
    )
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

        pairItem.target = self
        sendTextItem.target = self

        menu.addItem(header)
        menu.addItem(.separator())
        menu.addItem(stateItem)
        menu.addItem(advertiseItem)
        menu.addItem(pairItem)
        menu.addItem(sendTextItem)
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
                self.pairingController = PairingController { [weak self] status in
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
