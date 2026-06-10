import XCTest
@testable import HyphenDiscovery

private final class MemoryStore: OnboardingStateStore {
    var values = [String: Bool]()

    func bool(forKey key: String) -> Bool {
        values[key] ?? false
    }

    func set(_ value: Bool, forKey key: String) {
        values[key] = value
    }
}

final class LocalNetworkOnboardingTests: XCTestCase {

    private var store = MemoryStore()
    private var actions = 0
    private var presentations = 0

    override func setUp() {
        super.setUp()
        store = MemoryStore()
        actions = 0
        presentations = 0
    }

    private func run(gate: LocalNetworkOnboardingGate, decision: Bool?) {
        gate.run(action: { self.actions += 1 }) { complete in
            self.presentations += 1
            if let decision {
                complete(decision)
            }
        }
    }

    func testAlreadyExplainedRunsImmediatelyWithoutPresenting() {
        store.set(true, forKey: LocalNetworkOnboardingGate.explainedKey)
        run(gate: LocalNetworkOnboardingGate(store: store), decision: nil)
        XCTAssertEqual(actions, 1)
        XCTAssertEqual(presentations, 0)
    }

    func testAcceptPersistsAndRunsAction() {
        let gate = LocalNetworkOnboardingGate(store: store)
        run(gate: gate, decision: true)
        XCTAssertEqual(actions, 1)
        XCTAssertEqual(presentations, 1)
        XCTAssertTrue(store.bool(forKey: LocalNetworkOnboardingGate.explainedKey))

        // Second attempt: no re-explanation.
        run(gate: gate, decision: nil)
        XCTAssertEqual(actions, 2)
        XCTAssertEqual(presentations, 1)
    }

    func testDeclineRunsNothingAndPersistsNothing() {
        let gate = LocalNetworkOnboardingGate(store: store)
        run(gate: gate, decision: false)
        XCTAssertEqual(actions, 0)
        XCTAssertFalse(store.bool(forKey: LocalNetworkOnboardingGate.explainedKey))

        // Explanation reappears on the next attempt.
        run(gate: gate, decision: false)
        XCTAssertEqual(presentations, 2)
        XCTAssertEqual(actions, 0)
    }

    func testCopyKeepsItsPrivacyPromises() {
        // Regression guard: the copy must keep the fallback and the
        // recovery path; docs/copy/macos-local-network-onboarding.md is
        // the canonical source.
        XCTAssertTrue(LocalNetworkCopy.body.contains("QR"))
        XCTAssertTrue(LocalNetworkCopy.body.contains("System Settings"))
        XCTAssertTrue(LocalNetworkCopy.body.contains("never scans the internet"))
        XCTAssertFalse(LocalNetworkCopy.body.contains("telemetry"))
    }
}
