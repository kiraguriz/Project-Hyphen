import Foundation
import HyphenPower
import Network

/// Client-side reconnect (HYP-M2-013): drives the M1-014
/// `ReconnectStateMachine` (1/5/15/30 s backoff, capped; success resets;
/// sleep/wake hooks available to the app) with real transport — dial →
/// hello handshake presenting the held resume token → ProtocolSession;
/// session loss feeds `connectionLost()` and the machine schedules the
/// retry. Everything runs on one serial queue.
public final class SessionReconnector {

    public struct Callbacks {
        /// A live session (fresh or resumed).
        public var onSession: (ProtocolSession, SessionHandshake.HandshakeResult) -> Void = { _, _ in }
        public var onState: (ReconnectState) -> Void = { _ in }
        public var onAttemptFailed: (String) -> Void = { _ in }
        /// Forwarded from the active session.
        public var onEnvelope: (Envelope) -> Void = { _ in }
        public var onLiveness: (HeartbeatMonitor.State) -> Void = { _ in }
        public init() {}
    }

    private let host: String
    private let port: UInt16
    private let identity: DeviceIdentity
    private let verifier: SPKIPinVerifier
    private let device: SessionHandshake.DeviceInfo
    private let sessionConfig: ProtocolSession.Config
    private let callbacks: Callbacks
    private let queue: DispatchQueue
    private var machine: ReconnectStateMachine!
    private var activeSession: ProtocolSession?
    private var resumeToken: String?
    private var lastSessionId: String?
    private var stopped = false

    public init(
        host: String,
        port: UInt16,
        identity: DeviceIdentity,
        verifier: SPKIPinVerifier,
        device: SessionHandshake.DeviceInfo,
        sessionConfig: ProtocolSession.Config = ProtocolSession.Config(),
        scheduler: RetryScheduler? = nil,
        queue: DispatchQueue = DispatchQueue(label: "hyphen-reconnector"),
        callbacks: Callbacks
    ) {
        self.host = host
        self.port = port
        self.identity = identity
        self.verifier = verifier
        self.device = device
        self.sessionConfig = sessionConfig
        self.callbacks = callbacks
        self.queue = queue
        self.machine = ReconnectStateMachine(
            scheduler: scheduler ?? DispatchRetryScheduler(queue: queue),
            startConnect: { [weak self] in self?.attempt() },
            onState: callbacks.onState
        )
    }

    public func start() {
        queue.async { self.machine.requestConnect() }
    }

    public func stop() {
        queue.async {
            self.stopped = true
            self.machine.suspend()
            self.activeSession?.stop()
            self.activeSession = nil
        }
    }

    /// Runs on `queue` (machine callbacks and retry timers live there).
    private func attempt() {
        guard !stopped else { return }
        TLSConnector.connect(
            host: host,
            port: port,
            identity: identity,
            verifier: verifier,
            queue: queue
        ) { [weak self] result in
            guard let self, !self.stopped else { return }
            switch result {
            case .failure(let error):
                self.callbacks.onAttemptFailed("\(error)")
                self.machine.connectionLost() // connecting → waitingRetry
            case .success(let connection):
                SessionHandshake.initiate(
                    connection: connection,
                    device: self.device,
                    resumeToken: self.resumeToken,
                    previousSessionId: self.lastSessionId,
                    queue: self.queue
                ) { handshakeResult in
                    guard !self.stopped else {
                        connection.cancel()
                        return
                    }
                    switch handshakeResult {
                    case .failure(let error):
                        connection.cancel()
                        self.callbacks.onAttemptFailed("\(error)")
                        self.machine.connectionLost()
                    case .success(let handshake):
                        self.establish(connection: connection, handshake: handshake)
                    }
                }
            }
        }
    }

    private func establish(connection: NWConnection, handshake: SessionHandshake.HandshakeResult) {
        // A presented token is spent either way (§4.6 single-use).
        resumeToken = handshake.resumeToken
        lastSessionId = handshake.sessionId

        var config = sessionConfig
        config.startingSeq = 1 // hello consumed seq 1 on this connection
        var sessionCallbacks = ProtocolSession.Callbacks()
        sessionCallbacks.onEnvelope = callbacks.onEnvelope
        sessionCallbacks.onLiveness = callbacks.onLiveness
        sessionCallbacks.onClosed = { [weak self] in
            guard let self else { return }
            self.queue.async {
                self.activeSession = nil
                if !self.stopped { self.machine.connectionLost() }
            }
        }
        let session = ProtocolSession(
            connection: connection,
            sessionId: handshake.sessionId,
            config: config,
            callbacks: sessionCallbacks
        )
        activeSession = session
        machine.connectionEstablished()
        session.start(replaying: handshake.leftover)
        callbacks.onSession(session, handshake)
    }
}
