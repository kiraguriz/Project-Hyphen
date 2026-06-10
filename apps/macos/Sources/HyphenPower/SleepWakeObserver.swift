import AppKit

/// Power transitions Hyphen reacts to (plan §8.4 state machine inputs).
public enum PowerEvent: Equatable {
    case willSleep
    case didWake
}

/// Seam for HYP-M1-014's reconnect state machine; the wake observer only
/// signals, it never owns reconnect policy.
public protocol ReconnectTrigger: AnyObject {
    func wakeOccurred()
}

/// Observes `NSWorkspace` sleep/wake notifications (HYP-M1-013).
/// The `NotificationCenter` is injected so tests can post the very same
/// notification names the platform posts; production uses
/// `NSWorkspace.shared.notificationCenter` (the only center that carries
/// these notifications).
public final class SleepWakeObserver {

    private let center: NotificationCenter
    private let onEvent: (PowerEvent) -> Void
    private weak var reconnect: ReconnectTrigger?
    private var tokens: [NSObjectProtocol] = []

    /// Local-only ordered event record (diagnostics; never transmitted).
    public private(set) var eventLog: [PowerEvent] = []

    public init(
        center: NotificationCenter = NSWorkspace.shared.notificationCenter,
        reconnect: ReconnectTrigger? = nil,
        onEvent: @escaping (PowerEvent) -> Void
    ) {
        self.center = center
        self.reconnect = reconnect
        self.onEvent = onEvent
    }

    public func start() {
        guard tokens.isEmpty else { return }
        tokens.append(
            center.addObserver(
                forName: NSWorkspace.willSleepNotification, object: nil, queue: .main
            ) { [weak self] _ in self?.handle(.willSleep) }
        )
        tokens.append(
            center.addObserver(
                forName: NSWorkspace.didWakeNotification, object: nil, queue: .main
            ) { [weak self] _ in self?.handle(.didWake) }
        )
    }

    public func stop() {
        tokens.forEach(center.removeObserver(_:))
        tokens = []
    }

    private func handle(_ event: PowerEvent) {
        eventLog.append(event)
        onEvent(event)
        if event == .didWake {
            reconnect?.wakeOccurred()
        }
    }
}
