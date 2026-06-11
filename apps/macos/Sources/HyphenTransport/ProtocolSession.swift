import Foundation
import Network

/// Steady-state protocol session over an authenticated NWConnection
/// (HYP-M2-012, protocol v0 §4): heartbeats every interval, receive-
/// silence watchdog (degraded after 2 missed intervals), automatic acks
/// for `requiresAck` envelopes, ack-timeout detection, per-sender seq.
/// Hello/capability negotiation and reconnect/resume land with M2-013.
/// Twin of the Android `ProtocolSession`.
public final class ProtocolSession {

    public struct Config {
        /// Protocol §4 default 10 s; tests shrink it.
        public var heartbeatIntervalMs: Int64 = 10_000
        public var missThreshold: Int64 = 2
        public var ackTimeoutMs: Int64 = 10_000
        /// Test hook only: false simulates a protocol-violating silent peer.
        public var autoAck = true
        public init() {}
    }

    public struct Callbacks {
        /// Plugin/feature envelopes; core types (heartbeat/ack) stay internal.
        public var onEnvelope: (Envelope) -> Void = { _ in }
        public var onLiveness: (HeartbeatMonitor.State) -> Void = { _ in }
        public var onAckTimeout: (String) -> Void = { _ in }
        public var onProtocolError: (String, String) -> Void = { _, _ in }
        public var onClosed: () -> Void = {}
        public init() {}
    }

    private let connection: NWConnection
    private let sessionId: String
    private let config: Config
    private let callbacks: Callbacks
    private let queue: DispatchQueue
    private let queueKey = DispatchSpecificKey<Bool>()
    private let reader = FrameReader()
    private var monitor: HeartbeatMonitor!
    private var ackTracker: AckTracker!
    private var heartbeatTimer: DispatchSourceTimer?
    private var tickTimer: DispatchSourceTimer?
    private var seq: Int64 = 0
    private var closed = false

    /// - Parameter connection: an already-established (post-handshake)
    ///   connection from `TLSEndpointListener`/`TLSConnector`.
    public init(
        connection: NWConnection,
        sessionId: String,
        config: Config = Config(),
        callbacks: Callbacks
    ) {
        self.connection = connection
        self.sessionId = sessionId
        self.config = config
        self.callbacks = callbacks
        self.queue = DispatchQueue(label: "hyphen-session")
        queue.setSpecific(key: queueKey, value: true)
    }

    public func start() {
        queue.async { [self] in
            monitor = HeartbeatMonitor(
                intervalMs: config.heartbeatIntervalMs,
                missThreshold: config.missThreshold,
                startedAtMs: Self.nowMs(),
                onStateChange: callbacks.onLiveness
            )
            ackTracker = AckTracker(timeoutMs: config.ackTimeoutMs, onTimeout: callbacks.onAckTimeout)

            let heartbeat = DispatchSource.makeTimerSource(queue: queue)
            heartbeat.schedule(
                deadline: .now() + .milliseconds(Int(config.heartbeatIntervalMs)),
                repeating: .milliseconds(Int(config.heartbeatIntervalMs))
            )
            heartbeat.setEventHandler { [weak self] in
                self?.sendOnQueue(type: Envelope.typeHeartbeat)
            }
            heartbeat.resume()
            heartbeatTimer = heartbeat

            let tickMs = max(1, Int(config.heartbeatIntervalMs / 2))
            let tick = DispatchSource.makeTimerSource(queue: queue)
            tick.schedule(deadline: .now() + .milliseconds(tickMs), repeating: .milliseconds(tickMs))
            tick.setEventHandler { [weak self] in
                let now = Self.nowMs()
                self?.monitor.tick(nowMs: now)
                self?.ackTracker.tick(nowMs: now)
            }
            tick.resume()
            tickTimer = tick

            receiveLoop()
        }
    }

    /// Thread-safe; callable from any thread EXCEPT inside this session's
    /// own callbacks (which already run on the session queue).
    @discardableResult
    public func send(
        type: String,
        payload: [String: Any] = [:],
        requiresAck: Bool = false,
        capability: String? = nil,
        ackOf: String? = nil
    ) -> String? {
        if DispatchQueue.getSpecific(key: queueKey) == true {
            return sendOnQueue(type: type, payload: payload, requiresAck: requiresAck, capability: capability, ackOf: ackOf)
        }
        return queue.sync {
            sendOnQueue(type: type, payload: payload, requiresAck: requiresAck, capability: capability, ackOf: ackOf)
        }
    }

    public func stop() {
        queue.async { self.close() }
    }

    @discardableResult
    private func sendOnQueue(
        type: String,
        payload: [String: Any] = [:],
        requiresAck: Bool = false,
        capability: String? = nil,
        ackOf: String? = nil
    ) -> String? {
        guard !closed else { return nil }
        let now = Self.nowMs()
        seq += 1
        let envelope = Envelope(
            messageId: Ulid.generate(),
            sessionId: sessionId,
            type: type,
            capability: capability,
            seq: seq,
            ackOf: ackOf,
            sentAtUnixMs: now,
            requiresAck: requiresAck,
            payload: payload
        )
        do {
            let frame = try FrameCodec.encode(try envelope.encode())
            connection.send(content: frame, completion: .contentProcessed { [weak self] error in
                if error != nil { self?.close() }
            })
        } catch {
            callbacks.onProtocolError("protocol/invalid-envelope", "encode failed: \(error)")
            return nil
        }
        if requiresAck { ackTracker.registerSent(messageId: envelope.messageId, nowMs: now) }
        return envelope.messageId
    }

    private func receiveLoop() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { [weak self] data, _, isComplete, error in
            guard let self, !self.closed else { return }
            if let data, !data.isEmpty {
                do {
                    for frame in try self.reader.feed(data) {
                        self.handleFrame(frame)
                    }
                } catch {
                    self.callbacks.onProtocolError("transport/frame-too-large", "\(error)")
                    self.close()
                    return
                }
            }
            if isComplete || error != nil {
                self.close()
                return
            }
            self.receiveLoop()
        }
    }

    private func handleFrame(_ frame: Data) {
        let envelope: Envelope
        do {
            envelope = try Envelope.decode(frame)
        } catch {
            // Stay open per §3; malformed envelopes are surfaced and skipped.
            callbacks.onProtocolError("protocol/invalid-envelope", "\(error)")
            return
        }
        monitor.envelopeReceived(nowMs: Self.nowMs())
        switch envelope.type {
        case Envelope.typeAck:
            if let ackOf = envelope.ackOf { ackTracker.ackReceived(ackOf) }
        case Envelope.typeHeartbeat:
            break // liveness already recorded
        default:
            if envelope.requiresAck && config.autoAck {
                sendOnQueue(type: Envelope.typeAck, ackOf: envelope.messageId)
            }
            callbacks.onEnvelope(envelope)
        }
    }

    private func close() {
        guard !closed else { return }
        closed = true
        heartbeatTimer?.cancel()
        tickTimer?.cancel()
        connection.cancel()
        callbacks.onClosed()
    }

    private static func nowMs() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }
}
