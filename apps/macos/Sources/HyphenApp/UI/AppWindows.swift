import AppKit
import SwiftUI

// Glue that hosts the SwiftUI design surfaces inside the AppKit menu-bar app:
// borderless transparent windows that show a single SwiftUI card (the card
// draws its own WindowChrome + shadow), an Escape-to-close monitor, and the
// observable model that drives the live pairing window.

/// Borderless window that can still become key so its SwiftUI buttons work.
final class BorderlessKeyWindow: NSWindow {
    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { true }
}

/// Presents one SwiftUI view in a borderless, content-sized window. Reused for
/// the settings window and the pairing window.
final class DesignWindowController {
    private var window: NSWindow?
    private var escMonitor: Any?
    private let onClosed: (() -> Void)?

    init(onClosed: (() -> Void)? = nil) {
        self.onClosed = onClosed
    }

    var isVisible: Bool { window?.isVisible ?? false }

    func present<Content: View>(@ViewBuilder _ content: () -> Content) {
        if let window {
            NSApp.activate(ignoringOtherApps: true)
            window.makeKeyAndOrderFront(nil)
            return
        }
        let hosting = NSHostingController(rootView: content().fixedSize())
        let w = BorderlessKeyWindow(contentViewController: hosting)
        w.styleMask = [.borderless, .fullSizeContentView]
        w.isOpaque = false
        w.backgroundColor = .clear
        w.hasShadow = false // the SwiftUI card draws its own shadow
        w.isMovableByWindowBackground = true
        w.isReleasedWhenClosed = false
        w.center()
        window = w

        // Escape closes the borderless window (no title bar to host Cmd-W).
        // `close()` is pure teardown; the owner is notified via `onClosed`
        // here so a programmatic `close()` cannot re-enter through it.
        escMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
            guard let self, self.window != nil else { return event }
            if event.keyCode == 53 { // Escape
                self.requestClose()
                return nil
            }
            return event
        }

        NSApp.activate(ignoringOtherApps: true)
        w.makeKeyAndOrderFront(nil)
    }

    /// Closes the window and notifies the owner via `onClosed` — the same path
    /// the Escape key and the window-chrome close button take. Use this for any
    /// user-initiated dismissal; `close()` alone is pure teardown.
    func requestClose() {
        close()
        onClosed?()
    }

    /// Pure teardown — safe to call repeatedly; never fires `onClosed`.
    func close() {
        if let escMonitor {
            NSEvent.removeMonitor(escMonitor)
            self.escMonitor = nil
        }
        window?.orderOut(nil)
        window = nil
    }
}

/// Runs the Local Network explain-first dialog (Section C) as a modal and
/// returns the user's choice — a drop-in for the old NSAlert gate.
enum LocalNetworkDialog {
    static func runModal() -> Bool {
        var didContinue = false
        let hosting = NSHostingController(
            rootView: PairingLocalNetworkDialogView(
                onContinue: { didContinue = true; NSApp.stopModal() },
                onNotNow: { didContinue = false; NSApp.stopModal() }
            ).fixedSize()
        )
        let w = BorderlessKeyWindow(contentViewController: hosting)
        w.styleMask = [.borderless, .fullSizeContentView]
        w.isOpaque = false
        w.backgroundColor = .clear
        w.hasShadow = false
        w.isMovableByWindowBackground = true
        w.isReleasedWhenClosed = false
        w.center()
        NSApp.activate(ignoringOtherApps: true)
        NSApp.runModal(for: w)
        w.orderOut(nil)
        return didContinue
    }
}

/// Drives the live pairing window (Section C). The PairingController updates
/// these published values as the QR/SAS flow progresses; `PairingWindowHost`
/// re-renders the design view in response.
final class PairingWindowModel: ObservableObject {
    @Published var sasCodes: [String] = ["··", "··", "··"]
    @Published var address: String = ""
    @Published var peerName: String = "等待手机连接…"
    @Published var fingerprint: String = "等待手机连接…"
    @Published var qrPayload: String = ""
    @Published var awaitingConfirmation = false

    var onConfirm: () -> Void = {}
    var onReject: () -> Void = {}
}

/// Renders the design `PairingWindowView` against the live model.
struct PairingWindowHost: View {
    @ObservedObject var model: PairingWindowModel
    var onClose: () -> Void = {}

    var body: some View {
        PairingWindowView(
            sasCodes: model.sasCodes,
            address: model.address,
            peerName: model.peerName,
            fingerprint: model.fingerprint,
            qrPayload: model.qrPayload,
            onClose: onClose,
            onConfirm: { model.onConfirm() },
            onReject: { model.onReject() }
        )
        .opacity(1)
    }
}
