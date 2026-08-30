import Foundation
import XCTest
@testable import HyphenApp

final class SessionCallbackOwnershipGateTests: XCTestCase {
    private let fingerprint = Data(repeating: 0x44, count: 32)

    func testReplacementAndRevocationRejectLateCallbacks() {
        let gate = SessionCallbackOwnershipGate()
        let first = SessionCallbackOwnership(
            token: UUID(),
            generation: 1,
            peerFingerprint: fingerprint
        )
        let second = SessionCallbackOwnership(
            token: UUID(),
            generation: 2,
            peerFingerprint: fingerprint
        )
        var calls: [String] = []

        gate.activate(first)
        XCTAssertTrue(gate.withCurrent(first) { calls.append("first") })
        gate.activate(second)
        XCTAssertFalse(gate.withCurrent(first) { calls.append("stale") })
        XCTAssertTrue(gate.withCurrent(second) { calls.append("second") })
        gate.invalidate()
        XCTAssertFalse(gate.withCurrent(second) { calls.append("revoked") })

        XCTAssertEqual(calls, ["first", "second"])
    }

    func testRejectedActivationCannotDisplaceTheCurrentSessionTicket() {
        let gate = SessionCallbackOwnershipGate()
        let current = SessionCallbackOwnership(
            token: UUID(),
            generation: 3,
            peerFingerprint: fingerprint
        )
        let stale = SessionCallbackOwnership(
            token: UUID(),
            generation: 2,
            peerFingerprint: fingerprint
        )
        gate.activate(current)

        XCTAssertFalse(gate.activate(stale, ifAccepted: { false }))
        XCTAssertTrue(gate.withCurrent(current) {})
        XCTAssertFalse(gate.withCurrent(stale) {})
    }

    func testCloseConsumesOnlyTheCurrentSessionOnce() {
        let gate = SessionCallbackOwnershipGate()
        let ownership = SessionCallbackOwnership(
            token: UUID(),
            generation: 7,
            peerFingerprint: fingerprint
        )
        var closes = 0
        gate.activate(ownership)

        XCTAssertTrue(gate.consumeCurrent(ownership) { closes += 1 })
        XCTAssertFalse(gate.consumeCurrent(ownership) { closes += 1 })
        XCTAssertFalse(gate.withCurrent(ownership) {})
        XCTAssertEqual(closes, 1)
    }

    func testInvalidationWaitsForInFlightCallbackThenRejectsLaterWork() {
        let gate = SessionCallbackOwnershipGate()
        let ownership = SessionCallbackOwnership(
            token: UUID(),
            generation: 9,
            peerFingerprint: fingerprint
        )
        gate.activate(ownership)
        let callbackEntered = DispatchSemaphore(value: 0)
        let releaseCallback = DispatchSemaphore(value: 0)
        let callbackFinished = expectation(description: "callback finished")
        let invalidationFinished = DispatchSemaphore(value: 0)

        DispatchQueue.global().async {
            _ = gate.withCurrent(ownership) {
                callbackEntered.signal()
                _ = releaseCallback.wait(timeout: .now() + 2)
            }
            callbackFinished.fulfill()
        }
        XCTAssertEqual(callbackEntered.wait(timeout: .now() + 2), .success)
        DispatchQueue.global().async {
            gate.invalidate()
            invalidationFinished.signal()
        }
        XCTAssertEqual(
            invalidationFinished.wait(timeout: .now() + 0.1),
            .timedOut,
            "revoke must not overtake a callback already admitted by the ownership gate"
        )
        releaseCallback.signal()
        wait(for: [callbackFinished], timeout: 2)
        XCTAssertEqual(invalidationFinished.wait(timeout: .now() + 2), .success)

        XCTAssertFalse(gate.withCurrent(ownership) {})
    }
}
