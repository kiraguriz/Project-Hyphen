import Network
import XCTest
@testable import HyphenTransport

/// Real mutual-TLS handshakes over loopback: both sides present minted
/// identities and verify each other purely by SPKI pin.
final class TLSPinningTests: XCTestCase {

    private var serverStore: KeychainIdentityStore!
    private var clientStore: KeychainIdentityStore!
    private var server: DeviceIdentity!
    private var client: DeviceIdentity!
    private var listener: TLSEndpointListener!
    private let queue = DispatchQueue(label: "tls-pinning-tests")

    override func setUpWithError() throws {
        try super.setUpWithError()
        serverStore = KeychainIdentityStore(label: "dev.hyphen.identity.test.\(UUID().uuidString)")
        clientStore = KeychainIdentityStore(label: "dev.hyphen.identity.test.\(UUID().uuidString)")
        server = try serverStore.loadOrCreate(commonName: "Hyphen Test Mac")
        client = try clientStore.loadOrCreate(commonName: "Hyphen Test Android")
    }

    override func tearDown() {
        listener?.stop()
        listener = nil
        try? serverStore.removeIdentity()
        try? clientStore.removeIdentity()
        super.tearDown()
    }

    private func startListener(
        pinning verifier: SPKIPinVerifier,
        onConnection: @escaping (NWConnection) -> Void = { _ in }
    ) throws -> UInt16 {
        listener = TLSEndpointListener(identity: server, verifier: verifier)
        let ready = expectation(description: "listener ready")
        try listener.start(port: 0, queue: queue, onState: { state in
            if case .listening = state { ready.fulfill() }
        }, onConnection: onConnection)
        wait(for: [ready], timeout: 5)
        guard case .listening(let port) = listener.state else {
            XCTFail("listener not listening: \(listener.state)")
            return 0
        }
        return port
    }

    func testHandshakeAndEchoWithMatchingPins() throws {
        let port = try startListener(
            pinning: SPKIPinVerifier(expectedFingerprint: client.spkiFingerprint),
            onConnection: { connection in
                connection.receive(minimumIncompleteLength: 1, maximumLength: 16) { data, _, _, _ in
                    connection.send(content: data, completion: .contentProcessed { _ in })
                }
            }
        )

        let echoed = expectation(description: "byte echoed back over mutual TLS")
        TLSConnector.connect(
            host: "127.0.0.1",
            port: port,
            identity: client,
            verifier: SPKIPinVerifier(expectedFingerprint: server.spkiFingerprint),
            queue: queue
        ) { result in
            switch result {
            case .failure(let error):
                XCTFail("handshake failed: \(error)")
            case .success(let connection):
                connection.send(content: Data("h".utf8), completion: .contentProcessed { _ in })
                connection.receive(minimumIncompleteLength: 1, maximumLength: 16) { data, _, _, _ in
                    XCTAssertEqual(data, Data("h".utf8))
                    connection.cancel()
                    echoed.fulfill()
                }
            }
        }
        wait(for: [echoed], timeout: 10)
    }

    func testClientRejectsWrongServerPin() throws {
        let port = try startListener(
            pinning: SPKIPinVerifier(expectedFingerprint: client.spkiFingerprint)
        )

        let failed = expectation(description: "client rejects unpinned server")
        TLSConnector.connect(
            host: "127.0.0.1",
            port: port,
            identity: client,
            verifier: SPKIPinVerifier(expectedFingerprint: Data(repeating: 0xEE, count: 32)),
            queue: queue
        ) { result in
            if case .success = result {
                XCTFail("handshake must fail when the server key is not the pinned one")
            }
            failed.fulfill()
        }
        wait(for: [failed], timeout: 10)
    }

    func testServerRejectsWrongClientPin() throws {
        let accepted = expectation(description: "server must not surface an unpinned client")
        accepted.isInverted = true
        let port = try startListener(
            pinning: SPKIPinVerifier(expectedFingerprint: Data(repeating: 0xEE, count: 32)),
            onConnection: { _ in accepted.fulfill() }
        )

        let settled = expectation(description: "client side settles")
        TLSConnector.connect(
            host: "127.0.0.1",
            port: port,
            identity: client,
            verifier: SPKIPinVerifier(expectedFingerprint: server.spkiFingerprint),
            queue: queue
        ) { result in
            // TLS 1.3 lets the client believe the handshake finished before
            // the server has judged its certificate, so .success here is
            // legal — the guarantee under test is that the SERVER never
            // hands an unpinned connection to the app.
            if case .success(let connection) = result { connection.cancel() }
            settled.fulfill()
        }
        wait(for: [settled], timeout: 10)
        wait(for: [accepted], timeout: 2)
    }

    func testTrustStoreShapedCallbackPins() throws {
        // The app-side shape: verifier consults a lookup, not a constant.
        let trusted = [client.spkiFingerprint]
        let port = try startListener(
            pinning: SPKIPinVerifier(isTrusted: { trusted.contains($0) })
        )

        let connected = expectation(description: "lookup-backed pin accepts")
        TLSConnector.connect(
            host: "127.0.0.1",
            port: port,
            identity: client,
            verifier: SPKIPinVerifier(isTrusted: { [self] in $0 == server.spkiFingerprint }),
            queue: queue
        ) { result in
            if case .failure(let error) = result {
                XCTFail("handshake failed: \(error)")
            } else if case .success(let connection) = result {
                connection.cancel()
            }
            connected.fulfill()
        }
        wait(for: [connected], timeout: 10)
    }
}
