import Foundation

/// Timer seam so the backoff schedule is unit-testable.
public protocol RetryScheduler {
    func schedule(after delay: TimeInterval, _ action: @escaping () -> Void) -> AnyObject
    func cancel(_ token: AnyObject)
}

public final class DispatchRetryScheduler: RetryScheduler {
    private let queue: DispatchQueue

    public init(queue: DispatchQueue = .main) {
        self.queue = queue
    }

    public func schedule(after delay: TimeInterval, _ action: @escaping () -> Void) -> AnyObject {
        let item = DispatchWorkItem(block: action)
        queue.asyncAfter(deadline: .now() + delay, execute: item)
        return item
    }

    public func cancel(_ token: AnyObject) {
        (token as? DispatchWorkItem)?.cancel()
    }
}

public enum ReconnectState: Equatable {
    case idle
    case connecting
    case connected
    case waitingRetry(attempt: Int, delay: TimeInterval)
    case sleeping
    case suspended
}

/// Reconnect prototype (HYP-M1-014, plan §8.4). Owns *when* to attempt a
/// connection; *how* is the injected `startConnect` (M2 transport).
///
/// Rules from the plan:
/// - retry backoff walks 1s → 5s → 15s → 30s, then repeats at 30s
/// - wake starts a fresh schedule (first recovery attempt at 1s)
/// - success resets the schedule; sleep/suspend cancel pending retries
public final class ReconnectStateMachine: ReconnectTrigger {

    /// The roadmap-required schedule (acceptance criterion of HYP-M1-014).
    public static let backoffSchedule: [TimeInterval] = [1, 5, 15, 30]

    private let scheduler: RetryScheduler
    private let startConnect: () -> Void
    private let onState: (ReconnectState) -> Void

    private var retryToken: AnyObject?
    private var attempt = 0

    public private(set) var state: ReconnectState = .idle {
        didSet { if state != oldValue { onState(state) } }
    }

    public init(
        scheduler: RetryScheduler,
        startConnect: @escaping () -> Void,
        onState: @escaping (ReconnectState) -> Void = { _ in }
    ) {
        self.scheduler = scheduler
        self.startConnect = startConnect
        self.onState = onState
    }

    // MARK: - Events

    /// User or discovery asked for a connection now.
    public func requestConnect() {
        guard state != .suspended, state != .sleeping else { return }
        cancelPendingRetry()
        attempt = 0
        beginAttempt()
    }

    public func connectionEstablished() {
        guard state != .suspended else { return }
        cancelPendingRetry()
        attempt = 0
        state = .connected
    }

    /// Connection dropped or heartbeat declared the session dead.
    public func connectionLost() {
        switch state {
        case .connected, .connecting:
            scheduleRetry()
        case .idle, .waitingRetry, .sleeping, .suspended:
            break
        }
    }

    /// Mac is about to sleep: stop retrying; wake starts a fresh schedule.
    public func sleepOccurred() {
        guard state != .suspended else { return }
        cancelPendingRetry()
        state = .sleeping
    }

    /// `ReconnectTrigger` (fired by `SleepWakeObserver`): recovery attempts
    /// at 1/5/15/30s after wake, starting from a clean slate.
    public func wakeOccurred() {
        guard state != .suspended else { return }
        cancelPendingRetry()
        attempt = 0
        scheduleRetry()
    }

    /// User disabled the peer.
    public func suspend() {
        cancelPendingRetry()
        state = .suspended
    }

    public func resume() {
        guard state == .suspended else { return }
        state = .idle
    }

    // MARK: - Internals

    private func beginAttempt() {
        state = .connecting
        startConnect()
    }

    private func scheduleRetry() {
        let delay = Self.backoffSchedule[min(attempt, Self.backoffSchedule.count - 1)]
        state = .waitingRetry(attempt: attempt, delay: delay)
        attempt += 1
        retryToken = scheduler.schedule(after: delay) { [weak self] in
            guard let self, case .waitingRetry = self.state else { return }
            self.retryToken = nil
            self.beginAttempt()
        }
    }

    private func cancelPendingRetry() {
        if let token = retryToken {
            scheduler.cancel(token)
            retryToken = nil
        }
    }
}
