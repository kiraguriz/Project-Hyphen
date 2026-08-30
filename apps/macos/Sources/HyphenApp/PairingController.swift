import AppKit
import HyphenCore
import HyphenDiagnostics
import HyphenNotifications
import HyphenText
import HyphenTransport
import HyphenTransfer
import Network
import UniformTypeIdentifiers

protocol InteractiveNotificationPresenter: NotificationPresenter {
    func setDismissHandler(_ handler: ((String) -> Void)?)
    func setReplyHandler(_ handler: ((String, Int, String?, String) -> Void)?)
}

extension UserNotificationCenterPresenter: InteractiveNotificationPresenter {}

protocol TrustedPeerReconnectListener: AnyObject {
    func start(
        port: UInt16,
        queue: DispatchQueue,
        onState: @escaping (TLSEndpointListener.State) -> Void,
        onConnection: @escaping (NWConnection) -> Void,
        onConnectionState: ((NWConnection, NWConnection.State) -> Void)?
    ) throws
    func stop()
}

extension TLSEndpointListener: TrustedPeerReconnectListener {}

typealias SteadyHandshakeResponder = (
    NWConnection,
    SessionHandshake.DeviceInfo,
    Data,
    ResumeTokenStore,
    ResumeTokenStore.Issuance,
    DispatchQueue,
    @escaping (Result<SessionHandshake.HandshakeResult, Error>) -> Void
) -> Void

struct SessionCallbackOwnership: Equatable {
    let token: UUID
    let generation: UInt64
    let peerFingerprint: Data
}

/// Serializes live-session callbacks against replacement and trust revocation.
/// The recursive lock permits an app callback to synchronously trigger revoke
/// without deadlocking; revocation still invalidates the ticket before any
/// later callback can mutate product state.
final class SessionCallbackOwnershipGate {
    private let lock = NSRecursiveLock()
    private var current: SessionCallbackOwnership?

    func activate(_ ownership: SessionCallbackOwnership) {
        _ = activate(ownership, ifAccepted: { true })
    }

    /// Replaces the current ticket only after the caller atomically proves the
    /// candidate still owns the product session. A rejected stale handshake
    /// must never displace callbacks for a newer live session.
    @discardableResult
    func activate(
        _ ownership: SessionCallbackOwnership,
        ifAccepted: () -> Bool
    ) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard ifAccepted() else { return false }
        current = ownership
        return true
    }

    @discardableResult
    func withCurrent(_ ownership: SessionCallbackOwnership, action: () -> Void) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard current == ownership else { return false }
        action()
        return true
    }

    @discardableResult
    func consumeCurrent(_ ownership: SessionCallbackOwnership, action: () -> Void) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard current == ownership else { return false }
        current = nil
        action()
        return true
    }

    func invalidate() {
        lock.lock()
        current = nil
        lock.unlock()
    }

    func invalidate(ifCurrent ownership: SessionCallbackOwnership) {
        lock.lock()
        if current == ownership {
            current = nil
        }
        lock.unlock()
    }

    func isCurrent(_ ownership: SessionCallbackOwnership) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return current == ownership
    }
}

/// Multi-transfer replacement for the former single "last progress" slot.
/// Recency is retained only among transfers that are still incomplete.
struct ActiveTransferIndex {
    private var progressByFileId: [String: TransferProgress] = [:]
    private var recency: [String] = []

    mutating func record(_ progress: TransferProgress) {
        remove(fileId: progress.fileId)
        guard !progress.isComplete else { return }
        progressByFileId[progress.fileId] = progress
        recency.append(progress.fileId)
    }

    mutating func remove(fileId: String) {
        progressByFileId.removeValue(forKey: fileId)
        recency.removeAll { $0 == fileId }
    }

    mutating func removeAll() {
        progressByFileId.removeAll()
        recency.removeAll()
    }

    var preferredFileId: String? {
        recency.reversed().first { progressByFileId[$0] != nil }
    }
}

/// Mac side of the SAS pairing flow (HYP-M2-011, protocol v0 §5):
/// shows the QR (HYP-M2-009), accepts ONE provisional TLS connection —
/// any client certificate is admitted but its SPKI fingerprint is
/// captured (protocol §2: provisional until SAS confirmation) — then
/// displays the SAS for both-sides comparison. Trust is written through
/// `SasConfirmationGate` only when the user confirms; reject/close
/// persists nothing. Steady-state sessions and reconnect acceptance are
/// owned here (HYP-M2-012/013 product wiring).
final class PairingController: NSObject {

    private struct PendingSteadyConnection {
        let connection: NWConnection
        let generation: UInt64
        let peerFingerprint: Data
    }

    private let queue = DispatchQueue(label: "hyphen-pairing")
    private var listener: TLSEndpointListener?
    private var steadyListener: TrustedPeerReconnectListener?
    private var pairingAttemptID: UUID?
    // Section C pairing surface: the design SwiftUI window (QR + SAS) hosted in
    // a borderless window. Only the presentation changed here — the listener,
    // SAS gate, and session logic below are unchanged.
    private var pairingWindow: DesignWindowController?
    private let pairingModel = PairingWindowModel()
    private let provisionalLock = NSLock()
    private var provisional = ProvisionalPairingState()
    private var provisionalConnection: NWConnection?
    private var pairingConfirm: PairingWireProtocol.PairingConfirmExchange?
    private var activeSession: ProtocolSession?
    private var activeSessionToken: UUID?
    private var activeSessionOwnership: SessionCallbackOwnership?
    private let sessionCallbackGate = SessionCallbackOwnershipGate()
    private var activeCapabilities: SessionHandshake.NegotiatedCapabilities?
    private var activeTransferSender: TransferSender?
    private let tokenStore = ResumeTokenStore()
    private let transferCheckpointStore = TransferCheckpointStore()
    // Reconnect/trust state is touched from both `queue` (reconnect listener)
    // and the main thread (trust revoke); `reconnectLock` guards all of it.
    private let reconnectLock = NSRecursiveLock()
    private var rememberedPeerFingerprint: Data?
    private var rememberedDeviceName: String?
    private var rememberedListenPort: UInt16?
    private var stoppedForTrustChange = false
    private var reconnectSessionActive = false
    private var peerLinkGeneration: UInt64 = 0
    private var pendingSteadyConnections: [ObjectIdentifier: PendingSteadyConnection] = [:]
    private let textReceiver = TextLinkReceiver()
    private var activeTransfers = ActiveTransferIndex()
    private var notificationPrivacySyncTracker = NotificationPrivacySyncTracker()
    private let notificationPrivacyPolicySendQueue = NotificationPrivacyPolicySendQueue()
    private lazy var transferReceiver = TransferReceiver(
        storage: FileTransferStorage(
            root: FileManager.default.temporaryDirectory.appendingPathComponent(
                "hyphen-transfer",
                isDirectory: true
            ),
            isCheckpointActive: { [weak self] fileId in
                self?.transferCheckpointStore.load(fileId: fileId) != nil
            }
        ),
        checkpointStore: transferCheckpointStore,
        onRestored: { [weak self] progress in
            self?.recordIncomingTransferRestored(progress)
        },
        onProgress: { [weak self] progress in
            self?.recordIncomingTransferProgress(progress)
        }
    )
    private let notificationPresenter: InteractiveNotificationPresenter
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
        },
        onRemoveAll: { [weak self] in
            self?.emitActivity(.notificationsCleared(at: Date()))
        }
    )
    private lazy var notificationReceiver = NotificationMirrorReceiver(
        presenter: feedNotificationPresenter,
        replyActionsEnabled: { [weak self] in
            NotificationCapabilityGate.canPresentReplyActions(self?.activeCapabilities)
        },
        privacyPolicy: { [weak self] in
            self?.notificationPrivacyPolicy() ?? .allowAll
        },
        onPackageObserved: { [weak self] packageName in
            self?.onNotificationPackageObserved(packageName)
        }
    )
    private let diagnosticLogs: LocalStructuredLogStore
    private let peerLinkLifecycle: PeerLinkLifecycle
    private let reconnectListenerFactory: (String, SPKIPinVerifier) throws -> TrustedPeerReconnectListener
    private let steadyHandshakeResponder: SteadyHandshakeResponder
    private let onStatus: (String) -> Void
    private let onActivity: (ActivityEvent) -> Void
    private let notificationPrivacyPolicy: () -> NotificationPrivacyPolicy
    private let onNotificationPackageObserved: (String) -> Void
    private let onNotificationPrivacySync: (NotificationPrivacySyncState) -> Void

    init(
        diagnosticLogs: LocalStructuredLogStore = LocalStructuredLogStore(),
        peerLinkLifecycle: PeerLinkLifecycle = PersistentPeerLinkLifecycle(
            store: KeychainPeerLinkStore(),
            trustStore: KeychainTrustStore()
        ),
        notificationPresenter: InteractiveNotificationPresenter = UserNotificationCenterPresenter(),
        reconnectListenerFactory: @escaping (String, SPKIPinVerifier) throws -> TrustedPeerReconnectListener = {
            deviceName,
            verifier in
            let identity = try KeychainIdentityStore().loadOrCreate(commonName: deviceName)
            return TLSEndpointListener(identity: identity, verifier: verifier)
        },
        steadyHandshakeResponder: @escaping SteadyHandshakeResponder = {
            connection,
            device,
            peerFingerprint,
            tokenStore,
            tokenIssuance,
            queue,
            completion in
            SessionHandshake.respond(
                connection: connection,
                device: device,
                peerFingerprint: peerFingerprint,
                tokenStore: tokenStore,
                tokenIssuance: tokenIssuance,
                queue: queue,
                completion: completion
            )
        },
        notificationPrivacyPolicy: @escaping () -> NotificationPrivacyPolicy = { .allowAll },
        onNotificationPackageObserved: @escaping (String) -> Void = { _ in },
        onNotificationPrivacySync: @escaping (NotificationPrivacySyncState) -> Void = { _ in },
        onActivity: @escaping (ActivityEvent) -> Void = { _ in },
        onStatus: @escaping (String) -> Void
    ) {
        self.diagnosticLogs = diagnosticLogs
        self.peerLinkLifecycle = peerLinkLifecycle
        self.notificationPresenter = notificationPresenter
        self.reconnectListenerFactory = reconnectListenerFactory
        self.steadyHandshakeResponder = steadyHandshakeResponder
        self.notificationPrivacyPolicy = notificationPrivacyPolicy
        self.onNotificationPackageObserved = onNotificationPackageObserved
        self.onNotificationPrivacySync = onNotificationPrivacySync
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
                    self.reconnectLock.lock()
                    self.rememberedListenPort = port
                    self.reconnectLock.unlock()
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
                    PairingWireProtocol.runResponder(
                        connection: connection,
                        nonce: nonce,
                        macSpkiFingerprint: identity.spkiFingerprint,
                        expectedAndroidFingerprint: peerFingerprint,
                        queue: self.queue
                    ) { [weak self] result in
                        guard let self else { return }
                        switch result {
                        case .failure:
                            connection.cancel()
                            self.releaseProvisionalConnection(connection)
                            DispatchQueue.main.async {
                                self.onStatus("Pairing aborted: wire exchange failed")
                            }
                        case .success(let wire):
                            self.pairingConfirm = wire.confirm
                            DispatchQueue.main.async {
                                self.presentSasConfirmation(
                                    attemptID: attemptID,
                                    identity: identity,
                                    nonce: nonce,
                                    deviceName: deviceName,
                                    peerFingerprint: peerFingerprint,
                                    transcript: wire.transcript,
                                    peerDeviceName: wire.peerDevice.deviceName
                                )
                            }
                        }
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
        sessionCallbackGate.invalidate()
        listener?.stop()
        listener = nil
        reconnectLock.lock()
        let steady = steadyListener
        steadyListener = nil
        let pendingSteady = pendingSteadyConnections.values.map(\.connection)
        pendingSteadyConnections.removeAll()
        reconnectSessionActive = false
        reconnectLock.unlock()
        steady?.stop()
        pendingSteady.forEach { $0.cancel() }
        activeSession?.stop()
        activeSession = nil
        activeSessionToken = nil
        activeSessionOwnership = nil
        activeCapabilities = nil
        activeTransferSender?.recycleSession()
        activeTransferSender = nil
        transferReceiver.recycleSession()
        activeTransfers.removeAll()
        markNotificationPrivacyOffline()
        notificationPresenter.setDismissHandler(nil)
        notificationPresenter.setReplyHandler(nil)
        pairingAttemptID = nil
        pairingModel.awaitingConfirmation = false
        pairingConfirm = nil
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
        pairingModel.manualPairingPayload = manualPairingPayload(host: host, port: port, nonce: nonce, deviceName: deviceName)
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

    private func manualPairingPayload(host: String, port: UInt16, nonce: Data, deviceName: String) -> String {
        var uri = "hyphen://pair?v=0&ep=\(host):\(port)&n=\(nonce.hyphenBase64URL)"
        if let encoded = deviceName.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) {
            uri += "&dn=\(encoded)"
        }
        return uri
    }

    /// Wake/network reconnect entry: ensure a trusted-peer listener is accepting
    /// Android redials (HYP-M1-014/M2-013 product wiring).
    func requestReconnect() {
        queue.async { [weak self] in
            self?.startReconnectListenerIfNeeded()
        }
    }

    /// Restore the one v0 listener descriptor only after its fingerprint is
    /// revalidated against the current trust store. Starting the listener stays
    /// with the app delegate so Local Network onboarding remains the gate.
    @discardableResult
    func restorePersistedLink() -> Bool {
        reconnectLock.lock()
        let generation = peerLinkGeneration
        let alreadyOwned = rememberedPeerFingerprint != nil
            || steadyListener != nil
            || reconnectSessionActive
            || activeSession != nil
            || stoppedForTrustChange
        reconnectLock.unlock()
        guard !alreadyOwned else { return false }

        let restored: RestoredPeerLink
        do {
            guard let value = try peerLinkLifecycle.restoreTrusted() else { return false }
            restored = value
        } catch {
            recordPeerLinkFailure(code: "trust/peer-link-restore-failed", operation: "restore-link")
            onStatus("Trusted peer link could not be restored")
            return false
        }

        reconnectLock.lock()
        guard peerLinkGeneration == generation,
              !stoppedForTrustChange,
              rememberedPeerFingerprint == nil,
              steadyListener == nil,
              !reconnectSessionActive,
              activeSession == nil
        else {
            reconnectLock.unlock()
            return false
        }
        rememberedPeerFingerprint = restored.peer.spkiFingerprint
        rememberedDeviceName = restored.peer.displayName
        rememberedListenPort = restored.listenPort
        reconnectSessionActive = false
        reconnectLock.unlock()

        let name = restored.peer.displayName.isEmpty ? "Android device" : restored.peer.displayName
        emitActivity(.peerChanged(isPaired: true, peerName: name))
        emitActivity(.connectionStateChanged(.reconnecting, latencyMs: nil))
        onStatus("Restoring trusted peer link…")
        return true
    }

    func stopAfterTrustChange(invalidateTokensFor peerFingerprint: Data? = nil, invalidateAllTokens: Bool = false) {
        sessionCallbackGate.invalidate()
        reconnectLock.lock()
        let rememberedFingerprint = rememberedPeerFingerprint
        peerLinkGeneration &+= 1
        stoppedForTrustChange = true
        rememberedPeerFingerprint = nil
        rememberedDeviceName = nil
        rememberedListenPort = nil
        reconnectSessionActive = false
        let steady = steadyListener
        steadyListener = nil
        let pendingSteady = pendingSteadyConnections.values.map(\.connection)
        pendingSteadyConnections.removeAll()
        reconnectLock.unlock()
        steady?.stop()
        pendingSteady.forEach { $0.cancel() }
        do {
            try peerLinkLifecycle.invalidate()
        } catch {
            // Trust has already been revoked by the caller. Even if deletion
            // fails, restoreTrusted() cannot accept the stale fingerprint.
            recordPeerLinkFailure(code: "trust/peer-link-invalidate-failed", operation: "invalidate-link")
            onStatus("Trusted peer recovery state could not be removed")
        }
        if invalidateAllTokens {
            tokenStore.invalidateAll()
            transferReceiver.invalidateAllCheckpoints()
        } else if let fingerprint = peerFingerprint ?? rememberedFingerprint {
            tokenStore.invalidatePeer(fingerprint)
            transferCheckpointStore.invalidatePeer(fingerprint)
            transferReceiver.invalidatePeerCheckpoints(fingerprint)
        }
        activeSession?.stop()
        activeSession = nil
        activeSessionToken = nil
        activeSessionOwnership = nil
        activeCapabilities = nil
        activeTransferSender?.recycleSession()
        activeTransferSender = nil
        activeTransfers.removeAll()
        transferReceiver.recycleSession()
        markNotificationPrivacyOffline()
        notificationPresenter.setDismissHandler(nil)
        notificationPresenter.setReplyHandler(nil)
        onStatus("Session stopped after trust change")
        onActivity(.connectionStateChanged(.suspended, latencyMs: nil))
    }

    private func startReconnectListenerIfNeeded(
        expectedGeneration: UInt64? = nil,
        expectedPeerFingerprint: Data? = nil
    ) {
        reconnectLock.lock()
        let generation = peerLinkGeneration
        let stopped = stoppedForTrustChange
        let active = reconnectSessionActive
        let peerFp = rememberedPeerFingerprint
        let alreadyListening = steadyListener != nil
        let listenPort = rememberedListenPort ?? 0
        reconnectLock.unlock()
        guard expectedGeneration == nil || expectedGeneration == generation else { return }
        guard expectedPeerFingerprint == nil || expectedPeerFingerprint == peerFp else { return }
        guard !stopped, !active, let peerFingerprint = peerFp, !alreadyListening else { return }
        do {
            let deviceName = Host.current().localizedName ?? "Hyphen Mac"
            let verifier = SPKIPinVerifier(isTrusted: { $0 == peerFingerprint })
            let acceptor = try reconnectListenerFactory(deviceName, verifier)
            reconnectLock.lock()
            guard peerLinkGeneration == generation,
                  !stoppedForTrustChange,
                  !reconnectSessionActive,
                  rememberedPeerFingerprint == peerFingerprint,
                  steadyListener == nil
            else {
                reconnectLock.unlock()
                acceptor.stop()
                return
            }
            steadyListener = acceptor
            do {
                // Start while ownership is locked. Revocation either wins
                // before this point (the generation guard rejects us), or waits
                // and then takes/stops the installed listener.
                try acceptor.start(
                    port: listenPort,
                    queue: queue,
                    onState: { _ in },
                    onConnection: { [weak self] connection in
                        self?.acceptReconnect(
                            connection: connection,
                            deviceName: deviceName,
                            peerFingerprint: peerFingerprint,
                            generation: generation
                        )
                    },
                    onConnectionState: { _, state in
                        if case .failed = state { }
                    }
                )
                reconnectLock.unlock()
            } catch {
                steadyListener = nil
                reconnectLock.unlock()
                acceptor.stop()
                throw error
            }
            onStatus("Waiting for phone reconnect…")
        } catch {
            // Release the listener slot so a later wake can retry instead of
            // being blocked forever by the `steadyListener != nil` guard.
            onStatus("Reconnect listener failed: \(error)")
        }
    }

    private func acceptReconnect(
        connection: NWConnection,
        deviceName: String,
        peerFingerprint: Data,
        generation: UInt64
    ) {
        reconnectLock.lock()
        guard peerLinkGeneration == generation,
              !stoppedForTrustChange,
              rememberedPeerFingerprint == peerFingerprint
        else {
            reconnectLock.unlock()
            connection.cancel()
            return
        }
        let steady = steadyListener
        steadyListener = nil
        reconnectLock.unlock()
        steady?.stop()
        startSteadySession(
            connection: connection,
            deviceName: deviceName,
            peerFingerprint: peerFingerprint,
            generation: generation
        )
    }

    private func presentSasConfirmation(
        attemptID: UUID,
        identity: DeviceIdentity,
        nonce: Data,
        deviceName: String,
        peerFingerprint: Data,
        transcript: PairingTranscript,
        peerDeviceName: String?,
    ) {
        guard pairingAttemptID == attemptID, pairingWindow != nil else { return }
        guard let confirm = pairingConfirm else {
            onStatus("Pairing aborted: confirm channel missing")
            endPairing()
            return
        }
        let gate = SasConfirmationGate(
            transcript: transcript,
            peerFingerprint: peerFingerprint,
            peerDisplayName: peerDeviceName ?? "Android device",
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
        pairingModel.onConfirm = { [weak self, gate, confirm] in
            guard let self else { return }
            guard self.pairingModel.awaitingConfirmation else { return }
            self.pairingModel.awaitingConfirmation = false
            self.resetPairingActions()
            PairingCommit.finalize(gate: gate, confirm: confirm, localAccepted: true) { outcome in
                DispatchQueue.main.async {
                    switch outcome {
                    case .trusted:
                        let connection = self.takeProvisionalConnection()
                        let generation = self.beginPeerLinkOwnership(peerFingerprint: peerFingerprint)
                        self.pairingConfirm = nil
                        self.onStatus("Paired — fingerprint pinned (\(gate.sas))")
                        self.emitActivity(.pairingNote(message: L("pairing.note.paired"), at: Date()))
                        self.closePairingUI()
                        self.startSteadySession(
                            connection: connection,
                            deviceName: deviceName,
                            peerFingerprint: peerFingerprint,
                            generation: generation
                        )
                    case .rejected:
                        self.onStatus("Pairing rejected — nothing stored")
                        self.endPairing()
                    case .incomplete:
                        self.onStatus("Pairing incomplete — nothing stored")
                        self.endPairing()
                    }
                }
            }
        }
        pairingModel.onReject = { [weak self, gate, confirm] in
            guard let self else { return }
            guard self.pairingModel.awaitingConfirmation else { return }
            self.pairingModel.awaitingConfirmation = false
            self.resetPairingActions()
            PairingCommit.finalize(gate: gate, confirm: confirm, localAccepted: false) { _ in
                DispatchQueue.main.async {
                    self.onStatus("Pairing rejected — nothing stored")
                    self.endPairing()
                }
            }
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

    private func startSteadySession(
        connection: NWConnection?,
        deviceName: String,
        peerFingerprint: Data,
        generation: UInt64
    ) {
        guard let connection else {
            onStatus("Pairing completed but no TLS connection remained")
            return
        }
        let connectionID = ObjectIdentifier(connection)
        reconnectLock.lock()
        let ownershipIsCurrent = peerLinkGeneration == generation
            && !stoppedForTrustChange
            && rememberedPeerFingerprint == peerFingerprint
        let tokenIssuance = ownershipIsCurrent
            ? tokenStore.captureIssuance(for: peerFingerprint)
            : nil
        if ownershipIsCurrent {
            pendingSteadyConnections[connectionID] = PendingSteadyConnection(
                connection: connection,
                generation: generation,
                peerFingerprint: peerFingerprint
            )
        }
        reconnectLock.unlock()
        guard ownershipIsCurrent, let tokenIssuance else {
            connection.cancel()
            return
        }
        let device = SessionHandshake.DeviceInfo(
            kind: "macos",
            appVersion: HyphenCore.version,
            deviceName: deviceName
        )
        steadyHandshakeResponder(
            connection,
            device,
            peerFingerprint,
            tokenStore,
            tokenIssuance,
            queue
        ) { [weak self] result in
            guard let self else {
                connection.cancel()
                return
            }
            let handshakeStillOwned = self.finishPendingSteadyHandshake(
                connectionID: connectionID,
                generation: generation,
                peerFingerprint: peerFingerprint
            )
            switch result {
            case .failure(let error):
                connection.cancel()
                guard handshakeStillOwned else { return }
                DispatchQueue.main.async { self.onStatus("Session handshake failed: \(error)") }
                // acceptReconnect consumes the one steady listener before the
                // hello exchange. A transient malformed/dropped hello must
                // reopen the same generation's trusted port for Android's next
                // backoff dial. A stale failure must not open the new peer's
                // listener after re-pair or revocation.
                self.queue.async { [weak self] in
                    self?.startReconnectListenerIfNeeded(
                        expectedGeneration: generation,
                        expectedPeerFingerprint: peerFingerprint
                    )
                }
            case .success(let handshake):
                guard handshakeStillOwned else {
                    connection.cancel()
                    return
                }
                let sessionOwnership = SessionCallbackOwnership(
                    token: UUID(),
                    generation: generation,
                    peerFingerprint: peerFingerprint
                )
                var callbacks = ProtocolSession.Callbacks()
                callbacks.onEnvelope = { [weak self] envelope in
                    DispatchQueue.main.async {
                        guard let self else { return }
                        _ = self.withCurrentSession(sessionOwnership) {
                            self.handleSessionEnvelope(envelope)
                        }
                    }
                }
                callbacks.onAck = { [weak self] messageId in
                    DispatchQueue.main.async {
                        guard let self else { return }
                        _ = self.withCurrentSession(sessionOwnership) {
                            // Advances the outbound transfer window (chunk acks).
                            try? self.activeTransferSender?.handleAck(messageId)
                            self.handleNotificationPrivacySendResult(
                                self.notificationPrivacyPolicySendQueue.capacityAvailable()
                            )
                        }
                    }
                }
                callbacks.onAckTimeout = { [weak self] messageId in
                    DispatchQueue.main.async {
                        guard let self else { return }
                        _ = self.withCurrentSession(sessionOwnership) {
                            self.activeTransferSender?.handleAckTimeout(messageId)
                            self.handleNotificationPrivacySendResult(
                                self.notificationPrivacyPolicySendQueue.capacityAvailable()
                            )
                        }
                    }
                }
                callbacks.onClosed = { [weak self] in
                    DispatchQueue.main.async {
                        guard let self else { return }
                        let accepted = self.consumeCurrentSession(sessionOwnership) {
                            self.activeTransferSender?.recycleSession()
                            self.activeSession = nil
                            self.activeSessionToken = nil
                            self.activeSessionOwnership = nil
                            self.activeCapabilities = nil
                            self.activeTransferSender = nil
                            self.activeTransfers.removeAll()
                            self.transferReceiver.recycleSession()
                            self.notificationPresenter.setDismissHandler(nil)
                            self.notificationPresenter.setReplyHandler(nil)
                            self.reconnectSessionActive = false
                            self.markNotificationPrivacyOffline()
                        }
                        guard accepted else { return }
                        self.onStatus("Phone session closed")
                        self.onActivity(.connectionStateChanged(.suspended, latencyMs: nil))
                        self.queue.async { [weak self] in
                            self?.startReconnectListenerIfNeeded()
                        }
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
                    var redundantListener: TrustedPeerReconnectListener?
                    var redundantConnections: [NWConnection] = []
                    let installed = self.sessionCallbackGate.activate(
                        sessionOwnership,
                        ifAccepted: {
                            self.reconnectLock.lock()
                            defer { self.reconnectLock.unlock() }
                            guard self.peerLinkGeneration == generation,
                                  !self.stoppedForTrustChange,
                                  self.rememberedPeerFingerprint == peerFingerprint
                            else { return false }
                            redundantListener = self.steadyListener
                            self.steadyListener = nil
                            redundantConnections = self.pendingSteadyConnections.values.map(\.connection)
                            self.pendingSteadyConnections.removeAll()
                            self.activeTransferSender?.recycleSession()
                            self.activeSession?.stop()
                            self.activeSession = session
                            self.notificationPrivacyPolicySendQueue.activate(
                                outbox: ProtocolSessionNotificationDismissOutbox(session: session)
                            )
                            self.activeSessionToken = sessionOwnership.token
                            self.activeSessionOwnership = sessionOwnership
                            self.activeCapabilities = handshake.negotiatedCapabilities
                            self.activeTransferSender = TransferSender(
                                outbox: ProtocolSessionTransferOutbox(session: session),
                                negotiatedCapabilities: handshake.negotiatedCapabilities,
                                checkpointStore: self.transferCheckpointStore,
                                onRestored: { [weak self] progress in
                                    DispatchQueue.main.async {
                                        guard let self else { return }
                                        _ = self.withCurrentSession(sessionOwnership) {
                                            self.activeTransfers.record(progress)
                                            self.onStatus("transfer resume restored: \(progress.fileId)")
                                            self.onActivity(.transferResuming(
                                                progress,
                                                direction: .outgoing,
                                                at: Date()
                                            ))
                                        }
                                    }
                                },
                                onProgress: { [weak self] progress in
                                    DispatchQueue.main.async {
                                        guard let self else { return }
                                        _ = self.withCurrentSession(sessionOwnership) {
                                            self.activeTransfers.record(progress)
                                            self.onStatus(Self.transferProgressLine(progress))
                                            self.onActivity(.transferProgress(
                                                progress,
                                                direction: .outgoing,
                                                at: Date()
                                            ))
                                        }
                                    }
                                }
                            )
                            return true
                        }
                    )
                    redundantListener?.stop()
                    redundantConnections.forEach { $0.cancel() }
                    guard installed else {
                        session.stop()
                        return
                    }

                    let activated = self.withCurrentSession(sessionOwnership) {
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
                        let name = handshake.peerDeviceName ?? "Android device"
                        self.rememberedDeviceName = name
                        self.reconnectSessionActive = true
                        // Push the current per-app privacy policy so Android filters
                        // content source-side from the start of the session.
                        self.syncNotificationPrivacyPolicy()
                        self.activeTransferSender?.bindSession(
                            peerFingerprint: peerFingerprint,
                            sessionId: handshake.sessionId
                        )
                        // Receiver restoration emits resume requests immediately;
                        // bind the sender to this session before those callbacks.
                        let pendingDeliveries = self.transferReceiver.bindSession(
                            peerFingerprint: peerFingerprint,
                            sessionId: handshake.sessionId
                        )
                        pendingDeliveries.forEach { self.handleCompletedIncomingTransfer($0) }
                        self.onStatus("Connected to \(name)")
                        self.onActivity(.peerChanged(isPaired: true, peerName: name))
                        self.onActivity(.connectionStateChanged(.connected, latencyMs: nil))
                        self.onActivity(.pairingNote(
                            message: L("pairing.note.connected", name),
                            at: Date()
                        ))
                    }
                    if !activated {
                        session.stop()
                    }
                }
            }
        }
    }

    private func finishPendingSteadyHandshake(
        connectionID: ObjectIdentifier,
        generation: UInt64,
        peerFingerprint: Data
    ) -> Bool {
        reconnectLock.lock()
        defer { reconnectLock.unlock() }
        guard let pending = pendingSteadyConnections.removeValue(forKey: connectionID),
              pending.generation == generation,
              pending.peerFingerprint == peerFingerprint
        else { return false }
        return peerLinkGeneration == generation
            && !stoppedForTrustChange
            && rememberedPeerFingerprint == peerFingerprint
    }

    func beginPeerLinkOwnership(peerFingerprint: Data) -> UInt64 {
        sessionCallbackGate.invalidate()
        reconnectLock.lock()
        let previousListener = steadyListener
        steadyListener = nil
        let previousPendingConnections = pendingSteadyConnections.values.map(\.connection)
        pendingSteadyConnections.removeAll()
        let previousSession = activeSession
        activeSession = nil
        activeSessionToken = nil
        activeSessionOwnership = nil
        activeCapabilities = nil
        let previousTransferSender = activeTransferSender
        activeTransferSender = nil
        activeTransfers.removeAll()
        markNotificationPrivacyOffline()
        peerLinkGeneration &+= 1
        // Enter a generation-owned transition before replacing the durable
        // route. Old callbacks are already invalidated, while the new peer is
        // not published in memory until clear-before-save has been attempted.
        stoppedForTrustChange = true
        rememberedPeerFingerprint = nil
        rememberedDeviceName = nil
        reconnectSessionActive = false
        let listenPort = rememberedListenPort
        let generation = peerLinkGeneration
        var persistenceFailure: Error?
        do {
            if let listenPort {
                try peerLinkLifecycle.replaceCurrent(
                    listenPort: listenPort,
                    peerFingerprint: peerFingerprint
                )
            } else {
                // No new route can be written, but the old current descriptor
                // must still be retired before this ownership switch commits.
                try peerLinkLifecycle.invalidate()
                throw PeerLinkError.invalidPort
            }
        } catch {
            persistenceFailure = error
        }
        rememberedPeerFingerprint = peerFingerprint
        stoppedForTrustChange = false
        reconnectLock.unlock()
        previousListener?.stop()
        previousPendingConnections.forEach { $0.cancel() }
        previousSession?.stop()
        previousTransferSender?.recycleSession()
        transferReceiver.recycleSession()
        notificationPresenter.setDismissHandler(nil)
        notificationPresenter.setReplyHandler(nil)
        if persistenceFailure != nil {
            recordPeerLinkFailure(code: "trust/peer-link-persist-failed", operation: "replace-link")
            onStatus("Trusted peer link could not be saved for cold-start recovery")
        }
        return generation
    }

    @discardableResult
    private func withCurrentSession(
        _ ownership: SessionCallbackOwnership,
        action: () -> Void
    ) -> Bool {
        var accepted = false
        guard sessionCallbackGate.withCurrent(ownership, action: {
            reconnectLock.lock()
            defer { reconnectLock.unlock() }
            guard peerLinkGeneration == ownership.generation,
                  !stoppedForTrustChange,
                  rememberedPeerFingerprint == ownership.peerFingerprint,
                  activeSessionToken == ownership.token
            else { return }
            action()
            accepted = true
        }) else { return false }
        return accepted
    }

    @discardableResult
    private func consumeCurrentSession(
        _ ownership: SessionCallbackOwnership,
        action: () -> Void
    ) -> Bool {
        var accepted = false
        guard sessionCallbackGate.consumeCurrent(ownership, action: {
            reconnectLock.lock()
            defer { reconnectLock.unlock() }
            guard peerLinkGeneration == ownership.generation,
                  !stoppedForTrustChange,
                  rememberedPeerFingerprint == ownership.peerFingerprint,
                  activeSessionToken == ownership.token
            else { return }
            action()
            accepted = true
        }) else { return false }
        return accepted
    }

    private func recordIncomingTransferProgress(_ progress: TransferProgress) {
        recordIncomingTransfer(progress, restored: false)
    }

    private func recordIncomingTransferRestored(_ progress: TransferProgress) {
        recordIncomingTransfer(progress, restored: true)
    }

    private func recordIncomingTransfer(_ progress: TransferProgress, restored: Bool) {
        reconnectLock.lock()
        let ownership = activeSessionOwnership
        reconnectLock.unlock()
        guard let ownership else { return }
        let apply = { [weak self] in
            guard let self else { return }
            _ = self.withCurrentSession(ownership) {
                self.activeTransfers.record(progress)
                if restored {
                    self.onStatus("inbound transfer checkpoint restored: \(progress.fileId)")
                    self.onActivity(.transferResuming(progress, direction: .incoming, at: Date()))
                } else {
                    self.onStatus(Self.transferProgressLine(progress))
                    self.onActivity(.transferProgress(progress, direction: .incoming, at: Date()))
                }
            }
        }
        if Thread.isMainThread {
            apply()
        } else {
            DispatchQueue.main.async(execute: apply)
        }
    }

    private func recordPeerLinkFailure(code: String, operation: String) {
        _ = try? diagnosticLogs.recordFailure(
            code: code,
            component: "pairing-controller",
            operation: operation
        )
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
    func applyLocalNotificationPrivacyPolicy() {
        notificationReceiver.applyPrivacyPolicy(notificationPrivacyPolicy())
    }

    func syncNotificationPrivacyPolicy() {
        guard activeSession != nil else {
            markNotificationPrivacyOffline()
            return
        }
        guard activeCapabilities?.notificationPrivacyPolicyEnabled == true else {
            notificationPrivacyPolicySendQueue.retire()
            notificationPrivacySyncTracker.markUnsupported()
            onNotificationPrivacySync(notificationPrivacySyncTracker.state)
            return
        }
        handleNotificationPrivacySendResult(
            notificationPrivacyPolicySendQueue.offer(notificationPrivacyPolicy())
        )
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
                onStatus(Self.peerErrorLine(envelope))
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
                onStatus("unsupported capability reported: \(id ?? "session closed")")
                return
            }
            if let session = activeSession, !isNegotiatedCapability(envelope.capability) {
                let id = sendProtocolError(
                    session: session,
                    regarding: envelope,
                    code: "plugin/unsupported-capability",
                    message: "Capability is not negotiated for this session."
                )
                onStatus("unsupported capability reported: \(id ?? "session closed")")
                return
            }
            if let session = activeSession, !isNegotiatedNotificationOption(envelope.type) {
                let id = sendProtocolError(
                    session: session,
                    regarding: envelope,
                    code: "plugin/unsupported-capability",
                    message: "Notification action is not negotiated for this session."
                )
                onStatus("unsupported notification option reported: \(id ?? "session closed")")
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
                    onStatus("unsupported capability reported: \(id ?? "session closed")")
                    return
                }
            }
            if let action = try notificationReceiver.handle(envelope) {
                renderNotificationAction(action)
                return
            }
            if envelope.capability == TransferProtocol.capability {
                if envelope.type == TransferProtocol.typeResumeInfo {
                    let info = try TransferResumeInfo(payload: envelope.payload)
                    do {
                        try activeTransferSender?.handleResumeInfo(info)
                        onStatus("transfer resume continued: \(info.fileId)")
                    } catch {
                        onStatus("transfer resume info ignored: \(error)")
                    }
                    return
                }
                if envelope.type == TransferProtocol.typeCancel {
                    let cancel = try TransferCancel(payload: envelope.payload)
                    _ = activeTransferSender?.handleRemoteCancel(cancel)
                }
                switch try transferReceiver.handle(envelope) {
                case .completed(let completed):
                    handleCompletedIncomingTransfer(completed)
                case .resumeRequested(let info):
                    guard let sender = activeTransferSender else {
                        onStatus("transfer resume requested without active sender")
                        return
                    }
                    let id = sender.sendResumeInfo(info)
                    onStatus(
                        id.map { "transfer resume info sent: \($0)" }
                            ?? "transfer resume info queued: \(info.fileId)"
                    )
                case .cancelled(let cancel):
                    activeTransfers.remove(fileId: cancel.fileId)
                    onStatus("transfer cancelled: \(cancel.fileId)")
                    onActivity(.transferCancelled(fileId: cancel.fileId, at: Date()))
                case .ignored:
                    break
                }
                return
            }
            if let request = try textReceiver.handle(envelope) {
                presentTextLinkConfirmation(request)
                return
            }
            if Self.isPluginEnvelope(envelope), let session = activeSession {
                let id = sendProtocolError(
                    session: session,
                    regarding: envelope,
                    code: "protocol/unknown-type",
                    message: "No handler is registered for this message type."
                )
                onStatus("unknown type reported: \(id ?? "session closed")")
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
            onStatus("session envelope rejected: \(error)")
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
             NotificationMirrorProtocol.typeReplyResult,
             NotificationMirrorProtocol.typePrivacyResult:
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
        case .privacyPolicyApplied(let regarding, let localFloor):
            guard notificationPrivacySyncTracker.applyResult(
                regarding: regarding,
                localFloor: localFloor
            ) else {
                onStatus("stale notification privacy result ignored")
                return
            }
            onNotificationPrivacySync(notificationPrivacySyncTracker.state)
            onStatus("Android notification privacy policy applied; local floor: \(localFloor.rawValue)")
        }
    }

    private func markNotificationPrivacyOffline() {
        notificationPrivacyPolicySendQueue.retire()
        notificationPrivacySyncTracker.markOffline()
        onNotificationPrivacySync(notificationPrivacySyncTracker.state)
    }

    private func handleNotificationPrivacySendResult(_ result: NotificationPrivacyPolicySendResult) {
        switch result {
        case .idle:
            return
        case .queued:
            notificationPrivacySyncTracker.markQueued()
        case .sent(let messageId):
            notificationPrivacySyncTracker.markPending(messageId: messageId)
        }
        onNotificationPrivacySync(notificationPrivacySyncTracker.state)
    }

    func cancelActiveTransfer() {
        guard let fileId = activeTransfers.preferredFileId else {
            onStatus("transfer cancel: no active transfer")
            return
        }
        cancelTransfer(fileId: fileId)
    }

    func cancelTransfer(fileId: String) {
        let checkpoint = transferCheckpointStore.load(fileId: fileId)
        let senderOwnsTransfer = activeTransferSender?.containsTransfer(fileId: fileId) == true
        let inbound = checkpoint?.direction == .inbound || transferReceiver.pendingDelivery(fileId: fileId) != nil
        do {
            let id: String?
            if senderOwnsTransfer, let sender = activeTransferSender {
                id = try sender.cancel(fileId: fileId, discard: true)
            } else if inbound || checkpoint == nil {
                let cancelled = transferReceiver.cancelLocal(fileId: fileId, discard: true)
                guard cancelled || checkpoint != nil else {
                    onStatus("transfer cancel: unknown file \(fileId)")
                    return
                }
                id = try activeTransferSender?.sendCancel(TransferCancel(fileId: fileId, discard: true))
            } else {
                transferCheckpointStore.delete(fileId: fileId)
                id = try activeTransferSender?.sendCancel(TransferCancel(fileId: fileId, discard: true))
            }
            activeTransfers.remove(fileId: fileId)
            onStatus("transfer cancel \(id.map { "sent: \($0)" } ?? "recorded locally")")
            onActivity(.transferCancelled(fileId: fileId, at: Date()))
        } catch {
            onStatus("transfer cancel failed: \(error)")
        }
    }

    func retryIncomingTransferDelivery(fileId: String) {
        guard let completed = transferReceiver.pendingDelivery(fileId: fileId) else {
            onStatus("transfer save retry unavailable: \(fileId)")
            return
        }
        handleCompletedIncomingTransfer(completed)
    }

    private func deliverIncomingFile(_ completed: TransferCompleted) throws -> URL {
        guard let downloads = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first else {
            throw CocoaError(.fileNoSuchFile)
        }
        return try ReceivedFileDelivery(
            destinationDirectory: downloads,
            onSourceCleanupFailure: { [weak self] in
                self?.onStatus("transfer cleanup: verified source retained for age-based cleanup")
            }
        ).deliver(completed)
    }

    private func handleCompletedIncomingTransfer(_ completed: TransferCompleted) {
        // Delivery is local I/O, not a protocol fault. The receiver keeps the
        // verified checkpoint/source pending until this publication succeeds.
        do {
            let delivered = try deliverIncomingFile(completed)
            transferReceiver.markDeliverySucceeded(fileId: completed.manifest.fileId)
            activeTransfers.remove(fileId: completed.manifest.fileId)
            onStatus(
                "transfer received: \(completed.manifest.filename) " +
                    "(\(completed.manifest.sizeBytes) bytes) -> \(delivered.lastPathComponent)"
            )
            onActivity(.transferCompleted(
                fileId: completed.manifest.fileId,
                filename: completed.manifest.filename,
                sizeBytes: completed.manifest.sizeBytes,
                direction: .incoming,
                verified: true,
                at: Date()
            ))
        } catch {
            transferReceiver.markDeliveryFailed(fileId: completed.manifest.fileId)
            activeTransfers.remove(fileId: completed.manifest.fileId)
            onStatus("transfer received but save failed; verified source queued for retry: \(error)")
            onActivity(.transferDeliveryFailed(
                fileId: completed.manifest.fileId,
                filename: completed.manifest.filename,
                sizeBytes: completed.manifest.sizeBytes,
                at: Date()
            ))
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
