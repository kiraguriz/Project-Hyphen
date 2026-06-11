import Foundation
import Network
import Security

/// Network.framework TLS plumbing shared by listener and connector
/// (HYP-M2-007): TLS 1.3 floor, mutual authentication, and an
/// `SPKIPinVerifier` verify block on both sides. Framing and session
/// logic land with HYP-M2-012; this layer only produces authenticated
/// connections.
enum TLSEndpoint {

    static func parameters(
        identity: DeviceIdentity,
        verifier: SPKIPinVerifier,
        queue: DispatchQueue
    ) -> NWParameters {
        let tls = NWProtocolTLS.Options()
        let options = tls.securityProtocolOptions

        sec_protocol_options_set_min_tls_protocol_version(options, .TLSv13)

        if let secIdentity = sec_identity_create(identity.identity) {
            sec_protocol_options_set_local_identity(options, secIdentity)
            // Servers ask for the client certificate via the challenge
            // block; setting it on listeners too is harmless.
            sec_protocol_options_set_challenge_block(options, { _, complete in
                complete(secIdentity)
            }, queue)
        }

        // Both directions are mutually authenticated (protocol v0 §2).
        sec_protocol_options_set_peer_authentication_required(options, true)
        sec_protocol_options_set_verify_block(options, { _, secTrust, complete in
            let trust = sec_trust_copy_ref(secTrust).takeRetainedValue()
            complete(verifier.verify(trust: trust))
        }, queue)

        let parameters = NWParameters(tls: tls)
        parameters.includePeerToPeer = false // LAN only, no AWDL in v0
        return parameters
    }
}

/// Accepts pinned-peer TLS connections. Connections surface only after
/// the handshake (including the peer pin check) completes.
public final class TLSEndpointListener {

    public enum State: Equatable {
        case idle
        case listening(port: UInt16)
        case failed(String)
        case stopped
    }

    private let identity: DeviceIdentity
    private let verifier: SPKIPinVerifier
    private var listener: NWListener?

    public private(set) var state: State = .idle

    public init(identity: DeviceIdentity, verifier: SPKIPinVerifier) {
        self.identity = identity
        self.verifier = verifier
    }

    /// - Parameter port: 0 picks an ephemeral port; read it from `state`.
    public func start(
        port: UInt16,
        queue: DispatchQueue,
        onState: @escaping (State) -> Void,
        onConnection: @escaping (NWConnection) -> Void
    ) throws {
        let parameters = TLSEndpoint.parameters(identity: identity, verifier: verifier, queue: queue)
        let listener = try NWListener(
            using: parameters,
            on: port == 0 ? .any : NWEndpoint.Port(rawValue: port)!
        )
        self.listener = listener

        listener.stateUpdateHandler = { [weak self] newState in
            guard let self else { return }
            switch newState {
            case .ready:
                self.state = .listening(port: listener.port?.rawValue ?? 0)
            case .failed(let error):
                self.state = .failed(String(describing: error))
            case .cancelled:
                self.state = .stopped
            default:
                return
            }
            onState(self.state)
        }
        listener.newConnectionHandler = { connection in
            connection.stateUpdateHandler = { connectionState in
                switch connectionState {
                case .ready:
                    onConnection(connection)
                case .failed:
                    connection.cancel()
                default:
                    break
                }
            }
            connection.start(queue: queue)
        }
        listener.start(queue: queue)
    }

    public func stop() {
        listener?.cancel()
        listener = nil
    }
}

/// Dials a pinned peer. Exactly one completion: `.success` after the
/// authenticated handshake, `.failure` on handshake rejection or
/// transport error.
public enum TLSConnector {

    public static func connect(
        host: String,
        port: UInt16,
        identity: DeviceIdentity,
        verifier: SPKIPinVerifier,
        queue: DispatchQueue,
        completion: @escaping (Result<NWConnection, Error>) -> Void
    ) {
        let parameters = TLSEndpoint.parameters(identity: identity, verifier: verifier, queue: queue)
        let connection = NWConnection(
            host: NWEndpoint.Host(host),
            port: NWEndpoint.Port(rawValue: port)!,
            using: parameters
        )
        var completed = false
        connection.stateUpdateHandler = { state in
            guard !completed else { return }
            switch state {
            case .ready:
                completed = true
                completion(.success(connection))
            case .failed(let error):
                completed = true
                connection.cancel()
                completion(.failure(error))
            case .waiting(let error):
                // Network.framework parks failed TLS handshakes (incl. a
                // rejecting verify block) in .waiting and retries forever.
                // The connector is one-shot: retries belong to the
                // reconnect state machine (HYP-M1-014), and a pin mismatch
                // must surface immediately, not spin silently.
                completed = true
                connection.cancel()
                completion(.failure(error))
            default:
                break
            }
        }
        connection.start(queue: queue)
    }
}
