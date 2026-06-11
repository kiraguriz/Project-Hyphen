import Network
import XCTest
@testable import HyphenTransport

/// Integration: two ProtocolSessions over real mutual-TLS loopback
/// (HYP-M2-012). Intervals are shrunk (80 ms) so degraded transitions
/// are observable in test time; semantics match protocol §4.
final class ProtocolSessionTests: XCTestCase {

    private var serverStore: KeychainIdentityStore!
    private var clientStore: KeychainIdentityStore!
    private var server: DeviceIdentity!
    private var client: DeviceIdentity!
    private var listener: TLSEndpointListener!
    private var sessions: [ProtocolSession] = []
    private let queue = DispatchQueue(label: "session-tests")

    private var fast: ProtocolSession.Config {
        var config = ProtocolSession.Config()
        config.heartbeatIntervalMs = 80
        config.ackTimeoutMs = 300
        return config
    }

    override func setUpWithError() throws {
        try super.setUpWithError()
        serverStore = KeychainIdentityStore(label: "dev.hyphen.identity.test.\(UUID().uuidString)")
        clientStore = KeychainIdentityStore(label: "dev.hyphen.identity.test.\(UUID().uuidString)")
        server = try serverStore.loadOrCreate(commonName: "Server")
        client = try clientStore.loadOrCreate(commonName: "Client")
    }

    override func tearDown() {
        sessionsLock.lock()
        let toStop = sessions
        sessions = []
        sessionsLock.unlock()
        toStop.forEach { $0.stop() }
        listener?.stop()
        listener = nil
        try? serverStore.removeIdentity()
        try? clientStore.removeIdentity()
        super.tearDown()
    }

    private final class Recorder {
        let degraded = XCTestExpectation(description: "degraded")
        let recovered = XCTestExpectation(description: "recovered")
        let envelopeReceived = XCTestExpectation(description: "envelope")
        let ackTimedOut = XCTestExpectation(description: "ack timeout")
        let protocolErrored = XCTestExpectation(description: "protocol error")
        var envelopes: [Envelope] = []
        var ackTimeoutId: String?
        var protocolErrorCode: String?
        private let lock = NSLock()

        var callbacks: ProtocolSession.Callbacks {
            var callbacks = ProtocolSession.Callbacks()
            callbacks.onEnvelope = { [self] envelope in
                lock.lock(); envelopes.append(envelope); lock.unlock()
                envelopeReceived.fulfill()
            }
            callbacks.onLiveness = { [self] state in
                switch state {
                case .degraded: degraded.fulfill()
                case .healthy: recovered.fulfill()
                }
            }
            callbacks.onAckTimeout = { [self] id in
                lock.lock(); ackTimeoutId = id; lock.unlock()
                ackTimedOut.fulfill()
            }
            callbacks.onProtocolError = { [self] code, _ in
                lock.lock(); protocolErrorCode = code; lock.unlock()
                protocolErrored.fulfill()
            }
            return callbacks
        }
    }

    private var listenPort: UInt16 = 0
    private let sessionsLock = NSLock()

    private func connectedPair(
        serverConfig: ProtocolSession.Config,
        clientConfig: ProtocolSession.Config,
        serverRecorder: Recorder,
        clientRecorder: Recorder
    ) throws -> (server: ProtocolSession, client: ProtocolSession) {
        var serverSession: ProtocolSession?
        let serverUp = expectation(description: "server session up")
        // Tests may open a second (hostile) connection later; every accepted
        // connection gets a server session, so over-fulfilling is expected.
        serverUp.assertForOverFulfill = false
        listener = TLSEndpointListener(
            identity: server,
            verifier: SPKIPinVerifier(expectedFingerprint: client.spkiFingerprint)
        )
        let listening = expectation(description: "listening")
        try listener.start(port: 0, queue: queue, onState: { state in
            if case .listening = state { listening.fulfill() }
        }, onConnection: { [weak self] connection in
            let session = ProtocolSession(
                connection: connection,
                sessionId: "s_test1",
                config: serverConfig,
                callbacks: serverRecorder.callbacks
            )
            if serverSession == nil { serverSession = session }
            if let self {
                self.sessionsLock.lock()
                self.sessions.append(session)
                self.sessionsLock.unlock()
            }
            session.start()
            serverUp.fulfill()
        })
        wait(for: [listening], timeout: 5)
        guard case .listening(let port) = listener.state else {
            throw XCTSkip("listener failed to report a port")
        }
        listenPort = port

        var clientSession: ProtocolSession?
        let connected = expectation(description: "client connected")
        TLSConnector.connect(
            host: "127.0.0.1",
            port: port,
            identity: client,
            verifier: SPKIPinVerifier(expectedFingerprint: server.spkiFingerprint),
            queue: queue
        ) { result in
            if case .success(let connection) = result {
                let session = ProtocolSession(
                    connection: connection,
                    sessionId: "s_test1",
                    config: clientConfig,
                    callbacks: clientRecorder.callbacks
                )
                clientSession = session
                session.start()
            }
            connected.fulfill()
        }
        wait(for: [connected, serverUp], timeout: 10)
        let pair = (try XCTUnwrap(serverSession), try XCTUnwrap(clientSession))
        sessionsLock.lock()
        sessions.append(pair.1)
        sessionsLock.unlock()
        return pair
    }

    func testHeartbeatsKeepBothSidesHealthy() throws {
        let serverRecorder = Recorder()
        let clientRecorder = Recorder()
        serverRecorder.degraded.isInverted = true
        clientRecorder.degraded.isInverted = true
        _ = try connectedPair(
            serverConfig: fast, clientConfig: fast,
            serverRecorder: serverRecorder, clientRecorder: clientRecorder
        )
        // 6+ intervals: any missing heartbeat flow would trip the watchdog.
        wait(for: [serverRecorder.degraded, clientRecorder.degraded], timeout: 0.5)
    }

    func testSilentPeerDegradesThenRecovers() throws {
        let serverRecorder = Recorder()
        let clientRecorder = Recorder()
        var silent = fast
        silent.heartbeatIntervalMs = 60_000 // never heartbeats in test time
        let pair = try connectedPair(
            serverConfig: silent, clientConfig: fast,
            serverRecorder: serverRecorder, clientRecorder: clientRecorder
        )
        wait(for: [clientRecorder.degraded], timeout: 3)
        // Any envelope from the server recovers the client.
        pair.server.send(type: Envelope.typeHeartbeat)
        wait(for: [clientRecorder.recovered], timeout: 3)
    }

    func testRequiresAckIsDeliveredAndAcked() throws {
        let serverRecorder = Recorder()
        let clientRecorder = Recorder()
        clientRecorder.ackTimedOut.isInverted = true
        let pair = try connectedPair(
            serverConfig: fast, clientConfig: fast,
            serverRecorder: serverRecorder, clientRecorder: clientRecorder
        )
        pair.client.send(type: "text.send", payload: ["kind": "text"], requiresAck: true, capability: "text.v1")
        wait(for: [serverRecorder.envelopeReceived], timeout: 3)
        XCTAssertEqual(serverRecorder.envelopes.first?.type, "text.send")
        // No timeout within 2x the ack window — the ack arrived.
        wait(for: [clientRecorder.ackTimedOut], timeout: 0.6)
    }

    func testMissingAckSurfacesAsAckTimeout() throws {
        let serverRecorder = Recorder()
        let clientRecorder = Recorder()
        var silentAcker = fast
        silentAcker.autoAck = false
        let pair = try connectedPair(
            serverConfig: silentAcker, clientConfig: fast,
            serverRecorder: serverRecorder, clientRecorder: clientRecorder
        )
        let messageId = pair.client.send(type: "text.send", requiresAck: true, capability: "text.v1")
        wait(for: [clientRecorder.ackTimedOut], timeout: 3)
        XCTAssertEqual(clientRecorder.ackTimeoutId, messageId)
    }

    func testOversizedFrameSurfacesFrameTooLarge() throws {
        let serverRecorder = Recorder()
        let clientRecorder = Recorder()
        _ = try connectedPair(
            serverConfig: fast, clientConfig: fast,
            serverRecorder: serverRecorder, clientRecorder: clientRecorder
        )
        // Hostile second connection writes a raw 5 MiB length header.
        let hostile = expectation(description: "hostile connected")
        TLSConnector.connect(
            host: "127.0.0.1",
            port: listenPort,
            identity: client,
            verifier: SPKIPinVerifier(expectedFingerprint: server.spkiFingerprint),
            queue: queue
        ) { result in
            if case .success(let connection) = result {
                let fiveMiB = UInt32(5 * 1024 * 1024)
                let header = Data([
                    UInt8(truncatingIfNeeded: fiveMiB >> 24),
                    UInt8(truncatingIfNeeded: fiveMiB >> 16),
                    UInt8(truncatingIfNeeded: fiveMiB >> 8),
                    UInt8(truncatingIfNeeded: fiveMiB),
                ])
                connection.send(content: header, completion: .contentProcessed { _ in })
            }
            hostile.fulfill()
        }
        wait(for: [hostile], timeout: 5)
        wait(for: [serverRecorder.protocolErrored], timeout: 3)
        XCTAssertEqual(serverRecorder.protocolErrorCode, "transport/frame-too-large")
    }
}
