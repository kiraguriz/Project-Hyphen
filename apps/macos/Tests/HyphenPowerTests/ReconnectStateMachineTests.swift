import XCTest
@testable import HyphenPower

private final class FakeScheduler: RetryScheduler {
    final class Token {
        let delay: TimeInterval
        let action: () -> Void
        var cancelled = false

        init(delay: TimeInterval, action: @escaping () -> Void) {
            self.delay = delay
            self.action = action
        }
    }

    var scheduled: [Token] = []

    func schedule(after delay: TimeInterval, _ action: @escaping () -> Void) -> AnyObject {
        let token = Token(delay: delay, action: action)
        scheduled.append(token)
        return token
    }

    func cancel(_ token: AnyObject) {
        (token as? Token)?.cancelled = true
    }

    /// Fires the most recent non-cancelled timer.
    func firePending() {
        guard let token = scheduled.last, !token.cancelled else { return }
        token.action()
    }

    var lastDelay: TimeInterval? { scheduled.last?.delay }
}

final class ReconnectStateMachineTests: XCTestCase {

    private let scheduler = FakeScheduler()
    private var connectAttempts = 0
    private var states: [ReconnectState] = []

    private func makeMachine() -> ReconnectStateMachine {
        ReconnectStateMachine(
            scheduler: scheduler,
            startConnect: { [self] in connectAttempts += 1 },
            onState: { [self] in states.append($0) }
        )
    }

    private func failAndRetry(_ machine: ReconnectStateMachine) {
        machine.connectionLost()
        scheduler.firePending()
    }

    func testBackoffScheduleIsExactlyTheRoadmapSchedule() {
        XCTAssertEqual(ReconnectStateMachine.backoffSchedule, [1, 5, 15, 30])
    }

    func testRequestConnectAttemptsImmediately() {
        let machine = makeMachine()
        machine.requestConnect()
        XCTAssertEqual(machine.state, .connecting)
        XCTAssertEqual(connectAttempts, 1)
    }

    func testRepeatedFailuresWalkTheScheduleAndCapAt30() {
        let machine = makeMachine()
        machine.requestConnect()

        var observedDelays: [TimeInterval] = []
        for _ in 0..<6 {
            machine.connectionLost()
            observedDelays.append(scheduler.lastDelay ?? -1)
            scheduler.firePending()
        }
        XCTAssertEqual(observedDelays, [1, 5, 15, 30, 30, 30])
        XCTAssertEqual(connectAttempts, 7) // initial + 6 retries
    }

    func testSuccessResetsTheSchedule() {
        let machine = makeMachine()
        machine.requestConnect()
        failAndRetry(machine)
        failAndRetry(machine)
        XCTAssertEqual(scheduler.lastDelay, 5)

        machine.connectionEstablished()
        XCTAssertEqual(machine.state, .connected)
        machine.connectionLost()
        XCTAssertEqual(scheduler.lastDelay, 1, "fresh schedule after success")
    }

    func testWakeStartsFreshScheduleFromOneSecond() {
        let machine = makeMachine()
        machine.requestConnect()
        failAndRetry(machine)
        failAndRetry(machine)
        failAndRetry(machine)
        XCTAssertEqual(scheduler.lastDelay, 15)

        machine.sleepOccurred()
        XCTAssertEqual(machine.state, .sleeping)

        machine.wakeOccurred()
        if case let .waitingRetry(attempt, delay) = machine.state {
            XCTAssertEqual(attempt, 0)
            XCTAssertEqual(delay, 1)
        } else {
            XCTFail("expected waitingRetry after wake, got \(machine.state)")
        }
        scheduler.firePending()
        XCTAssertEqual(machine.state, .connecting)
    }

    func testSleepCancelsPendingRetry() {
        let machine = makeMachine()
        machine.requestConnect()
        machine.connectionLost()
        let pending = scheduler.scheduled.last
        machine.sleepOccurred()
        XCTAssertEqual(pending?.cancelled, true)

        // A cancelled timer firing late must not connect from sleep.
        pending?.action()
        XCTAssertEqual(machine.state, .sleeping)
        XCTAssertEqual(connectAttempts, 1)
    }

    func testSuspendBlocksEverythingUntilResume() {
        let machine = makeMachine()
        machine.requestConnect()
        machine.suspend()
        XCTAssertEqual(machine.state, .suspended)

        machine.wakeOccurred()
        machine.connectionLost()
        machine.requestConnect()
        XCTAssertEqual(machine.state, .suspended)
        XCTAssertEqual(connectAttempts, 1)

        machine.resume()
        XCTAssertEqual(machine.state, .idle)
        machine.requestConnect()
        XCTAssertEqual(connectAttempts, 2)
    }

    func testLostWhileWaitingDoesNotStackTimers() {
        let machine = makeMachine()
        machine.requestConnect()
        machine.connectionLost()
        XCTAssertEqual(scheduler.scheduled.count, 1)
        machine.connectionLost() // already waiting: ignored
        machine.connectionLost()
        XCTAssertEqual(scheduler.scheduled.count, 1)
    }

    func testWakeWorksThroughTheReconnectTriggerProtocol() {
        let machine = makeMachine()
        let trigger: ReconnectTrigger = machine
        trigger.wakeOccurred()
        if case .waitingRetry = machine.state {
            // expected
        } else {
            XCTFail("trigger protocol must drive the machine")
        }
    }
}
