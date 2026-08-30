import XCTest
import HyphenCore
import HyphenNotifications
import HyphenTransport
import Network
@testable import HyphenApp

private final class FakePeerLinkLifecycle: PeerLinkLifecycle {
    var restored: RestoredPeerLink?
    var restoreError: Error?
    var invalidations = 0
    var beforeRestore: (() -> Void)?
    var remembered: [(listenPort: UInt16, peerFingerprint: Data)] = []

    func remember(listenPort: UInt16, peerFingerprint: Data) throws {
        remembered.append((listenPort, peerFingerprint))
    }

    func restoreTrusted() throws -> RestoredPeerLink? {
        beforeRestore?()
        if let restoreError { throw restoreError }
        return restored
    }

    func invalidate() throws {
        invalidations += 1
    }
}

private final class RecordingReconnectListener: TrustedPeerReconnectListener {
    private let lock = NSLock()
    private(set) var startCalls = 0
    private(set) var stopCalls = 0
    var onStart: (() -> Void)?
    let stopped = DispatchSemaphore(value: 0)
    let started = DispatchSemaphore(value: 0)
    private var connectionHandler: ((NWConnection) -> Void)?

    func start(
        port: UInt16,
        queue: DispatchQueue,
        onState: @escaping (TLSEndpointListener.State) -> Void,
        onConnection: @escaping (NWConnection) -> Void,
        onConnectionState: ((NWConnection, NWConnection.State) -> Void)?
    ) throws {
        lock.lock()
        startCalls += 1
        connectionHandler = onConnection
        lock.unlock()
        started.signal()
        onStart?()
    }

    func stop() {
        lock.lock()
        stopCalls += 1
        lock.unlock()
        stopped.signal()
    }

    func counts() -> (starts: Int, stops: Int) {
        lock.lock()
        defer { lock.unlock() }
        return (startCalls, stopCalls)
    }

    func deliver(_ connection: NWConnection) {
        lock.lock()
        let handler = connectionHandler
        lock.unlock()
        handler?(connection)
    }
}

private final class LockedHandshakeCompletion {
    private let lock = NSLock()
    private var value: ((Result<SessionHandshake.HandshakeResult, Error>) -> Void)?

    func set(_ completion: @escaping (Result<SessionHandshake.HandshakeResult, Error>) -> Void) {
        lock.lock()
        value = completion
        lock.unlock()
    }

    func take() -> ((Result<SessionHandshake.HandshakeResult, Error>) -> Void)? {
        lock.lock()
        defer { lock.unlock() }
        let completion = value
        value = nil
        return completion
    }
}

private final class LockedBool {
    private let lock = NSLock()
    private var value: Bool?

    func set(_ value: Bool) {
        lock.lock()
        self.value = value
        lock.unlock()
    }

    func get() -> Bool? {
        lock.lock()
        defer { lock.unlock() }
        return value
    }
}

private final class LockedTokenIssuer {
    private let lock = NSLock()
    private var issueAction: (() -> String?)?

    func set(_ action: @escaping () -> String?) {
        lock.lock()
        issueAction = action
        lock.unlock()
    }

    func issue() -> String? {
        lock.lock()
        let action = issueAction
        lock.unlock()
        return action?()
    }
}

private final class NoopInteractiveNotificationPresenter: InteractiveNotificationPresenter {
    private(set) var removeAllMirroredNotificationsCallCount = 0

    func setDismissHandler(_ handler: ((String) -> Void)?) {}
    func setReplyHandler(_ handler: ((String, Int, String?, String) -> Void)?) {}
    func show(_ request: HyphenNotifications.NotificationPresentationRequest) {}
    func remove(identifier: String) {}
    func removeAllMirroredNotifications() {
        removeAllMirroredNotificationsCallCount += 1
    }
}

final class PairingControllerColdStartTests: XCTestCase {
    private let fingerprint = Data(repeating: 0x31, count: 32)

    func testDisabledPrivacyPolicyPurgesLocalNotificationsWithoutAnActiveSession() {
        let presenter = NoopInteractiveNotificationPresenter()
        let controller = PairingController(
            notificationPresenter: presenter,
            notificationPrivacyPolicy: {
                NotificationPrivacyPolicy(isMirroringEnabled: false)
            },
            onStatus: { _ in }
        )

        controller.applyLocalNotificationPrivacyPolicy()

        XCTAssertEqual(presenter.removeAllMirroredNotificationsCallCount, 1)
    }

    func testRestorePublishesTrustedPeerAndReconnectStateWithoutStartingNetwork() {
        let lifecycle = FakePeerLinkLifecycle()
        lifecycle.restored = RestoredPeerLink(
            listenPort: 43123,
            peer: TrustedPeer(
                spkiFingerprint: fingerprint,
                displayName: "Pixel",
                addedAt: Date(timeIntervalSince1970: 1)
            )
        )
        var events: [ActivityEvent] = []
        let controller = PairingController(
            peerLinkLifecycle: lifecycle,
            notificationPresenter: NoopInteractiveNotificationPresenter(),
            onActivity: { events.append($0) },
            onStatus: { _ in }
        )

        XCTAssertTrue(controller.restorePersistedLink())
        XCTAssertFalse(controller.restorePersistedLink(), "a second restore must not duplicate reconnect ownership")

        XCTAssertEqual(events.count, 2)
        guard case .peerChanged(let paired, let name) = events[0] else {
            return XCTFail("expected peer event")
        }
        XCTAssertTrue(paired)
        XCTAssertEqual(name, "Pixel")
        guard case .connectionStateChanged(.reconnecting, nil) = events[1] else {
            return XCTFail("expected reconnecting event")
        }
    }

    func testRestoreFailureDoesNotPublishPeerOrConnectionState() {
        let lifecycle = FakePeerLinkLifecycle()
        lifecycle.restoreError = PeerLinkError.corruptRecord
        var events: [ActivityEvent] = []
        var statuses: [String] = []
        let controller = PairingController(
            peerLinkLifecycle: lifecycle,
            notificationPresenter: NoopInteractiveNotificationPresenter(),
            onActivity: { events.append($0) },
            onStatus: { statuses.append($0) }
        )

        XCTAssertFalse(controller.restorePersistedLink())

        XCTAssertTrue(events.isEmpty)
        XCTAssertEqual(statuses, ["Trusted peer link could not be restored"])
    }

    func testTrustChangeInvalidatesDurableRouting() {
        let lifecycle = FakePeerLinkLifecycle()
        let controller = PairingController(
            peerLinkLifecycle: lifecycle,
            notificationPresenter: NoopInteractiveNotificationPresenter(),
            onStatus: { _ in }
        )

        controller.stopAfterTrustChange(invalidateAllTokens: true)

        XCTAssertEqual(lifecycle.invalidations, 1)
    }

    func testRevocationWinsWhileColdRestoreIsRevalidatingTrust() {
        let lifecycle = FakePeerLinkLifecycle()
        lifecycle.restored = restoredLink()
        let restoreEntered = DispatchSemaphore(value: 0)
        let allowRestore = DispatchSemaphore(value: 0)
        lifecycle.beforeRestore = {
            restoreEntered.signal()
            _ = allowRestore.wait(timeout: .now() + 2)
        }
        let result = LockedBool()
        let finished = expectation(description: "restore finished")
        let controller = PairingController(
            peerLinkLifecycle: lifecycle,
            notificationPresenter: NoopInteractiveNotificationPresenter(),
            onStatus: { _ in }
        )

        DispatchQueue.global().async {
            result.set(controller.restorePersistedLink())
            finished.fulfill()
        }
        XCTAssertEqual(restoreEntered.wait(timeout: .now() + 2), .success)

        controller.stopAfterTrustChange(invalidateAllTokens: true)
        allowRestore.signal()
        wait(for: [finished], timeout: 2)

        XCTAssertEqual(result.get(), false)
    }

    func testQueuedReconnectCannotInstallListenerAfterRevocation() {
        let lifecycle = FakePeerLinkLifecycle()
        lifecycle.restored = restoredLink()
        let listener = RecordingReconnectListener()
        let factoryEntered = DispatchSemaphore(value: 0)
        let allowFactory = DispatchSemaphore(value: 0)
        let controller = PairingController(
            peerLinkLifecycle: lifecycle,
            notificationPresenter: NoopInteractiveNotificationPresenter(),
            reconnectListenerFactory: { _, _ in
                factoryEntered.signal()
                _ = allowFactory.wait(timeout: .now() + 2)
                return listener
            },
            onStatus: { _ in }
        )
        XCTAssertTrue(controller.restorePersistedLink())

        controller.requestReconnect()
        XCTAssertEqual(factoryEntered.wait(timeout: .now() + 2), .success)
        controller.stopAfterTrustChange(invalidateAllTokens: true)
        allowFactory.signal()

        XCTAssertEqual(listener.stopped.wait(timeout: .now() + 2), .success)
        XCTAssertEqual(listener.counts().starts, 0)
        XCTAssertEqual(listener.counts().stops, 1)
    }

    func testNewPeerOwnershipStopsThePreviousPeersReconnectListener() {
        let lifecycle = FakePeerLinkLifecycle()
        lifecycle.restored = restoredLink()
        let listener = RecordingReconnectListener()
        let controller = PairingController(
            peerLinkLifecycle: lifecycle,
            notificationPresenter: NoopInteractiveNotificationPresenter(),
            reconnectListenerFactory: { _, _ in listener },
            onStatus: { _ in }
        )
        XCTAssertTrue(controller.restorePersistedLink())
        controller.requestReconnect()
        XCTAssertEqual(listener.started.wait(timeout: .now() + 2), .success)

        _ = controller.beginPeerLinkOwnership(
            peerFingerprint: Data(repeating: 0x52, count: 32)
        )

        XCTAssertEqual(listener.stopped.wait(timeout: .now() + 2), .success)
        XCTAssertEqual(listener.counts().stops, 1)
        XCTAssertEqual(lifecycle.invalidations, 1)
        XCTAssertEqual(lifecycle.remembered.count, 1)
        XCTAssertEqual(lifecycle.remembered[0].listenPort, 43123)
        XCTAssertEqual(lifecycle.remembered[0].peerFingerprint, Data(repeating: 0x52, count: 32))
    }

    func testStaleHandshakeFailureCannotInstallAListenerForTheNewPeerGeneration() {
        let lifecycle = FakePeerLinkLifecycle()
        lifecycle.restored = restoredLink()
        let listener = RecordingReconnectListener()
        let completion = LockedHandshakeCompletion()
        let handshakeEntered = DispatchSemaphore(value: 0)
        let controller = PairingController(
            peerLinkLifecycle: lifecycle,
            notificationPresenter: NoopInteractiveNotificationPresenter(),
            reconnectListenerFactory: { _, _ in listener },
            steadyHandshakeResponder: { _, _, _, _, _, _, callback in
                completion.set(callback)
                handshakeEntered.signal()
            },
            onStatus: { _ in }
        )
        XCTAssertTrue(controller.restorePersistedLink())
        controller.requestReconnect()
        XCTAssertEqual(listener.started.wait(timeout: .now() + 2), .success)
        listener.deliver(NWConnection(host: "127.0.0.1", port: 9, using: .tcp))
        XCTAssertEqual(handshakeEntered.wait(timeout: .now() + 2), .success)

        _ = controller.beginPeerLinkOwnership(
            peerFingerprint: Data(repeating: 0x52, count: 32)
        )
        completion.take()?(.failure(NSError(domain: "test.stale-handshake", code: 1)))
        let queuesDrained = expectation(description: "queues drained")
        DispatchQueue.global().asyncAfter(deadline: .now() + 0.1) {
            DispatchQueue.main.async { queuesDrained.fulfill() }
        }
        wait(for: [queuesDrained], timeout: 2)

        XCTAssertEqual(listener.counts().starts, 1)
    }

    func testFailedReconnectHandshakeReinstallsListenerForNextRedial() {
        let lifecycle = FakePeerLinkLifecycle()
        lifecycle.restored = restoredLink()
        let listener = RecordingReconnectListener()
        let controller = PairingController(
            peerLinkLifecycle: lifecycle,
            notificationPresenter: NoopInteractiveNotificationPresenter(),
            reconnectListenerFactory: { _, _ in listener },
            steadyHandshakeResponder: { _, _, _, _, _, _, completion in
                completion(.failure(NSError(domain: "test.handshake", code: 1)))
            },
            onStatus: { _ in }
        )
        XCTAssertTrue(controller.restorePersistedLink())

        controller.requestReconnect()
        XCTAssertEqual(listener.started.wait(timeout: .now() + 2), .success)
        listener.deliver(NWConnection(host: "127.0.0.1", port: 9, using: .tcp))

        XCTAssertEqual(listener.stopped.wait(timeout: .now() + 2), .success)
        XCTAssertEqual(listener.started.wait(timeout: .now() + 2), .success)
        XCTAssertEqual(listener.counts().starts, 2)
        XCTAssertEqual(listener.counts().stops, 1)
    }

    func testRevocationWinsAfterAcceptWhileReconnectHandshakeCompletionIsQueued() {
        let lifecycle = FakePeerLinkLifecycle()
        lifecycle.restored = restoredLink()
        let listener = RecordingReconnectListener()
        let completion = LockedHandshakeCompletion()
        let handshakeEntered = DispatchSemaphore(value: 0)
        var events: [ActivityEvent] = []
        let controller = PairingController(
            peerLinkLifecycle: lifecycle,
            notificationPresenter: NoopInteractiveNotificationPresenter(),
            reconnectListenerFactory: { _, _ in listener },
            steadyHandshakeResponder: { _, _, _, _, _, _, callback in
                completion.set(callback)
                handshakeEntered.signal()
            },
            onActivity: { events.append($0) },
            onStatus: { _ in }
        )
        XCTAssertTrue(controller.restorePersistedLink())
        controller.requestReconnect()
        XCTAssertEqual(listener.started.wait(timeout: .now() + 2), .success)
        listener.deliver(NWConnection(host: "127.0.0.1", port: 9, using: .tcp))
        XCTAssertEqual(handshakeEntered.wait(timeout: .now() + 2), .success)

        controller.stopAfterTrustChange(invalidateAllTokens: true)
        completion.take()?(.success(SessionHandshake.HandshakeResult(
            sessionId: "s_stale",
            resumeToken: "must-not-survive",
            resumed: false,
            peerDeviceName: "Revoked Pixel",
            negotiatedCapabilities: .empty(),
            leftover: Data()
        )))
        let mainQueueDrained = expectation(description: "main queue drained")
        DispatchQueue.main.async { mainQueueDrained.fulfill() }
        wait(for: [mainQueueDrained], timeout: 2)

        XCTAssertFalse(controller.hasActiveSession)
        XCTAssertFalse(events.contains { event in
            if case .connectionStateChanged(.connected, nil) = event { return true }
            return false
        })
    }

    func testRevocationInvalidatesTokenIssuanceCapturedByAnAcceptedHandshake() {
        let lifecycle = FakePeerLinkLifecycle()
        lifecycle.restored = restoredLink()
        let listener = RecordingReconnectListener()
        let issuer = LockedTokenIssuer()
        let handshakeEntered = DispatchSemaphore(value: 0)
        let controller = PairingController(
            peerLinkLifecycle: lifecycle,
            notificationPresenter: NoopInteractiveNotificationPresenter(),
            reconnectListenerFactory: { _, _ in listener },
            steadyHandshakeResponder: { _, _, peerFingerprint, tokenStore, issuance, _, _ in
                issuer.set {
                    tokenStore.issue(
                        sessionId: "s_must_not_survive",
                        peerFingerprint: peerFingerprint,
                        ifCurrent: issuance
                    )
                }
                handshakeEntered.signal()
            },
            onStatus: { _ in }
        )
        XCTAssertTrue(controller.restorePersistedLink())
        controller.requestReconnect()
        XCTAssertEqual(listener.started.wait(timeout: .now() + 2), .success)
        listener.deliver(NWConnection(host: "127.0.0.1", port: 9, using: .tcp))
        XCTAssertEqual(handshakeEntered.wait(timeout: .now() + 2), .success)

        controller.stopAfterTrustChange(invalidateAllTokens: true)

        XCTAssertNil(issuer.issue())
    }

    private func restoredLink() -> RestoredPeerLink {
        RestoredPeerLink(
            listenPort: 43123,
            peer: TrustedPeer(
                spkiFingerprint: fingerprint,
                displayName: "Pixel",
                addedAt: Date(timeIntervalSince1970: 1)
            )
        )
    }
}
