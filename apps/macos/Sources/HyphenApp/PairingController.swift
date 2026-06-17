import AppKit
import HyphenCore
import HyphenDiagnostics
import HyphenNotifications
import HyphenText
import HyphenTransport
import HyphenTransfer
import Network
import UniformTypeIdentifiers

/// Mac side of the SAS pairing flow (HYP-M2-011, protocol v0 §5):
/// shows the QR (HYP-M2-009), accepts ONE provisional TLS connection —
/// any client certificate is admitted but its SPKI fingerprint is
/// captured (protocol §2: provisional until SAS confirmation) — then
/// displays the SAS for both-sides comparison. Trust is written through
/// `SasConfirmationGate` only when the user confirms; reject/close
/// persists nothing. The provisional connection is closed after the
/// decision either way — the steady-state session arrives with M2-012/013.
final class PairingController: NSObject {

    private let queue = DispatchQueue(label: "hyphen-pairing")
    private var listener: TLSEndpointListener?
    private var pairingAttemptID: UUID?
    // Section C pairing surface: the design SwiftUI window (QR + SAS) hosted in
    // a borderless window. Only the presentation changed here — the listener,
    // SAS gate, and session logic below are unchanged.
    private var pairingWindow: DesignWindowController?
    private let pairingModel = PairingWindowModel()
    private let provisionalLock = NSLock()
    private var provisional = ProvisionalPairingState()
    private var provisionalConnection: NWConnection?
    private var activeSession: ProtocolSession?
    private var activeSessionToken: UUID?
    private var activeCapabilities: SessionHandshake.NegotiatedCapabilities?
    private var activeTransferSender: TransferSender?
    private let tokenStore = ResumeTokenStore()
    private let textReceiver = TextLinkReceiver()
    private var lastTransferProgress: TransferProgress?
    private lazy var transferReceiver = TransferReceiver { [weak self] progress in
        DispatchQueue.main.async {
            self?.lastTransferProgress = progress
            self?.onStatus(Self.transferProgressLine(progress))
            self?.onActivity(.transferProgress(progress, direction: .incoming, at: Date()))
        }
    }
    private let notificationPresenter = UserNotificationCenterPresenter()
    // Mirrors every presented (post-scrub) notification into the activity feed
    // while forwarding to the real system presenter. The receiver below talks to
    // this decorator; `setReply/DismissHandler` still target `notificationPresenter`.
    private lazy var feedNotificationPresenter = ActivityFeedNotificationPresenter(
        wrapped: notificationPresenter,
        onShow: { [weak self] request in
            self?.emitActivity(.notificationPosted(request, at: Date()))
        },
        onRemove: { [weak self] identifier in
            self?.emitActivity(.notificationRemoved(identifier: identifier, at: Date()))
        }
    )
    private lazy var notificationReceiver = NotificationMirrorReceiver(
        presenter: feedNotificationPresenter,
        replyActionsEnabled: { [weak self] in
            NotificationCapabilityGate.canPresentReplyActions(self?.activeCapabilities)
        },
        privacyPolicy: { [weak self] in
            self?.notificationPrivacyPolicy() ?? .allowAll
        }
    )
    private let diagnosticLogs: LocalStructuredLogStore
    private let onStatus: (String) -> Void
    private let onActivity: (ActivityEvent) -> Void
    private let notificationPrivacyPolicy: () -> NotificationPrivacyPolicy

    init(
        diagnosticLogs: LocalStructuredLogStore = LocalStructuredLogStore(),
        notificationPrivacyPolicy: @escaping () -> NotificationPrivacyPolicy = { .allowAll },
        onActivity: @escaping (ActivityEvent) -> Void = { _ in },
        onStatus: @escaping (String) -> Void
    ) {
        self.diagnosticLogs = diagnosticLogs
        self.notificationPrivacyPolicy = notificationPrivacyPolicy
        self.onActivity = onActivity
        self.onStatus = onStatus
    }

    /// Emit a structured activity event to the app model on the main thread.
    /// The model is the UI's source of truth; `onStatus` stays for the legacy
    /// NSMenu state line.
    private func emitActivity(_ event: ActivityEvent) {
        if Thread.isMainThread {
            onActivity(event)
        } else {
            DispatchQueue.main.async { [weak self] in self?.onActivity(event) }
        }
    }

    var isActive: Bool { pairingWindow != nil }
    var hasActiveSession: Bool { activeSession != nil }

    func beginPairing() {
        if let pairingWindow {
            pairingWindow.present { [pairingModel, weak self] in
                PairingWindowHost(model: pairingModel, onClose: { self?.endPairing() })
            }
            return
        }
        guard listener == nil else {
            onStatus("Pairing already starting")
            return
        }
        do {
            let deviceName = Host.current().localizedName ?? "Hyphen Mac"
            let identity = try KeychainIdentityStore().loadOrCreate(commonName: deviceName)
            let nonce = PairingNonce.random()
            let attemptID = UUID()
            pairingAttemptID = attemptID
            cancelAndClearProvisionalConnection()

            // Provisional verifier: admit one unknown client and remember
            // its claimed fingerprint for the SAS transcript.
            let verifier = SPKIPinVerifier(isTrusted: { [weak self] fingerprint in
                guard let self else { return false }
                return self.claimPendingFingerprint(fingerprint)
            })

            let listener = TLSEndpointListener(identity: identity, verifier: verifier)
            self.listener = listener
            try listener.start(
                port: 0,
                queue: queue,
                onState: { [weak self] state in
                    guard let self, case .listening(let port) = state else { return }
                    DispatchQueue.main.async {
                        self.presentWindow(
                            attemptID: attemptID,
                            identity: identity,
                            nonce: nonce,
                            port: port,
                            deviceName: deviceName
                        )
                    }
                },
                onConnection: { [weak self] connection in
                    guard let self, let peerFingerprint = self.acceptProvisionalConnection(connection) else {
                        connection.cancel()
                        return
                    }
                    DispatchQueue.main.async {
                        self.presentSasConfirmation(
                            attemptID: attemptID,
                            identity: identity,
                            nonce: nonce,
                            deviceName: deviceName,
                            peerFingerprint: peerFingerprint
                        )
                    }
                },
                onConnectionState: { [weak self] connection, state in
                    switch state {
                    case .failed, .cancelled:
                        self?.releaseProvisionalConnection(connection)
                    default:
                        break
                    }
                }
            )
        } catch {
            pairingAttemptID = nil
            listener = nil
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
        pairingAttemptID = nil
        pairingModel.awaitingConfirmation = false
        resetPairingActions()
        cancelAndClearProvisionalConnection()
        pairingWindow?.close()
        pairingWindow = nil
    }

    private func presentWindow(
        attemptID: UUID,
        identity: DeviceIdentity,
        nonce: Data,
        port: UInt16,
        deviceName: String
    ) {
        guard pairingAttemptID == attemptID else { return }
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
        ) else {
            onStatus("Pairing QR could not be built")
            endPairing()
            return
        }

        // Feed the design window real values; the SwiftUI view renders the QR
        // from the payload URI. SAS/peer fields fill in once a phone connects.
        pairingModel.address = "\(host):\(port)"
        pairingModel.qrPayload = payload.uriString
        pairingModel.peerName = L("pairing.waitingForPhone")
        pairingModel.fingerprint = L("pairing.waitingForPhone")
        pairingModel.sasCodes = ["··", "··", "··"]
        pairingModel.awaitingConfirmation = false
        resetPairingActions()

        let controller = DesignWindowController(onClosed: { [weak self] in
            // Closing the window aborts pairing; nothing was persisted unless
            // the SAS gate already confirmed (which clears the window first).
            self?.endPairing()
        })
        pairingWindow = controller
        controller.present { [pairingModel, weak self] in
            // The window-chrome close button aborts pairing too, matching Escape
            // and the legacy `.closable` window's behavior.
            PairingWindowHost(model: pairingModel, onClose: { self?.endPairing() })
        }
    }

    private func presentSasConfirmation(
        attemptID: UUID,
        identity: DeviceIdentity,
        nonce: Data,
        deviceName: String,
        peerFingerprint: Data
    ) {
        guard pairingAttemptID == attemptID, pairingWindow != nil else { return }
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

        // Drive the design window's SAS step (Section C): the "一致，建立信任" /
        // "不一致" buttons call the same gate.confirm()/reject() paths that the
        // old NSAlert used — the trust decision logic is unchanged.
        pairingModel.sasCodes = Self.sasGroups(gate.sas)
        // The device-chosen name arrives with hello (M2-012); until then use a
        // neutral, accurate label. Identity is carried by the real fingerprint
        // below, not a fabricated model name.
        pairingModel.peerName = L("pairing.androidDevice")
        pairingModel.fingerprint = Self.fingerprintLine(peerFingerprint)
        pairingModel.awaitingConfirmation = true
        pairingModel.onConfirm = { [weak self, gate] in
            guard let self else { return }
            guard self.pairingModel.awaitingConfirmation else { return }
            self.pairingModel.awaitingConfirmation = false
            self.resetPairingActions()
            do {
                guard try gate.confirm() == .trusted else {
                    self.onStatus("Pairing rejected — nothing stored")
                    self.endPairing()
                    return
                }
                let connection = self.takeProvisionalConnection()
                self.onStatus("Paired — fingerprint pinned (\(gate.sas))")
                self.emitActivity(.pairingNote(message: L("pairing.note.paired"), at: Date()))
                self.closePairingUI()
                self.startSteadySession(
                    connection: connection,
                    deviceName: deviceName,
                    peerFingerprint: peerFingerprint
                )
            } catch {
                self.onStatus("Trust store write failed: \(error)")
                self.endPairing()
            }
        }
        pairingModel.onReject = { [weak self, gate] in
            guard let self else { return }
            guard self.pairingModel.awaitingConfirmation else { return }
            self.pairingModel.awaitingConfirmation = false
            self.resetPairingActions()
            gate.reject()
            self.onStatus("Pairing rejected — nothing stored")
            self.endPairing()
        }
    }

    /// Splits the SAS string into the three groups the design shows. Falls back
    /// to even thirds when the gate does not pre-group it.
    private static func sasGroups(_ sas: String) -> [String] {
        let parts = sas.split(whereSeparator: { $0 == " " || $0 == "-" }).map(String.init)
        if parts.count >= 3 { return Array(parts.prefix(3)) }
        let digits = Array(sas.filter { !$0.isWhitespace })
        guard digits.count >= 3 else { return [sas, "", ""] }
        let n = digits.count / 3
        let g1 = String(digits[0..<n])
        let g2 = String(digits[n..<(2 * n)])
        let g3 = String(digits[(2 * n)...])
        return [g1, g2, g3]
    }

    /// "SHA‑256 · AA:BB:…:YY" preview from the pinned fingerprint bytes.
    private static func fingerprintLine(_ fingerprint: Data) -> String {
        HyphenFingerprintDisplay.string(for: fingerprint, style: .auditPreview)
    }

    private func closePairingUI() {
        listener?.stop()
        listener = nil
        pairingAttemptID = nil
        pairingModel.awaitingConfirmation = false
        resetPairingActions()
        pairingWindow?.close()
        pairingWindow = nil
    }

    private func resetPairingActions() {
        pairingModel.onConfirm = {}
        pairingModel.onReject = {}
    }

    private func claimPendingFingerprint(_ fingerprint: Data) -> Bool {
        provisionalLock.lock()
        defer { provisionalLock.unlock() }
        return provisional.claimFingerprint(fingerprint)
    }

    private func acceptProvisionalConnection(_ connection: NWConnection) -> Data? {
        provisionalLock.lock()
        defer { provisionalLock.unlock() }
        guard let peerFingerprint = provisional.attachConnection(ObjectIdentifier(connection)) else {
            return nil
        }
        provisionalConnection = connection
        return peerFingerprint
    }

    private func takeProvisionalConnection() -> NWConnection? {
        provisionalLock.lock()
        defer { provisionalLock.unlock() }
        let connection = provisionalConnection
        provisionalConnection = nil
        provisional.reset()
        return connection
    }

    private func cancelAndClearProvisionalConnection() {
        let connection = takeProvisionalConnection()
        connection?.cancel()
    }

    /// A provisional connection reached `.failed`/`.cancelled`. If it was the
    /// connection we were confirming, release the slot so a retry can pair
    /// without restarting the listener, and reset the SAS surface back to
    /// "waiting for phone". No-op for rejected concurrent peers or the
    /// post-confirm session connection (their ids no longer match).
    private func releaseProvisionalConnection(_ connection: NWConnection) {
        provisionalLock.lock()
        let released = provisional.releaseConnection(ObjectIdentifier(connection))
        if released {
            provisionalConnection = nil
        }
        provisionalLock.unlock()
        guard released else { return }
        DispatchQueue.main.async { [weak self] in
            guard let self, self.pairingModel.awaitingConfirmation else { return }
            self.pairingModel.awaitingConfirmation = false
            self.resetPairingActions()
            self.pairingModel.peerName = L("pairing.waitingForPhone")
            self.pairingModel.fingerprint = L("pairing.waitingForPhone")
            self.pairingModel.sasCodes = ["··", "··", "··"]
            self.onStatus(L("pairing.status.dropped"))
        }
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
                    DispatchQueue.main.async {
                        self?.handleSessionEnvelope(envelope)
                    }
                }
                callbacks.onAck = { [weak self] messageId in
                    DispatchQueue.main.async {
                        // Advances the outbound transfer window (chunk acks).
                        try? self?.activeTransferSender?.handleAck(messageId)
                    }
                }
                callbacks.onClosed = { [weak self] in
                    DispatchQueue.main.async {
                        if self?.activeSessionToken == sessionToken {
                            self?.activeSession = nil
                            self?.activeSessionToken = nil
                            self?.activeCapabilities = nil
                            self?.activeTransferSender = nil
                            self?.lastTransferProgress = nil
                            self?.notificationPresenter.setDismissHandler(nil)
                            self?.notificationPresenter.setReplyHandler(nil)
                        }
                        self?.onStatus("Phone session closed")
                        self?.onActivity(.connectionStateChanged(.suspended, latencyMs: nil))
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
                DispatchQueue.main.async {
                    self.activeSession?.stop()
                    self.activeSession = session
                    self.activeSessionToken = sessionToken
                    self.activeCapabilities = handshake.negotiatedCapabilities
                    self.activeTransferSender = TransferSender(
                        outbox: ProtocolSessionTransferOutbox(session: session),
                        negotiatedCapabilities: handshake.negotiatedCapabilities,
                        onProgress: { [weak self] progress in
                            DispatchQueue.main.async {
                                self?.lastTransferProgress = progress
                                self?.onStatus(Self.transferProgressLine(progress))
                                self?.onActivity(.transferProgress(progress, direction: .outgoing, at: Date()))
                            }
                        }
                    )
                    if NotificationCapabilityGate.canSendDismiss(handshake.negotiatedCapabilities) {
                        self.notificationPresenter.setDismissHandler { [weak self] sbnKey in
                            self?.sendNotificationDismissRequest(sbnKey: sbnKey)
                        }
                    } else {
                        self.notificationPresenter.setDismissHandler(nil)
                    }
                    if NotificationCapabilityGate.canSendReply(handshake.negotiatedCapabilities) {
                        self.notificationPresenter.setReplyHandler { [weak self] sbnKey, actionIndex, actionId, text in
                            self?.sendNotificationReplyRequest(
                                sbnKey: sbnKey,
                                actionIndex: actionIndex,
                                actionId: actionId,
                                text: text
                            )
                        }
                    } else {
                        self.notificationPresenter.setReplyHandler(nil)
                    }
                    session.start(replaying: handshake.leftover)
                    // Push the current per-app privacy policy so Android filters
                    // content source-side from the start of the session.
                    self.syncNotificationPrivacyPolicy()
                    let name = handshake.peerDeviceName ?? "Android device"
                    self.onStatus("Connected to \(name)")
                    self.onActivity(.peerChanged(isPaired: true, peerName: name))
                    self.onActivity(.connectionStateChanged(.connected, latencyMs: nil))
                    self.onActivity(.pairingNote(message: L("pairing.note.connected", name), at: Date()))
                }
            }
        }
    }

    /// User-initiated reply from the popover timeline. Routes through the same
    /// capability-gated path the system-notification quick reply uses.
    func requestNotificationReply(sbnKey: String, actionIndex: Int, actionId: String?, text: String) {
        sendNotificationReplyRequest(sbnKey: sbnKey, actionIndex: actionIndex, actionId: actionId, text: text)
    }

    /// User-initiated dismiss ("关闭") from the popover timeline.
    func requestNotificationDismiss(sbnKey: String) {
        sendNotificationDismissRequest(sbnKey: sbnKey)
    }

    private func sendNotificationDismissRequest(sbnKey: String) {
        guard let session = activeSession else {
            onStatus("notification dismiss: no active Android session")
            return
        }
        guard NotificationCapabilityGate.canSendDismiss(activeCapabilities) else {
            onStatus("notification dismiss: not negotiated")
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

    private func sendNotificationReplyRequest(sbnKey: String, actionIndex: Int, actionId: String?, text: String) {
        guard let session = activeSession else {
            onStatus("notification reply: no active Android session")
            return
        }
        guard NotificationCapabilityGate.canSendReply(activeCapabilities) else {
            onStatus("notification reply: not negotiated")
            return
        }
        let id = NotificationReplySender(
            outbox: ProtocolSessionNotificationDismissOutbox(session: session)
        ).requestReply(sbnKey: sbnKey, actionIndex: actionIndex, actionId: actionId, text: text)
        if let id {
            onStatus("notification reply requested: \(id)")
        } else {
            onStatus("notification reply rejected")
        }
    }

    /// Push the current per-app notification privacy policy to the paired
    /// Android peer so it can filter content source-side. No-op unless a session
    /// is active and both peers negotiated `notifications.v1.privacyPolicy`.
    func syncNotificationPrivacyPolicy() {
        guard let session = activeSession,
              activeCapabilities?.notificationPrivacyPolicyEnabled == true else {
            return
        }
        _ = NotificationPrivacyPolicySender(
            outbox: ProtocolSessionNotificationDismissOutbox(session: session)
        ).send(policy: notificationPrivacyPolicy())
    }

    func sendTextLink(raw: String) {
        guard let session = activeSession else {
            onStatus("text/link: no active Android session")
            return
        }
        guard activeCapabilities?.contains(SessionHandshake.capabilityText) == true else {
            onStatus("text/link: peer did not negotiate text.v1")
            return
        }
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        do {
            let kind: TextLinkKind = Self.isHTTPURL(trimmed) ? .url : .text
            let message = try TextLinkMessage(kind: kind, value: trimmed)
            let id = TextLinkSender(outbox: ProtocolSessionTextLinkOutbox(session: session)).send(message)
            if let id {
                onStatus("text/link sent: \(id)")
                emitActivity(.text(
                    kind: kind == .url ? .url : .text,
                    direction: .sent,
                    value: trimmed,
                    at: Date()
                ))
            } else {
                onStatus("text/link send failed: session closed")
            }
        } catch {
            onStatus("text/link rejected: \(error)")
        }
    }

    /// Outbound file send entry: feeds a local file into the persistent
    /// `activeTransferSender`, populating its outbound registry so a later
    /// `resume.info`/ack from the peer routes to a real transfer.
    func sendFile(url: URL) {
        guard let sender = activeTransferSender else {
            onStatus("transfer/send: no active Android session")
            return
        }
        guard activeCapabilities?.contains(TransferProtocol.capability) == true else {
            onStatus("transfer/send: peer did not negotiate transfer.v1")
            return
        }
        do {
            let source = FileTransferByteSource(fileURL: url)
            let mimeType = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType
                ?? "application/octet-stream"
            let manifest = try sender.sendSource(
                filename: url.lastPathComponent,
                mimeType: mimeType,
                source: source,
                chunkSizeBytes: Self.defaultTransferChunkBytes
            )
            onStatus("transfer/send started: \(manifest.filename) (\(manifest.sizeBytes) bytes)")
            // Surface the outgoing row immediately; chunk-ack progress follows.
            if let progress = try? TransferProgress(manifest: manifest, completedChunks: 0) {
                emitActivity(.transferProgress(progress, direction: .outgoing, at: Date()))
            }
        } catch {
            onStatus("transfer/send rejected: \(error)")
        }
    }

    private static let defaultTransferChunkBytes = 256 * 1024

    private static func isHTTPURL(_ value: String) -> Bool {
        guard let scheme = URLComponents(string: value)?.scheme?.lowercased() else {
            return false
        }
        return scheme == "http" || scheme == "https"
    }

    private func handleSessionEnvelope(_ envelope: Envelope) {
        do {
            if envelope.type == Envelope.typeError {
                DispatchQueue.main.async { [weak self] in
                    self?.onStatus(Self.peerErrorLine(envelope))
                }
                return
            }
            if let session = activeSession,
               let expected = Self.expectedCapability(for: envelope.type),
               envelope.capability != expected {
                let id = sendProtocolError(
                    session: session,
                    regarding: envelope,
                    code: "plugin/unsupported-capability",
                    message: "Message type was sent under the wrong capability."
                )
                DispatchQueue.main.async { [weak self] in
                    self?.onStatus("unsupported capability reported: \(id ?? "session closed")")
                }
                return
            }
            if let session = activeSession, !isNegotiatedCapability(envelope.capability) {
                let id = sendProtocolError(
                    session: session,
                    regarding: envelope,
                    code: "plugin/unsupported-capability",
                    message: "Capability is not negotiated for this session."
                )
                DispatchQueue.main.async { [weak self] in
                    self?.onStatus("unsupported capability reported: \(id ?? "session closed")")
                }
                return
            }
            if let session = activeSession, !isNegotiatedNotificationOption(envelope.type) {
                let id = sendProtocolError(
                    session: session,
                    regarding: envelope,
                    code: "plugin/unsupported-capability",
                    message: "Notification action is not negotiated for this session."
                )
                DispatchQueue.main.async { [weak self] in
                    self?.onStatus("unsupported notification option reported: \(id ?? "session closed")")
                }
                return
            }
            if NotificationCapabilityGate.isNotificationEnvelope(type: envelope.type) {
                guard let session = activeSession,
                      NotificationCapabilityGate.allowsInboundEnvelope(type: envelope.type, capabilities: activeCapabilities)
                else {
                    return
                }
                if envelope.capability != NotificationMirrorProtocol.capability {
                    let id = sendProtocolError(
                        session: session,
                        regarding: envelope,
                        code: "plugin/unsupported-capability",
                        message: "Message type was sent under the wrong capability."
                    )
                    DispatchQueue.main.async { [weak self] in
                        self?.onStatus("unsupported capability reported: \(id ?? "session closed")")
                    }
                    return
                }
            }
            if let action = try notificationReceiver.handle(envelope) {
                DispatchQueue.main.async { [weak self] in
                    self?.renderNotificationAction(action)
                }
                return
            }
            if envelope.capability == TransferProtocol.capability {
                if envelope.type == TransferProtocol.typeResumeInfo {
                    let info = try TransferResumeInfo(payload: envelope.payload)
                    DispatchQueue.main.async { [weak self] in
                        do {
                            try self?.activeTransferSender?.handleResumeInfo(info)
                            self?.onStatus("transfer resume continued: \(info.fileId)")
                        } catch {
                            self?.onStatus("transfer resume info ignored: \(error)")
                        }
                    }
                    return
                }
                switch try transferReceiver.handle(envelope) {
                case .completed(let completed):
                    // Debug surface keeps no copy; drop the temp file so completed
                    // transfers do not accumulate in the storage directory.
                    try? FileManager.default.removeItem(at: completed.fileURL)
                    DispatchQueue.main.async { [weak self] in
                        self?.lastTransferProgress = nil
                        self?.onStatus("transfer received: \(completed.manifest.filename) (\(completed.manifest.sizeBytes) bytes)")
                        // The receiver only reaches `.completed` after every chunk's
                        // SHA-256 verified, so the feed marks it verified.
                        self?.onActivity(.transferCompleted(
                            fileId: completed.manifest.fileId,
                            filename: completed.manifest.filename,
                            sizeBytes: completed.manifest.sizeBytes,
                            direction: .incoming,
                            verified: true,
                            at: Date()
                        ))
                    }
                case .resumeRequested(let info):
                    let session = activeSession
                    DispatchQueue.main.async { [weak self] in
                        guard let session else {
                            self?.onStatus("transfer resume requested without active session")
                            return
                        }
                        let id = TransferSender(outbox: ProtocolSessionTransferOutbox(session: session)).sendResumeInfo(info)
                        self?.onStatus("transfer resume info sent: \(id ?? "session closed")")
                    }
                case .cancelled(let cancel):
                    DispatchQueue.main.async { [weak self] in
                        self?.lastTransferProgress = nil
                        self?.onStatus("transfer cancelled: \(cancel.fileId)")
                        self?.onActivity(.transferCancelled(fileId: cancel.fileId, at: Date()))
                    }
                case .ignored:
                    break
                }
                return
            }
            if let request = try textReceiver.handle(envelope) {
                DispatchQueue.main.async { [weak self] in
                    self?.presentTextLinkConfirmation(request)
                }
                return
            }
            if Self.isPluginEnvelope(envelope), let session = activeSession {
                let id = sendProtocolError(
                    session: session,
                    regarding: envelope,
                    code: "protocol/unknown-type",
                    message: "No handler is registered for this message type."
                )
                DispatchQueue.main.async { [weak self] in
                    self?.onStatus("unknown type reported: \(id ?? "session closed")")
                }
            }
        } catch {
            if envelope.type != Envelope.typeError, let session = activeSession {
                _ = sendProtocolError(
                    session: session,
                    regarding: envelope,
                    code: "protocol/invalid-envelope",
                    message: "Envelope payload is invalid for its type."
                )
            }
            DispatchQueue.main.async { [weak self] in
                self?.onStatus("session envelope rejected: \(error)")
            }
        }
    }

    private func isNegotiatedCapability(_ capability: String?) -> Bool {
        guard let capability else { return true }
        return activeCapabilities?.contains(capability) != false
    }

    private func isNegotiatedNotificationOption(_ type: String) -> Bool {
        NotificationCapabilityGate.allowsInboundResult(type: type, capabilities: activeCapabilities)
    }

    private static func isPluginEnvelope(_ envelope: Envelope) -> Bool {
        ![Envelope.typeAck, Envelope.typeHeartbeat, Envelope.typeHello, Envelope.typeError].contains(envelope.type)
    }

    private static func expectedCapability(for type: String) -> String? {
        switch type {
        case NotificationMirrorProtocol.typePosted,
             NotificationMirrorProtocol.typeUpdated,
             NotificationMirrorProtocol.typeRemoved,
             NotificationMirrorProtocol.typeDismissResult,
             NotificationMirrorProtocol.typeReplyResult:
            return NotificationMirrorProtocol.capability
        case TransferProtocol.typeManifest,
             TransferProtocol.typeChunk,
             TransferProtocol.typeResumeRequest,
             TransferProtocol.typeResumeInfo,
             TransferProtocol.typeCancel:
            return TransferProtocol.capability
        case TextLinkMessage.typeSend:
            return TextLinkMessage.capability
        default:
            return nil
        }
    }

    @discardableResult
    private func sendProtocolError(
        session: ProtocolSession,
        regarding envelope: Envelope,
        code: String,
        message: String,
        retryable: Bool = false
    ) -> String? {
        session.send(
            type: Envelope.typeError,
            payload: [
                "code": code,
                "message": String(message.prefix(256)),
                "regarding": envelope.messageId,
                "retryable": retryable,
            ],
            requiresAck: false
        )
    }

    private static func peerErrorLine(_ envelope: Envelope) -> String {
        let code = envelope.payload["code"] as? String ?? "unknown"
        let regarding = envelope.payload["regarding"] as? String ?? "none"
        return "peer error: \(code) regarding \(regarding)"
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
            textReceiver.resolve(messageId: request.messageId)
            onStatus("Text/link declined")
            return
        }
        textReceiver.resolve(messageId: request.messageId)
        switch request.message.kind {
        case .text:
            NSPasteboard.general.clearContents()
            NSPasteboard.general.setString(request.message.value, forType: .string)
            onStatus("Text copied from Android")
            emitActivity(.text(kind: .text, direction: .received, value: request.message.value, at: Date()))
        case .url:
            if let url = URL(string: request.message.value) {
                NSWorkspace.shared.open(url)
                onStatus("Link opened from Android")
                emitActivity(.text(kind: .url, direction: .received, value: request.message.value, at: Date()))
            } else {
                onStatus("Link rejected: invalid URL")
            }
        }
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
