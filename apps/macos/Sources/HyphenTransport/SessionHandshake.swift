import Foundation
import Network

/// Hello exchange over an authenticated NWConnection (HYP-M2-013,
/// protocol v0 §4): the initiator sends `hello` (envelope sessionId
/// null) and the responder replies `hello` carrying the assigned
/// sessionId in the envelope — the SAME id as before when a valid resume
/// token was presented (that equality IS the resume signal) — plus a
/// fresh single-use resume token for the next reconnect. Twin of the
/// Android `SessionHandshake`.
///
/// v0 notes: handshake hellos travel with requiresAck=false (the reply
/// is the acknowledgment; doc-sync in M2-015) and seq restarts per
/// connection (hello=1, the session continues at 2).
public enum SessionHandshake {

    public static let timeoutMs = 10_000

    public struct DeviceInfo {
        public let kind: String // "android" | "macos"
        public let appVersion: String
        public let deviceName: String?

        public init(kind: String, appVersion: String, deviceName: String? = nil) {
            self.kind = kind
            self.appVersion = appVersion
            self.deviceName = deviceName
        }
    }

    public struct HandshakeResult {
        public let sessionId: String
        /// Token to present on the NEXT reconnect (nil if none issued).
        public let resumeToken: String?
        public let resumed: Bool
        public let peerDeviceName: String?
        /// Bytes the handshake read past the hello — the session must
        /// replay them before reading from the connection.
        public let leftover: Data
    }

    public struct HandshakeError: Error, CustomStringConvertible {
        public let code: String
        public let detail: String
        public var description: String { "\(code): \(detail)" }
    }

    /// Client side. Completion fires exactly once on `queue`.
    public static func initiate(
        connection: NWConnection,
        device: DeviceInfo,
        resumeToken: String?,
        previousSessionId: String?,
        queue: DispatchQueue,
        completion: @escaping (Result<HandshakeResult, Error>) -> Void
    ) {
        let hello = Envelope(
            messageId: Ulid.generate(),
            sessionId: nil,
            type: Envelope.typeHello,
            seq: 1,
            sentAtUnixMs: Int64(Date().timeIntervalSince1970 * 1000),
            requiresAck: false,
            payload: helloPayload(device: device, resumeToken: resumeToken)
        )
        sendEnvelope(hello, over: connection) { sendError in
            if let sendError {
                completion(.failure(sendError))
                return
            }
            readHello(connection: connection, queue: queue) { result in
                completion(result.flatMap { reply, leftover in
                    guard let sessionId = reply.sessionId else {
                        return .failure(HandshakeError(
                            code: "protocol/invalid-envelope",
                            detail: "responder hello carried no sessionId"
                        ))
                    }
                    return .success(HandshakeResult(
                        sessionId: sessionId,
                        resumeToken: (reply.payload["resumeToken"] as? String),
                        resumed: sessionId == previousSessionId,
                        peerDeviceName: deviceName(of: reply.payload),
                        leftover: leftover
                    ))
                })
            }
        }
    }

    /// Server side: consumes the initiator's hello, assigns or resumes a
    /// session, replies. `peerFingerprint` comes from the listener's
    /// verify callback (it authenticated this connection).
    public static func respond(
        connection: NWConnection,
        device: DeviceInfo,
        peerFingerprint: Data,
        tokenStore: ResumeTokenStore,
        queue: DispatchQueue,
        completion: @escaping (Result<HandshakeResult, Error>) -> Void
    ) {
        readHello(connection: connection, queue: queue) { result in
            switch result {
            case .failure(let error):
                completion(.failure(error))
            case .success(let (hello, leftover)):
                let presented = hello.payload["resumeToken"] as? String
                let resumedSessionId = presented.flatMap {
                    tokenStore.redeem(token: $0, peerFingerprint: peerFingerprint)
                }
                let sessionId = resumedSessionId ?? "s_\(Ulid.generate())"
                let nextToken = tokenStore.issue(sessionId: sessionId, peerFingerprint: peerFingerprint)

                let reply = Envelope(
                    messageId: Ulid.generate(),
                    sessionId: sessionId,
                    type: Envelope.typeHello,
                    seq: 1,
                    sentAtUnixMs: Int64(Date().timeIntervalSince1970 * 1000),
                    requiresAck: false,
                    payload: helloPayload(device: device, resumeToken: nextToken)
                )
                sendEnvelope(reply, over: connection) { sendError in
                    if let sendError {
                        completion(.failure(sendError))
                        return
                    }
                    completion(.success(HandshakeResult(
                        sessionId: sessionId,
                        resumeToken: nextToken,
                        resumed: resumedSessionId != nil,
                        peerDeviceName: deviceName(of: hello.payload),
                        leftover: leftover
                    )))
                }
            }
        }
    }

    // MARK: - Internals

    private static func sendEnvelope(
        _ envelope: Envelope,
        over connection: NWConnection,
        completion: @escaping (Error?) -> Void
    ) {
        do {
            let frame = try FrameCodec.encode(try envelope.encode())
            connection.send(content: frame, completion: .contentProcessed { completion($0) })
        } catch {
            completion(error)
        }
    }

    private static func readHello(
        connection: NWConnection,
        queue: DispatchQueue,
        completion: @escaping (Result<(Envelope, Data), Error>) -> Void
    ) {
        let reader = FrameReader()
        var done = false
        let finish: (Result<(Envelope, Data), Error>) -> Void = { result in
            guard !done else { return }
            done = true
            completion(result)
        }
        queue.asyncAfter(deadline: .now() + .milliseconds(timeoutMs)) {
            finish(.failure(HandshakeError(code: "transport/connection-lost", detail: "hello timed out")))
        }

        func receiveNext() {
            connection.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { data, _, isComplete, error in
                guard !done else { return }
                if let data, !data.isEmpty {
                    do {
                        if let frame = try reader.feed(data).first {
                            let envelope = try Envelope.decode(frame)
                            guard envelope.type == Envelope.typeHello else {
                                throw HandshakeError(
                                    code: "protocol/invalid-envelope",
                                    detail: "expected hello, got \(envelope.type)"
                                )
                            }
                            try validateHelloPayload(envelope.payload)
                            finish(.success((envelope, reader.remainder())))
                            return
                        }
                    } catch {
                        finish(.failure(error))
                        return
                    }
                }
                if isComplete || error != nil {
                    finish(.failure(HandshakeError(
                        code: "transport/connection-lost",
                        detail: "peer closed before hello"
                    )))
                    return
                }
                receiveNext()
            }
        }
        receiveNext()
    }

    /// Structural check per capability.schema.json (strict top level).
    private static func validateHelloPayload(_ payload: [String: Any]) throws {
        let unknown = Set(payload.keys).subtracting(["device", "resumeToken", "capabilities"])
        guard unknown.isEmpty else {
            throw HandshakeError(code: "protocol/invalid-envelope", detail: "unknown hello fields: \(unknown.sorted())")
        }
        guard let device = payload["device"] as? [String: Any],
              device["kind"] is String, device["appVersion"] is String
        else {
            throw HandshakeError(code: "protocol/invalid-envelope", detail: "hello.device missing or incomplete")
        }
        if let token = payload["resumeToken"], !(token is NSNull), !(token is String) {
            throw HandshakeError(code: "protocol/invalid-envelope", detail: "hello.resumeToken must be string or null")
        }
        guard payload["resumeToken"] != nil else {
            throw HandshakeError(code: "protocol/invalid-envelope", detail: "hello.resumeToken missing")
        }
        guard payload["capabilities"] is [String: Any] else {
            throw HandshakeError(code: "protocol/invalid-envelope", detail: "hello.capabilities missing")
        }
    }

    private static func helloPayload(device: DeviceInfo, resumeToken: String?) -> [String: Any] {
        var deviceDict: [String: Any] = [
            "kind": device.kind,
            "appVersion": device.appVersion,
        ]
        if let deviceName = device.deviceName { deviceDict["deviceName"] = deviceName }
        return [
            "device": deviceDict,
            "resumeToken": resumeToken ?? NSNull(),
            // Capability families arrive with the M3 feature work.
            "capabilities": [String: Any](),
        ]
    }

    private static func deviceName(of payload: [String: Any]) -> String? {
        (payload["device"] as? [String: Any])?["deviceName"] as? String
    }
}
