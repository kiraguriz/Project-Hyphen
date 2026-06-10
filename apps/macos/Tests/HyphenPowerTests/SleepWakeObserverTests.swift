import AppKit
import XCTest
@testable import HyphenPower

private final class FakeTrigger: ReconnectTrigger {
    var wakes = 0
    func wakeOccurred() {
        wakes += 1
    }
}

final class SleepWakeObserverTests: XCTestCase {

    private let center = NotificationCenter()
    private var events: [PowerEvent] = []
    private let trigger = FakeTrigger()

    private func makeObserver() -> SleepWakeObserver {
        SleepWakeObserver(center: center, reconnect: trigger) { [self] in events.append($0) }
    }

    private func post(_ name: Notification.Name) {
        center.post(name: name, object: nil)
        // Observers use queue: .main; posting on the main thread in tests
        // delivers synchronously, so no waiting is needed.
    }

    func testSleepAndWakeAreLoggedInOrder() {
        let observer = makeObserver()
        observer.start()
        post(NSWorkspace.willSleepNotification)
        post(NSWorkspace.didWakeNotification)
        XCTAssertEqual(events, [.willSleep, .didWake])
        XCTAssertEqual(observer.eventLog, [.willSleep, .didWake])
    }

    func testWakeStartsExactlyOneReconnectAttemptPerWake() {
        // The observer must stay alive across the posts: handlers capture
        // it weakly, so a released observer silently drops events.
        let observer = makeObserver()
        withExtendedLifetime(observer) {
            observer.start()
            post(NSWorkspace.didWakeNotification)
            XCTAssertEqual(trigger.wakes, 1)
            post(NSWorkspace.didWakeNotification)
            XCTAssertEqual(trigger.wakes, 2)
        }
    }

    func testSleepDoesNotTriggerReconnect() {
        let observer = makeObserver()
        withExtendedLifetime(observer) {
            observer.start()
            post(NSWorkspace.willSleepNotification)
            XCTAssertEqual(trigger.wakes, 0)
            XCTAssertEqual(observer.eventLog, [.willSleep])
        }
    }

    func testStopSilencesNotifications() {
        let observer = makeObserver()
        observer.start()
        observer.stop()
        post(NSWorkspace.didWakeNotification)
        XCTAssertTrue(events.isEmpty)
        XCTAssertEqual(trigger.wakes, 0)
    }

    func testDoubleStartDoesNotDuplicateObservers()
    {
        let observer = makeObserver()
        observer.start()
        observer.start()
        post(NSWorkspace.didWakeNotification)
        XCTAssertEqual(events, [.didWake])
        XCTAssertEqual(trigger.wakes, 1)
    }
}
