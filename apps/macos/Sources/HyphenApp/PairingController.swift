import AppKit
import HyphenCore
import HyphenDiagnostics
import HyphenNotifications
import HyphenText
import HyphenTransport
import HyphenTransfer
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
    private var activeSession: ProtocolSession?
    private var activeSessionToken: UUID?
    private let tokenStore = ResumeTokenStore()
    private let textReceiver = TextLinkReceiver()
    private var lastTransferProgress: TransferProgress?
    private lazy var transferReceiver = TransferReceiver { [weak self] progress in
        DispatchQueue.main.async {
            self?.lastTransferProgress = progress
            self?.onStatus(Self.transferProgressLine(progress))
        }
    }
    private let notificationPresenter = UserNotificationCenterPresenter()
    private lazy var notificationReceiver = NotificationMirrorReceiver(
        presenter: notificationPresenter
    )
    private let diagnosticLogs: LocalStructuredLogStore
    private let onStatus: (String) -> Void

    init(
        diagnosticLogs: LocalStructuredLogStore = LocalStructuredLogStore(),
        onStatus: @escaping (String) -> Void
    ) {
        self.diagnosticLogs = diagnosticLogs
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
                            deviceName: deviceName,
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
        activeSession?.stop()
        activeSession = nil
        activeSessionToken = nil
        lastTransferProgress = nil
        notificationPresenter.setDismissHandler(nil)
        notificationPresenter.setReplyHandler(nil)
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

    private func presentSasConfirmation(
        identity: DeviceIdentity,
        nonce: Data,
        deviceName: String,
        peerFingerprint: Data
    ) {
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
                closePairingUI()
                startSteadySession(
                    connection: provisionalConnection,
                    deviceName: deviceName,
                    peerFingerprint: peerFingerprint
                )
                provisionalConnection = nil
                pendingFingerprint = nil
                return
            } catch {
                onStatus("Trust store write failed: \(error)")
            }
        } else {
            gate.reject()
            onStatus("Pairing rejected — nothing stored")
        }
        endPairing()
    }

    private func closePairingUI() {
        listener?.stop()
        listener = nil
        if let window {
            window.delegate = nil
            window.orderOut(nil)
        }
        window = nil
        codeLabel = nil
    }

    private func startSteadySession(connection: NWConnection?, deviceName: String, peerFingerprint: Data) {
        guard let connection else {
            onStatus("Pairing completed but no TLS connection remained")
            return
        }
        let device = SessionHandshake.DeviceInfo(
            kind: "macos",
            appVersion: HyphenCore.version,
            deviceName: deviceName
        )
        SessionHandshake.respond(
            connection: connection,
            device: device,
            peerFingerprint: peerFingerprint,
            tokenStore: tokenStore,
            queue: queue
        ) { [weak self] result in
            guard let self else { return }
            switch result {
            case .failure(let error):
                connection.cancel()
                DispatchQueue.main.async {
                    self.onStatus("Session handshake failed: \(error)")
                }
            case .success(let handshake):
                let sessionToken = UUID()
                var callbacks = ProtocolSession.Callbacks()
                callbacks.onEnvelope = { [weak self] envelope in
                    self?.handleSessionEnvelope(envelope)
                }
                callbacks.onClosed = { [weak self] in
                    DispatchQueue.main.async {
                        if self?.activeSessionToken == sessionToken {
                            self?.activeSession = nil
                            self?.activeSessionToken = nil
                            self?.lastTransferProgress = nil
                            self?.notificationPresenter.setDismissHandler(nil)
                            self?.notificationPresenter.setReplyHandler(nil)
                        }
                        self?.onStatus("Phone session closed")
                    }
                }
                var config = ProtocolSession.Config()
                config.startingSeq = 1
                let session = ProtocolSession(
                    connection: connection,
                    sessionId: handshake.sessionId,
                    config: config,
                    callbacks: DiagnosticProtocolSessionCallbacks.wrap(
                        store: self.diagnosticLogs,
                        forwarding: callbacks
                    )
                )
                self.activeSession?.stop()
                self.activeSession = session
                self.activeSessionToken = sessionToken
                self.notificationPresenter.setDismissHandler { [weak self] sbnKey in
                    self?.sendNotificationDismissRequest(sbnKey: sbnKey)
                }
                self.notificationPresenter.setReplyHandler { [weak self] sbnKey, actionIndex, text in
                    self?.sendNotificationReplyRequest(sbnKey: sbnKey, actionIndex: actionIndex, text: text)
                }
                session.start(replaying: handshake.leftover)
                DispatchQueue.main.async {
                    let name = handshake.peerDeviceName ?? "Android device"
                    self.onStatus("Connected to \(name)")
                }
            }
        }
    }

    private func sendNotificationDismissRequest(sbnKey: String) {
        guard let session = activeSession else {
            onStatus("notification dismiss: no active Android session")
            return
        }
        let id = NotificationDismissSender(
            outbox: ProtocolSessionNotificationDismissOutbox(session: session)
        ).requestDismiss(sbnKey: sbnKey)
        if let id {
            onStatus("notification dismiss requested: \(id)")
        } else {
            onStatus("notification dismiss rejected: blank key")
        }
    }

    private func sendNotificationReplyRequest(sbnKey: String, actionIndex: Int, text: String) {
        guard let session = activeSession else {
            onStatus("notification reply: no active Android session")
            return
        }
        let id = NotificationReplySender(
            outbox: ProtocolSessionNotificationDismissOutbox(session: session)
        ).requestReply(sbnKey: sbnKey, actionIndex: actionIndex, text: text)
        if let id {
            onStatus("notification reply requested: \(id)")
        } else {
            onStatus("notification reply rejected")
        }
    }

    func sendTextLink(raw: String) {
        guard let session = activeSession else {
            onStatus("text/link: no active Android session")
            return
        }
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        do {
            let kind: TextLinkKind = Self.isHTTPURL(trimmed) ? .url : .text
            let message = try TextLinkMessage(kind: kind, value: trimmed)
            let id = TextLinkSender(outbox: ProtocolSessionTextLinkOutbox(session: session)).send(message)
            if let id {
                onStatus("text/link sent: \(id)")
            } else {
                onStatus("text/link send failed: session closed")
            }
        } catch {
            onStatus("text/link rejected: \(error)")
        }
    }

    private static func isHTTPURL(_ value: String) -> Bool {
        guard let scheme = URLComponents(string: value)?.scheme?.lowercased() else {
            return false
        }
        return scheme == "http" || scheme == "https"
    }

    private func handleSessionEnvelope(_ envelope: Envelope) {
        do {
            if let action = try notificationReceiver.handle(envelope) {
                DispatchQueue.main.async { [weak self] in
                    self?.renderNotificationAction(action)
                }
                return
            }
            if envelope.capability == TransferProtocol.capability {
                let completed = try transferReceiver.handle(envelope)
                if let completed {
                    DispatchQueue.main.async { [weak self] in
                        self?.lastTransferProgress = nil
                        self?.onStatus("transfer received: \(completed.manifest.filename) (\(completed.bytes.count) bytes)")
                    }
                }
                return
            }
            guard let request = try textReceiver.handle(envelope) else { return }
            DispatchQueue.main.async { [weak self] in
                self?.presentTextLinkConfirmation(request)
            }
        } catch {
            DispatchQueue.main.async { [weak self] in
                self?.onStatus("Text/link rejected: \(error)")
            }
        }
    }

    private func renderNotificationAction(_ action: NotificationMirrorAction) {
        switch action {
        case .shown:
            onStatus("Android notification mirrored")
        case .removed:
            onStatus("Android notification removed")
        case .dismissResult(_, let success, let errorCode):
            if success {
                onStatus("Android notification dismissed")
            } else {
                onStatus("Android notification dismiss failed: \(errorCode ?? "unknown")")
            }
        case .replyResult(_, let success, let errorCode):
            if success {
                onStatus("Android notification reply sent")
            } else {
                onStatus("Android notification reply failed: \(errorCode ?? "unknown")")
            }
        }
    }

    func cancelActiveTransfer() {
        guard let progress = lastTransferProgress, !progress.isComplete else {
            onStatus("transfer cancel: no active transfer")
            return
        }
        guard let session = activeSession else {
            onStatus("transfer cancel: no active Android session")
            return
        }
        do {
            let id = TransferSender(outbox: ProtocolSessionTransferOutbox(session: session)).sendCancel(
                try TransferCancel(fileId: progress.fileId, discard: true)
            )
            lastTransferProgress = nil
            onStatus("transfer cancel sent: \(id ?? "session closed")")
        } catch {
            onStatus("transfer cancel failed: \(error)")
        }
    }

    private static func transferProgressLine(_ progress: TransferProgress) -> String {
        "transfer \(progress.filename): \(progress.completedBytes)/\(progress.totalBytes) bytes " +
            "(\(progress.completedChunks)/\(progress.totalChunks))"
    }

    private func presentTextLinkConfirmation(_ request: TextLinkConfirmationRequest) {
        let alert = NSAlert()
        switch request.message.kind {
        case .text:
            alert.messageText = "Copy text from Android?"
            alert.addButton(withTitle: "Copy")
        case .url:
            alert.messageText = "Open link from Android?"
            alert.addButton(withTitle: "Open")
        }
        alert.informativeText = request.message.value
        alert.alertStyle = .informational
        alert.addButton(withTitle: "Cancel")
        NSApp.activate(ignoringOtherApps: true)

        guard alert.runModal() == .alertFirstButtonReturn else {
            onStatus("Text/link declined")
            return
        }
        switch request.message.kind {
        case .text:
            NSPasteboard.general.clearContents()
            NSPasteboard.general.setString(request.message.value, forType: .string)
            onStatus("Text copied from Android")
        case .url:
            if let url = URL(string: request.message.value) {
                NSWorkspace.shared.open(url)
                onStatus("Link opened from Android")
            } else {
                onStatus("Link rejected: invalid URL")
            }
        }
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
