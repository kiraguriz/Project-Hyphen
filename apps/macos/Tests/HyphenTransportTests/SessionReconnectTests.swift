import Network
import XCTest
@testable import HyphenPower
@testable import HyphenTransport

final class ResumeTokenStoreTests: XCTestCase {

    private let peerA = Data(repeating: 0xAA, count: 32)
    private let peerB = Data(repeating: 0xBB, count: 32)
    private var clock: Int64 = 0
    private lazy var store = ResumeTokenStore(nowMs: { self.clock })

    func testTokenRedeemsOnceForTheRightPeer() {
        let token = store.issue(sessionId: "s_one", peerFingerprint: peerA)
        XCTAssertNotNil(token.range(of: "^[A-Za-z0-9_-]{16,128}$", options: .regularExpression))
        XCTAssertEqual(store.redeem(token: token, peerFingerprint: peerA), "s_one")
        XCTAssertNil(store.redeem(token: token, peerFingerprint: peerA), "single-use")
    }

    func testExpiredTokenIsRefused() {
        let token = store.issue(sessionId: "s_one", peerFingerprint: peerA)
        clock = ResumeTokenStore.defaultTTLMs + 1
        XCTAssertNil(store.redeem(token: token, peerFingerprint: peerA))
    }

    func testWrongPeerIsRefusedAndTokenConsumed() {
        let token = store.issue(sessionId: "s_one", peerFingerprint: peerA)
        XCTAssertNil(store.redeem(token: token, peerFingerprint: peerB))
        XCTAssertNil(store.redeem(token: token, peerFingerprint: peerA), "consumed on the failed attempt")
    }

    func testReissueInvalidatesPreviousTokenForSession() {
        let first = store.issue(sessionId: "s_one", peerFingerprint: peerA)
        let second = store.issue(sessionId: "s_one", peerFingerprint: peerA)
        XCTAssertNotEqual(first, second)
        XCTAssertNil(store.redeem(token: first, peerFingerprint: peerA))
        XCTAssertEqual(store.redeem(token: second, peerFingerprint: peerA), "s_one")
    }

    func testTrustRevocationDropsEveryTokenForPeer() {
        _ = store.issue(sessionId: "s_one", peerFingerprint: peerA)
        _ = store.issue(sessionId: "s_two", peerFingerprint: peerB)
        store.invalidatePeer(peerA)
        XCTAssertEqual(store.liveCount, 1)
    }
}

/// HYP-M2-013 acceptance: reconnect after a simulated drop — over real
/// mutual-TLS loopback, the resume token round-trips through hello and
/// the SAME sessionId survives the drop.
final class SessionReconnectTests: XCTestCase {

    private var serverStore: KeychainIdentityStore!
    private var clientStore: KeychainIdentityStore!
    private var server: DeviceIdentity!
    private var client: DeviceIdentity!
    private var listener: TLSEndpointListener!
    private var reconnector: SessionReconnector?
    private var serverSessions: [ProtocolSession] = []
    private let lock = NSLock()
    private let serverQueue = DispatchQueue(label: "reconnect-test-server")
    private let clientQueue = DispatchQueue(label: "reconnect-test-client")

    private let macDevice = SessionHandshake.DeviceInfo(kind: "macos", appVersion: "0.0.1", deviceName: "Test Mac")
    private let phoneDevice = SessionHandshake.DeviceInfo(kind: "android", appVersion: "0.0.1", deviceName: "Test Phone")

    /// Fires retries immediately — backoff delays are pinned by the
    /// M1-014 state-machine tests; here we only need the walk to happen.
    private final class ImmediateScheduler: RetryScheduler {
        func schedule(after delay: TimeInterval, _ action: @escaping () -> Void) -> AnyObject {
            let item = DispatchWorkItem(block: action)
            DispatchQueue.global().async(execute: item)
            return item
        }

        func cancel(_ token: AnyObject) {
            (token as? DispatchWorkItem)?.cancel()
        }
    }

    override func setUpWithError() throws {
        try super.setUpWithError()
        serverStore = KeychainIdentityStore(label: "dev.hyphen.identity.test.\(UUID().uuidString)")
        clientStore = KeychainIdentityStore(label: "dev.hyphen.identity.test.\(UUID().uuidString)")
        server = try serverStore.loadOrCreate(commonName: "Server")
        client = try clientStore.loadOrCreate(commonName: "Client")
    }

    override func tearDown() {
        reconnector?.stop()
        reconnector = nil
        lock.lock()
        let sessions = serverSessions
        serverSessions = []
        lock.unlock()
        sessions.forEach { $0.stop() }
        listener?.stop()
        listener = nil
        try? serverStore.removeIdentity()
        try? clientStore.removeIdentity()
        super.tearDown()
    }

    /// Listener that handshakes + sessions every accepted connection.
    private func startResponder(tokenStore: ResumeTokenStore, config: ProtocolSession.Config) throws -> UInt16 {
        listener = TLSEndpointListener(
            identity: server,
            verifier: SPKIPinVerifier(expectedFingerprint: client.spkiFingerprint)
        )
        let listening = expectation(description: "listening")
        try listener.start(port: 0, queue: serverQueue, onState: { state in
            if case .listening = state { listening.fulfill() }
        }, onConnection: { [weak self] connection in
            guard let self else { return }
            SessionHandshake.respond(
                connection: connection,
                device: self.macDevice,
                peerFingerprint: self.client.spkiFingerprint,
                tokenStore: tokenStore,
                queue: self.serverQueue
            ) { result in
                guard case .success(let handshake) = result else { return }
                var serverConfig = config
                serverConfig.startingSeq = 1
                let session = ProtocolSession(
                    connection: connection,
                    sessionId: handshake.sessionId,
                    config: serverConfig,
                    callbacks: ProtocolSession.Callbacks()
                )
                self.lock.lock()
                self.serverSessions.append(session)
                self.lock.unlock()
                session.start(replaying: handshake.leftover)
            }
        })
        wait(for: [listening], timeout: 5)
        guard case .listening(let port) = listener.state else {
            throw XCTSkip("listener failed to report a port")
        }
        return port
    }

    func testReconnectAfterSimulatedDropResumesTheSameSession() throws {
        var config = ProtocolSession.Config()
        config.heartbeatIntervalMs = 100
        let port = try startResponder(tokenStore: ResumeTokenStore(), config: config)

        var results: [SessionHandshake.HandshakeResult] = []
        let firstSession = expectation(description: "first session")
        let secondSession = expectation(description: "second session")
        var callbacks = SessionReconnector.Callbacks()
        callbacks.onSession = { [self] _, handshake in
            lock.lock()
            results.append(handshake)
            let count = results.count
            lock.unlock()
            if count == 1 { firstSession.fulfill() }
            if count == 2 { secondSession.fulfill() }
        }
        reconnector = SessionReconnector(
            host: "127.0.0.1",
            port: port,
            identity: client,
            verifier: SPKIPinVerifier(expectedFingerprint: server.spkiFingerprint),
            device: phoneDevice,
            sessionConfig: config,
            scheduler: ImmediateScheduler(),
            queue: clientQueue,
            callbacks: callbacks
        )
        reconnector?.start()
        wait(for: [firstSession], timeout: 10)

        // Wait for the server-side session, then kill it (simulated drop).
        var serverSession: ProtocolSession?
        let deadline = Date().addingTimeInterval(5)
        while serverSession == nil && Date() < deadline {
            lock.lock()
            serverSession = serverSessions.first
            lock.unlock()
            if serverSession == nil { Thread.sleep(forTimeInterval: 0.01) }
        }
        try XCTUnwrap(serverSession).stop()

        wait(for: [secondSession], timeout: 10)
        lock.lock()
        let first = results[0]
        let second = results[1]
        lock.unlock()
        XCTAssertFalse(first.resumed)
        XCTAssertTrue(second.resumed, "second connect must resume")
        XCTAssertEqual(first.sessionId, second.sessionId, "sessionId must survive the drop")
        XCTAssertNotEqual(first.resumeToken, second.resumeToken, "resume tokens are single-use")
        XCTAssertEqual(first.peerDeviceName, "Test Mac")
    }

    func testFailedDialsWalkTheBackoffViaTheStateMachine() throws {
        // Dead port: POSIX bind-then-close so every dial fails.
        let deadPort: UInt16 = {
            let fd = socket(AF_INET, SOCK_STREAM, 0)
            defer { close(fd) }
            var addr = sockaddr_in()
            addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
            addr.sin_family = sa_family_t(AF_INET)
            addr.sin_port = 0
            addr.sin_addr = in_addr(s_addr: inet_addr("127.0.0.1"))
            _ = withUnsafePointer(to: &addr) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    Darwin.bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                }
            }
            var bound = sockaddr_in()
            var length = socklen_t(MemoryLayout<sockaddr_in>.size)
            _ = withUnsafeMutablePointer(to: &bound) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    getsockname(fd, $0, &length)
                }
            }
            return UInt16(bigEndian: bound.sin_port)
        }()

        var delays: [TimeInterval] = []
        let sawThirdRetry = expectation(description: "three retries scheduled")
        var callbacks = SessionReconnector.Callbacks()
        callbacks.onState = { [self] state in
            if case .waitingRetry(_, let delay) = state {
                lock.lock()
                delays.append(delay)
                let count = delays.count
                lock.unlock()
                if count == 3 { sawThirdRetry.fulfill() }
            }
        }
        reconnector = SessionReconnector(
            host: "127.0.0.1",
            port: deadPort,
            identity: client,
            verifier: SPKIPinVerifier(expectedFingerprint: server.spkiFingerprint),
            device: phoneDevice,
            scheduler: ImmediateScheduler(),
            queue: clientQueue,
            callbacks: callbacks
        )
        reconnector?.start()
        wait(for: [sawThirdRetry], timeout: 15)
        reconnector?.stop()

        lock.lock()
        let walk = Array(delays.prefix(3))
        lock.unlock()
        XCTAssertEqual(walk, [1, 5, 15], "backoff must walk the M1-014 schedule")
    }
}
