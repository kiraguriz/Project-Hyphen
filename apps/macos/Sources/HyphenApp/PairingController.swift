import AppKit
import HyphenCore
import HyphenTransport
import Network

/// Mac side of the SAS pairing flow (HYP-M2-011, protocol v0 §5):
/// shows the QR (HYP-M2-009), accepts ONE provisional TLS connection —
/// any client certificate is admitted but its SPKI fingerprint is
/// captured (protocol §2: provisional until SAS confirmation) — then
/// displays the SAS for both-sides comparison. Trust is written through
/// `SasConfirmationGate` only when the user confirms; reject/close
/// persists nothing. The provisional connection is closed after the
/// decision either way — the steady-state session arrives with M2-012/013.
final class PairingController: NSObject, NSWindowDelegate {

    private let queue = DispatchQueue(label: "hyphen-pairing")
    private var listener: TLSEndpointListener?
    private var window: NSWindow?
    private var codeLabel: NSTextField?
    private var pendingFingerprint: Data?
    private var provisionalConnection: NWConnection?
    private let onStatus: (String) -> Void

    init(onStatus: @escaping (String) -> Void) {
        self.onStatus = onStatus
    }

    var isActive: Bool { window != nil }

    func beginPairing() {
        guard window == nil else {
            window?.makeKeyAndOrderFront(nil)
            return
        }
        do {
            let deviceName = Host.current().localizedName ?? "Hyphen Mac"
            let identity = try KeychainIdentityStore().loadOrCreate(commonName: deviceName)
            let nonce = PairingNonce.random()

            // Provisional verifier: admit one unknown client and remember
            // its claimed fingerprint for the SAS transcript.
            let verifier = SPKIPinVerifier(isTrusted: { [weak self] fingerprint in
                guard let self else { return false }
                if self.pendingFingerprint == nil {
                    self.pendingFingerprint = fingerprint
                    return true
                }
                return false // one provisional peer at a time
            })

            let listener = TLSEndpointListener(identity: identity, verifier: verifier)
            self.listener = listener
            try listener.start(
                port: 0,
                queue: queue,
                onState: { [weak self] state in
                    guard let self, case .listening(let port) = state else { return }
                    DispatchQueue.main.async {
                        self.presentWindow(identity: identity, nonce: nonce, port: port, deviceName: deviceName)
                    }
                },
                onConnection: { [weak self] connection in
                    guard let self, let peerFingerprint = self.pendingFingerprint else { return }
                    self.provisionalConnection = connection
                    DispatchQueue.main.async {
                        self.presentSasConfirmation(
                            identity: identity,
                            nonce: nonce,
                            peerFingerprint: peerFingerprint
                        )
                    }
                }
            )
        } catch {
            onStatus("Pairing failed to start: \(error)")
        }
    }

    func endPairing() {
        listener?.stop()
        listener = nil
        provisionalConnection?.cancel()
        provisionalConnection = nil
        pendingFingerprint = nil
        if let window {
            window.delegate = nil
            window.orderOut(nil)
        }
        window = nil
        codeLabel = nil
    }

    private func presentWindow(identity: DeviceIdentity, nonce: Data, port: UInt16, deviceName: String) {
        guard let host = LocalIPv4.first() else {
            onStatus("Pairing needs a LAN IPv4 address — none found")
            endPairing()
            return
        }
        guard let payload = PairingQRPayload(
            host: host,
            port: port,
            spkiFingerprint: identity.spkiFingerprint,
            nonce: nonce,
            deviceName: deviceName
        ), let qr = QRCodeRenderer.image(for: payload.uriString) else {
            onStatus("Pairing QR could not be built")
            endPairing()
            return
        }

        let imageView = NSImageView(frame: NSRect(x: 20, y: 100, width: 320, height: 320))
        imageView.image = NSImage(cgImage: qr, size: NSSize(width: 320, height: 320))
        imageView.imageScaling = .scaleProportionallyUpOrDown

        let instructions = NSTextField(labelWithString: "Scan with the Hyphen Android app\n\(host):\(port)")
        instructions.frame = NSRect(x: 20, y: 56, width: 320, height: 36)
        instructions.alignment = .center

        let code = NSTextField(labelWithString: "Waiting for the phone to connect…")
        code.frame = NSRect(x: 20, y: 16, width: 320, height: 28)
        code.alignment = .center
        code.font = .monospacedDigitSystemFont(ofSize: 16, weight: .semibold)
        codeLabel = code

        let content = NSView(frame: NSRect(x: 0, y: 0, width: 360, height: 440))
        content.addSubview(imageView)
        content.addSubview(instructions)
        content.addSubview(code)

        let window = NSWindow(
            contentRect: content.frame,
            styleMask: [.titled, .closable],
            backing: .buffered,
            defer: false
        )
        window.title = "Pair New Device"
        window.contentView = content
        window.isReleasedWhenClosed = false
        window.delegate = self
        window.center()
        self.window = window
        NSApp.activate(ignoringOtherApps: true)
        window.makeKeyAndOrderFront(nil)
    }

    private func presentSasConfirmation(identity: DeviceIdentity, nonce: Data, peerFingerprint: Data) {
        guard let transcript = PairingTranscript(
            nonce: nonce,
            macSpkiFingerprint: identity.spkiFingerprint,
            androidSpkiFingerprint: peerFingerprint,
            protocolVersion: HyphenCore.protocolVersion
        ) else {
            onStatus("Pairing aborted: transcript could not be built")
            endPairing()
            return
        }
        let gate = SasConfirmationGate(
            transcript: transcript,
            peerFingerprint: peerFingerprint,
            peerDisplayName: "Android device", // device-chosen names arrive with hello (M2-012)
            trustStore: KeychainTrustStore()
        )
        codeLabel?.stringValue = "Pairing code: \(gate.sas)"

        let alert = NSAlert()
        alert.messageText = "Confirm pairing code"
        alert.informativeText = "Code: \(gate.sas)\n\nTrust this phone only if it shows the same code."
        alert.alertStyle = .warning
        alert.addButton(withTitle: "Codes Match — Trust")
        alert.addButton(withTitle: "Reject")
        NSApp.activate(ignoringOtherApps: true)

        if alert.runModal() == .alertFirstButtonReturn {
            do {
                try gate.confirm()
                onStatus("Paired — fingerprint pinned (\(gate.sas))")
            } catch {
                onStatus("Trust store write failed: \(error)")
            }
        } else {
            gate.reject()
            onStatus("Pairing rejected — nothing stored")
        }
        endPairing()
    }

    func windowWillClose(_ notification: Notification) {
        // Closing the window aborts pairing; nothing was persisted unless
        // the gate already confirmed.
        endPairing()
    }
}

/// First active non-loopback IPv4, preferring en* interfaces (Wi-Fi/
/// Ethernet) — the address that goes into the QR endpoint.
enum LocalIPv4 {
    static func first() -> String? {
        var addrs: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&addrs) == 0 else { return nil }
        defer { freeifaddrs(addrs) }

        var fallback: String?
        var cursor = addrs
        while let ifa = cursor {
            defer { cursor = ifa.pointee.ifa_next }
            let flags = Int32(ifa.pointee.ifa_flags)
            guard let sa = ifa.pointee.ifa_addr,
                  sa.pointee.sa_family == UInt8(AF_INET),
                  flags & IFF_LOOPBACK == 0,
                  flags & IFF_UP != 0
            else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            guard getnameinfo(sa, socklen_t(sa.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 else { continue }
            let address = String(cString: host)
            if String(cString: ifa.pointee.ifa_name).hasPrefix("en") {
                return address
            }
            if fallback == nil { fallback = address }
        }
        return fallback
    }
}
