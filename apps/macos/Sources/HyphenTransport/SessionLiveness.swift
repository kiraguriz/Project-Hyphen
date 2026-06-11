import Foundation

/// Receive-silence watchdog (protocol v0 §4, HYP-M2-012): degraded after
/// `missThreshold` heartbeat intervals with no envelope received. Any
/// received envelope recovers to healthy. Pure logic; time is injected.
/// Not thread-safe by itself — the session confines it to one queue.
public final class HeartbeatMonitor {

    public enum State: Equatable {
        case healthy
        case degraded
    }

    public private(set) var state: State = .healthy

    private let intervalMs: Int64
    private let missThreshold: Int64
    private var lastReceivedAtMs: Int64
    private let onStateChange: (State) -> Void

    public init(
        intervalMs: Int64,
        missThreshold: Int64 = 2,
        startedAtMs: Int64,
        onStateChange: @escaping (State) -> Void
    ) {
        self.intervalMs = intervalMs
        self.missThreshold = missThreshold
        self.lastReceivedAtMs = startedAtMs
        self.onStateChange = onStateChange
    }

    public func envelopeReceived(nowMs: Int64) {
        lastReceivedAtMs = nowMs
        if state == .degraded {
            state = .healthy
            onStateChange(state)
        }
    }

    public func tick(nowMs: Int64) {
        if state == .healthy, nowMs - lastReceivedAtMs > intervalMs * missThreshold {
            state = .degraded
            onStateChange(state)
        }
    }
}

/// Tracks sent `requiresAck` envelopes (protocol v0 §3): no ack within
/// `timeoutMs` is `protocol/ack-timeout`, fired exactly once per id.
public final class AckTracker {

    private var pending: [String: Int64] = [:]
    private let timeoutMs: Int64
    private let onTimeout: (String) -> Void

    public init(timeoutMs: Int64, onTimeout: @escaping (String) -> Void) {
        self.timeoutMs = timeoutMs
        self.onTimeout = onTimeout
    }

    public func registerSent(messageId: String, nowMs: Int64) {
        pending[messageId] = nowMs
    }

    @discardableResult
    public func ackReceived(_ ackOf: String) -> Bool {
        pending.removeValue(forKey: ackOf) != nil
    }

    public func tick(nowMs: Int64) {
        let expired = pending.filter { nowMs - $0.value > timeoutMs }.keys.sorted()
        for messageId in expired {
            pending.removeValue(forKey: messageId)
            onTimeout(messageId)
        }
    }

    public var pendingCount: Int { pending.count }
}
