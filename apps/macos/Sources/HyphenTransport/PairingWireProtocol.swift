import Foundation
import HyphenCore
import Network

/// Wire-level pairing state machine (protocol v0 §5.2, security review M-01).
public enum PairingWireProtocol {

    public static let typeRequest = "pair.request"
    public static let typeChallenge = "pair.challenge"
    public static let typeResponse = "pair.response"
    public static let typeConfirm = "pair.confirm"
    public static let errorSasRejected = "trust/sas-rejected"
    public static let pairingTimeoutMs = 10_000

    public struct PairingError: Error, Equatable {
        public let code: String
        public let detail: String
    }

    public struct WireDeviceInfo: Equatable {
        public let kind: String
        public let appVersion: String
        public let deviceName: String?

        public init(kind: String, appVersion: String, deviceName: String? = nil) {
            self.kind = kind
            self.appVersion = appVersion
            self.deviceName = deviceName
        }
    }

    public struct ResponderResult {
        public let transcript: PairingTranscript
        public let peerDevice: WireDeviceInfo
        public let confirm: PairingConfirmExchange
    }

    /// Mac responder: consumes `pair.request`, emits challenge/response, then
    /// hands off a confirm exchange for the SAS UI phase.
    public static func runResponder(
        connection: NWConnection,
        nonce: Data,
        macSpkiFingerprint: Data,
        expectedAndroidFingerprint: Data,
        protocolVersion: String = HyphenCore.protocolVersion,
        queue: DispatchQueue,
        completion: @escaping (Result<ResponderResult, Error>) -> Void
    ) {
        readEnvelope(connection: connection, queue: queue) { result in
            switch result {
            case .failure(let error):
                completion(.failure(error))
            case .success(let request):
                guard request.type == typeRequest else {
                    completion(.failure(PairingError(code: "protocol/unknown-type", detail: "expected \(typeRequest)")))
                    return
                }
                do {
                    let requestNonce = try requireBytes(request.payload, key: "nonce", length: PairingQRPayload.nonceLength)
                    guard requestNonce == nonce else {
                        throw PairingError(code: errorSasRejected, detail: "pairing nonce mismatch")
                    }
                    let androidFp = try requireBytes(request.payload, key: "androidSpkiFp", length: PairingQRPayload.fingerprintLength)
                    guard androidFp == expectedAndroidFingerprint else {
                        throw PairingError(code: errorSasRejected, detail: "android fingerprint mismatch")
                    }
                    let peerDevice = try requireDevice(request.payload)
                    guard let transcript = PairingTranscript(
                        nonce: nonce,
                        macSpkiFingerprint: macSpkiFingerprint,
                        androidSpkiFingerprint: androidFp,
                        protocolVersion: protocolVersion
                    ) else {
                        throw PairingError(code: "protocol/invalid-envelope", detail: "transcript inputs invalid")
                    }

                    var seq: Int64 = 1
                    let challenge = envelope(
                        seq: seq,
                        type: typeChallenge,
                        payload: ["transcriptHash": b64(transcript.hash)]
                    )
                    seq += 1
                    sendEnvelope(challenge, over: connection) { sendError in
                        if let sendError {
                            completion(.failure(sendError))
                            return
                        }
                        readEnvelope(connection: connection, queue: queue) { responseResult in
                            switch responseResult {
                            case .failure(let error):
                                completion(.failure(error))
                            case .success(let response):
                                guard response.type == typeResponse else {
                                    completion(.failure(PairingError(code: "protocol/unknown-type", detail: "expected \(typeResponse)")))
                                    return
                                }
                                do {
                                    let responseHash = try requireBytes(response.payload, key: "transcriptHash", length: PairingQRPayload.fingerprintLength)
                                    guard responseHash == transcript.hash else {
                                        throw PairingError(code: errorSasRejected, detail: "response transcriptHash mismatch")
                                    }
                                    let confirm = PairingConfirmExchange(
                                        connection: connection,
                                        queue: queue,
                                        nextSeq: seq
                                    )
                                    completion(.success(ResponderResult(
                                        transcript: transcript,
                                        peerDevice: peerDevice,
                                        confirm: confirm
                                    )))
                                } catch {
                                    completion(.failure(error))
                                }
                            }
                        }
                    }
                } catch {
                    completion(.failure(error))
                }
            }
        }
    }

    /// Bilateral `pair.confirm` exchange on an open pairing connection.
    public final class PairingConfirmExchange {
        private let connection: NWConnection
        private let queue: DispatchQueue
        private var seq: Int64
        private let lock = NSLock()
        private var localAccepted: Bool?
        private var remoteAccepted: Bool?
        private var dead = false

        fileprivate init(connection: NWConnection, queue: DispatchQueue, nextSeq: Int64) {
            self.connection = connection
            self.queue = queue
            self.seq = nextSeq
        }

        public var bothAccepted: Bool {
            lock.lock()
            defer { lock.unlock() }
            return localAccepted == true && remoteAccepted == true
        }

        public var isDead: Bool {
            lock.lock()
            defer { lock.unlock() }
            return dead || localAccepted == false || remoteAccepted == false
        }

        public func submitLocalDecision(_ accepted: Bool) {
            lock.lock()
            if dead || localAccepted != nil {
                lock.unlock()
                return
            }
            localAccepted = accepted
            if !accepted { dead = true }
            let nextSeq = seq
            seq += 1
            lock.unlock()
            let envelope = PairingWireProtocol.envelope(
                seq: nextSeq,
                type: PairingWireProtocol.typeConfirm,
                payload: ["accepted": accepted]
            )
            sendEnvelope(envelope, over: connection) { _ in }
        }

        public func awaitRemoteConfirm(completion: @escaping (Bool?) -> Void) {
            readEnvelope(connection: connection, queue: queue) { result in
                switch result {
                case .failure:
                    self.lock.lock()
                    self.dead = true
                    self.lock.unlock()
                    completion(nil)
                case .success(let envelope):
                    guard envelope.type == PairingWireProtocol.typeConfirm else {
                        self.lock.lock()
                        self.dead = true
                        self.lock.unlock()
                        completion(nil)
                        return
                    }
                    let accepted = envelope.payload["accepted"] as? Bool ?? false
                    self.lock.lock()
                    self.remoteAccepted = accepted
                    if !accepted { self.dead = true }
                    self.lock.unlock()
                    completion(accepted)
                }
            }
        }
    }

    // MARK: - Internals

    fileprivate static func envelope(seq: Int64, type: String, payload: [String: Any]) -> Envelope {
        Envelope(
            messageId: Ulid.generate(),
            sessionId: nil,
            type: type,
            seq: seq,
            sentAtUnixMs: Int64(Date().timeIntervalSince1970 * 1000),
            requiresAck: false,
            payload: payload
        )
    }

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

    private static func readEnvelope(
        connection: NWConnection,
        queue: DispatchQueue,
        completion: @escaping (Result<Envelope, Error>) -> Void
    ) {
        let reader = FrameReader()
        var done = false
        let finish: (Result<Envelope, Error>) -> Void = { result in
            guard !done else { return }
            done = true
            completion(result)
        }
        queue.asyncAfter(deadline: .now() + .milliseconds(pairingTimeoutMs)) {
            finish(.failure(PairingError(code: "transport/connection-lost", detail: "pairing timed out")))
        }

        func receiveNext() {
            connection.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { data, _, isComplete, error in
                guard !done else { return }
                if let error {
                    finish(.failure(error))
                    return
                }
                if let data, !data.isEmpty {
                    do {
                        if let first = try reader.feedUntilFirst(data) {
                            let envelope = try Envelope.decode(first.frame)
                            finish(.success(envelope))
                            return
                        }
                    } catch {
                        finish(.failure(error))
                        return
                    }
                }
                if isComplete {
                    finish(.failure(PairingError(code: "transport/connection-lost", detail: "pairing stream closed")))
                    return
                }
                receiveNext()
            }
        }
        receiveNext()
    }

    private static func requireDevice(_ payload: [String: Any]) throws -> WireDeviceInfo {
        guard let device = payload["device"] as? [String: Any],
              let kind = device["kind"] as? String,
              let appVersion = device["appVersion"] as? String else {
            throw PairingError(code: "protocol/invalid-envelope", detail: "device missing")
        }
        return WireDeviceInfo(kind: kind, appVersion: appVersion, deviceName: device["deviceName"] as? String)
    }

    private static func requireBytes(_ payload: [String: Any], key: String, length: Int) throws -> Data {
        guard let encoded = payload[key] as? String else {
            throw PairingError(code: "protocol/invalid-envelope", detail: "\(key) missing")
        }
        let padded = encoded
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        var data = Data(base64Encoded: padded)
            ?? Data(base64Encoded: padded + String(repeating: "=", count: (4 - padded.count % 4) % 4))
        guard let bytes = data, bytes.count == length else {
            throw PairingError(code: "protocol/invalid-envelope", detail: "\(key) wrong length")
        }
        return bytes
    }

    private static func b64(_ bytes: Data) -> String {
        bytes.base64EncodedString()
    }
}
