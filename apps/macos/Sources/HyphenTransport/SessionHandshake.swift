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
    public static let capabilityNotifications = "notifications.v1"
    public static let capabilityTransfer = "transfer.v1"
    public static let capabilityText = "text.v1"
    public static let capabilityDiagnostics = "diagnostics.v1"

    private static let defaultTransferMaxChunkBytes = 1_048_576
    private static let minTransferMaxChunkBytes = 1_024
    private static let maxTransferMaxChunkBytes = 2_097_152
    private static let deviceKinds: Set<String> = ["android", "macos"]
    private static let appVersionPattern = "^[0-9]+\\.[0-9]+\\.[0-9]+([\\-+][0-9A-Za-z.\\-]+)?$"
    private static let capabilityPattern = "^[a-z][a-z0-9]*\\.v[0-9]+$"

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
        public let negotiatedCapabilities: NegotiatedCapabilities
        /// Bytes the handshake read past the hello — the session must
        /// replay them before reading from the connection.
        public let leftover: Data
    }

    public struct NegotiatedCapabilities {
        private let entries: [String: [String: Any]]

        public init(_ entries: [String: [String: Any]]) {
            self.entries = entries
        }

        public func contains(_ capability: String) -> Bool {
            entries[capability] != nil
        }

        public var transferMaxChunkBytes: Int? {
            entries[capabilityTransfer]?["maxChunkBytes"] as? Int
        }

        public var wireObject: [String: Any] {
            entries
        }

        public func intersecting(_ peer: NegotiatedCapabilities) -> NegotiatedCapabilities {
            var result: [String: [String: Any]] = [:]
            if contains(capabilityNotifications), peer.contains(capabilityNotifications),
               let local = entries[capabilityNotifications],
               let remote = peer.entries[capabilityNotifications] {
                result[capabilityNotifications] = [
                    "reply": Self.minReply(
                        local["reply"] as? String ?? "off",
                        remote["reply"] as? String ?? "off"
                    ),
                    "dismiss": (local["dismiss"] as? Bool ?? false) && (remote["dismiss"] as? Bool ?? false),
                ]
            }
            if contains(capabilityTransfer), peer.contains(capabilityTransfer),
               let local = entries[capabilityTransfer],
               let remote = peer.entries[capabilityTransfer] {
                result[capabilityTransfer] = [
                    "resume": (local["resume"] as? Bool ?? false) && (remote["resume"] as? Bool ?? false),
                    "maxChunkBytes": min(
                        local["maxChunkBytes"] as? Int ?? defaultTransferMaxChunkBytes,
                        remote["maxChunkBytes"] as? Int ?? defaultTransferMaxChunkBytes
                    ),
                ]
            }
            if contains(capabilityText), peer.contains(capabilityText) {
                result[capabilityText] = ["direction": "bidirectional"]
            }
            if contains(capabilityDiagnostics), peer.contains(capabilityDiagnostics),
               let local = entries[capabilityDiagnostics],
               let remote = peer.entries[capabilityDiagnostics] {
                result[capabilityDiagnostics] = [
                    "redactedExport": (local["redactedExport"] as? Bool ?? false) && (remote["redactedExport"] as? Bool ?? false),
                ]
            }
            return NegotiatedCapabilities(result)
        }

        public static func advertised(maxTransferChunkBytes: Int = 1_048_576) throws -> NegotiatedCapabilities {
            guard (minTransferMaxChunkBytes...maxTransferMaxChunkBytes).contains(maxTransferChunkBytes) else {
                throw HandshakeError(code: "protocol/invalid-envelope", detail: "transfer maxChunkBytes out of range")
            }
            return NegotiatedCapabilities([
                capabilityNotifications: ["reply": "beta", "dismiss": true],
                capabilityTransfer: ["resume": true, "maxChunkBytes": maxTransferChunkBytes],
                capabilityText: ["direction": "bidirectional"],
                capabilityDiagnostics: ["redactedExport": true],
            ])
        }

        public static func empty() -> NegotiatedCapabilities {
            NegotiatedCapabilities([:])
        }

        private static func minReply(_ left: String, _ right: String) -> String {
            let rank = ["off": 0, "beta": 1, "on": 2]
            let minRank = min(rank[left] ?? 0, rank[right] ?? 0)
            return rank.first { $0.value == minRank }?.key ?? "off"
        }
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
                    let negotiatedCapabilities: NegotiatedCapabilities
                    do {
                        negotiatedCapabilities = try capabilities(of: reply.payload)
                            .intersecting(try NegotiatedCapabilities.advertised())
                    } catch {
                        return .failure(error)
                    }
                    return .success(HandshakeResult(
                        sessionId: sessionId,
                        resumeToken: (reply.payload["resumeToken"] as? String),
                        resumed: sessionId == previousSessionId,
                        peerDeviceName: deviceName(of: reply.payload),
                        negotiatedCapabilities: negotiatedCapabilities,
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
                let negotiatedCapabilities: NegotiatedCapabilities
                do {
                    negotiatedCapabilities = try NegotiatedCapabilities.advertised()
                        .intersecting(try capabilities(of: hello.payload))
                } catch {
                    completion(.failure(error))
                    return
                }
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
                    payload: helloPayload(device: device, resumeToken: nextToken, capabilities: negotiatedCapabilities)
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
                        negotiatedCapabilities: negotiatedCapabilities,
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
                        if let first = try reader.feedUntilFirst(data) {
                            let envelope = try Envelope.decode(first.frame)
                            guard envelope.type == Envelope.typeHello else {
                                throw HandshakeError(
                                    code: "protocol/invalid-envelope",
                                    detail: "expected hello, got \(envelope.type)"
                                )
                            }
                            try validateHelloPayload(envelope.payload)
                            finish(.success((envelope, first.leftover)))
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
        let unknownDevice = Set(device.keys).subtracting(["kind", "appVersion", "osVersion", "deviceName"])
        guard unknownDevice.isEmpty else {
            throw HandshakeError(code: "protocol/invalid-envelope", detail: "unknown hello.device fields: \(unknownDevice.sorted())")
        }
        guard let kind = device["kind"] as? String, deviceKinds.contains(kind) else {
            throw HandshakeError(code: "protocol/invalid-envelope", detail: "hello.device.kind invalid")
        }
        guard let appVersion = device["appVersion"] as? String,
              appVersion.range(of: appVersionPattern, options: .regularExpression) != nil
        else {
            throw HandshakeError(code: "protocol/invalid-envelope", detail: "hello.device.appVersion invalid")
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
        _ = try capabilities(of: payload)
    }

    private static func helloPayload(
        device: DeviceInfo,
        resumeToken: String?,
        capabilities: NegotiatedCapabilities = try! NegotiatedCapabilities.advertised()
    ) -> [String: Any] {
        var deviceDict: [String: Any] = [
            "kind": device.kind,
            "appVersion": device.appVersion,
        ]
        if let deviceName = device.deviceName { deviceDict["deviceName"] = deviceName }
        return [
            "device": deviceDict,
            "resumeToken": resumeToken ?? NSNull(),
            "capabilities": capabilities.wireObject,
        ]
    }

    private static func capabilities(of payload: [String: Any]) throws -> NegotiatedCapabilities {
        guard let raw = payload["capabilities"] as? [String: Any] else {
            throw HandshakeError(code: "protocol/invalid-envelope", detail: "hello.capabilities missing")
        }
        var result: [String: [String: Any]] = [:]
        for (name, value) in raw {
            guard name.range(of: capabilityPattern, options: .regularExpression) != nil else {
                throw HandshakeError(code: "protocol/invalid-envelope", detail: "bad capability '\(name)'")
            }
            guard let options = value as? [String: Any] else {
                throw HandshakeError(code: "protocol/invalid-envelope", detail: "capability '\(name)' options must be object")
            }
            try validateCapabilityOptions(name: name, options: options)
            result[name] = options
        }
        return NegotiatedCapabilities(result)
    }

    private static func validateCapabilityOptions(name: String, options: [String: Any]) throws {
        switch name {
        case capabilityNotifications:
            if let raw = options["reply"] {
                guard let reply = raw as? String else {
                    throw HandshakeError(code: "protocol/invalid-envelope", detail: "notifications.v1.reply must be string")
                }
                guard ["off", "beta", "on"].contains(reply) else {
                    throw HandshakeError(code: "protocol/invalid-envelope", detail: "notifications.v1.reply invalid")
                }
            }
            if let raw = options["dismiss"], !isBoolean(raw) {
                throw HandshakeError(code: "protocol/invalid-envelope", detail: "notifications.v1.dismiss must be boolean")
            }
        case capabilityTransfer:
            if let raw = options["resume"], !isBoolean(raw) {
                throw HandshakeError(code: "protocol/invalid-envelope", detail: "transfer.v1.resume must be boolean")
            }
            if let raw = options["maxChunkBytes"] {
                guard let max = raw as? Int else {
                    throw HandshakeError(code: "protocol/invalid-envelope", detail: "transfer.v1.maxChunkBytes must be integer")
                }
                guard (minTransferMaxChunkBytes...maxTransferMaxChunkBytes).contains(max) else {
                    throw HandshakeError(code: "protocol/invalid-envelope", detail: "transfer.v1.maxChunkBytes out of range")
                }
            }
        case capabilityText:
            if let raw = options["direction"] {
                guard let direction = raw as? String else {
                    throw HandshakeError(code: "protocol/invalid-envelope", detail: "text.v1.direction must be string")
                }
                guard ["bidirectional", "send-only", "receive-only"].contains(direction) else {
                    throw HandshakeError(code: "protocol/invalid-envelope", detail: "text.v1.direction invalid")
                }
            }
        case capabilityDiagnostics:
            if let raw = options["redactedExport"], !isBoolean(raw) {
                throw HandshakeError(code: "protocol/invalid-envelope", detail: "diagnostics.v1.redactedExport must be boolean")
            }
        default:
            break
        }
    }

    private static func deviceName(of payload: [String: Any]) -> String? {
        (payload["device"] as? [String: Any])?["deviceName"] as? String
    }

    private static func isBoolean(_ value: Any) -> Bool {
        CFGetTypeID(value as CFTypeRef) == CFBooleanGetTypeID()
    }
}
