import XCTest
@testable import HyphenCore

private final class MemoryPeerLinkStore: PeerLinkStore {
    var descriptor: RememberedPeerLink?
    var clearCalls = 0
    var saveError: Error?

    func save(_ descriptor: RememberedPeerLink) throws {
        if let saveError { throw saveError }
        self.descriptor = descriptor
    }

    func load() throws -> RememberedPeerLink? { descriptor }

    func clear() throws {
        clearCalls += 1
        descriptor = nil
    }
}

private final class MemoryPeerTrustStore: PeerTrustStore {
    var peers: [TrustedPeer] = []

    func add(_ peer: TrustedPeer) throws {
        peers.removeAll { $0.spkiFingerprint == peer.spkiFingerprint }
        peers.append(peer)
    }

    func peer(withFingerprint fingerprint: Data) throws -> TrustedPeer? {
        peers.first { $0.spkiFingerprint == fingerprint }
    }

    func remove(fingerprint: Data) throws -> Bool {
        let before = peers.count
        peers.removeAll { $0.spkiFingerprint == fingerprint }
        return peers.count != before
    }

    func allPeers() throws -> [TrustedPeer] { peers }
}

private final class BlockingSavePeerLinkStore: PeerLinkStore {
    private let lock = NSLock()
    private var stored: RememberedPeerLink?
    let saveEntered = DispatchSemaphore(value: 0)
    let allowSave = DispatchSemaphore(value: 0)
    let clearEntered = DispatchSemaphore(value: 0)

    func save(_ descriptor: RememberedPeerLink) throws {
        saveEntered.signal()
        _ = allowSave.wait(timeout: .now() + 2)
        lock.lock()
        stored = descriptor
        lock.unlock()
    }

    func load() throws -> RememberedPeerLink? {
        lock.lock()
        defer { lock.unlock() }
        return stored
    }

    func clear() throws {
        clearEntered.signal()
        lock.lock()
        stored = nil
        lock.unlock()
    }
}

final class PeerLinkLifecycleTests: XCTestCase {
    private let fingerprint = Data(repeating: 0x42, count: 32)

    func testRememberAndColdRestoreArePinnedToCurrentTrust() throws {
        let links = MemoryPeerLinkStore()
        let trust = MemoryPeerTrustStore()
        try trust.add(TrustedPeer(spkiFingerprint: fingerprint, displayName: "Pixel", addedAt: Date()))
        let lifecycle: PeerLinkLifecycle = PersistentPeerLinkLifecycle(store: links, trustStore: trust)

        try lifecycle.remember(listenPort: 43123, peerFingerprint: fingerprint)
        let restored = try XCTUnwrap(lifecycle.restoreTrusted())

        XCTAssertEqual(restored.listenPort, 43123)
        XCTAssertEqual(restored.peer.displayName, "Pixel")
        XCTAssertEqual(restored.peer.spkiFingerprint, fingerprint)
    }

    func testUntrustedFingerprintCanNeverBePersisted() {
        let links = MemoryPeerLinkStore()
        let lifecycle: PeerLinkLifecycle = PersistentPeerLinkLifecycle(
            store: links,
            trustStore: MemoryPeerTrustStore()
        )

        XCTAssertThrowsError(try lifecycle.remember(listenPort: 43123, peerFingerprint: fingerprint)) { error in
            XCTAssertEqual(error as? PeerLinkError, .untrustedPeer)
        }
        XCTAssertNil(links.descriptor)
    }

    func testStaleDescriptorIsClearedAfterTrustRemoval() throws {
        let links = MemoryPeerLinkStore()
        links.descriptor = RememberedPeerLink(peerFingerprint: fingerprint, listenPort: 43123)
        let lifecycle: PeerLinkLifecycle = PersistentPeerLinkLifecycle(
            store: links,
            trustStore: MemoryPeerTrustStore()
        )

        XCTAssertNil(try lifecycle.restoreTrusted())
        XCTAssertNil(links.descriptor)
        XCTAssertEqual(links.clearCalls, 1)
    }

    func testInvalidateRemovesColdStartRouting() throws {
        let links = MemoryPeerLinkStore()
        links.descriptor = RememberedPeerLink(peerFingerprint: fingerprint, listenPort: 43123)
        let lifecycle: PeerLinkLifecycle = PersistentPeerLinkLifecycle(
            store: links,
            trustStore: MemoryPeerTrustStore()
        )

        try lifecycle.invalidate()
        XCTAssertNil(links.descriptor)
    }

    func testInvalidateCannotLoseToAnInFlightRememberSave() throws {
        let links = BlockingSavePeerLinkStore()
        let trust = MemoryPeerTrustStore()
        try trust.add(TrustedPeer(spkiFingerprint: fingerprint, displayName: "Pixel", addedAt: Date()))
        let lifecycle = PersistentPeerLinkLifecycle(store: links, trustStore: trust)
        let rememberFinished = expectation(description: "remember finished")
        let invalidateFinished = expectation(description: "invalidate finished")
        let invalidateStarted = DispatchSemaphore(value: 0)

        DispatchQueue.global().async {
            try? lifecycle.remember(listenPort: 43123, peerFingerprint: self.fingerprint)
            rememberFinished.fulfill()
        }
        XCTAssertEqual(links.saveEntered.wait(timeout: .now() + 2), .success)

        DispatchQueue.global().async {
            invalidateStarted.signal()
            try? lifecycle.invalidate()
            invalidateFinished.fulfill()
        }
        XCTAssertEqual(invalidateStarted.wait(timeout: .now() + 2), .success)
        _ = links.clearEntered.wait(timeout: .now() + 0.25)
        links.allowSave.signal()
        wait(for: [rememberFinished, invalidateFinished], timeout: 2)

        XCTAssertNil(try links.load(), "invalidation must be ordered after the in-flight save")
    }

    func testFailedReplacementClearsThePreviouslyCurrentDescriptor() throws {
        let oldFingerprint = Data(repeating: 0x21, count: 32)
        let links = MemoryPeerLinkStore()
        links.descriptor = RememberedPeerLink(peerFingerprint: oldFingerprint, listenPort: 42100)
        links.saveError = PeerLinkError.corruptRecord
        let trust = MemoryPeerTrustStore()
        try trust.add(TrustedPeer(spkiFingerprint: fingerprint, displayName: "Pixel", addedAt: Date()))
        let lifecycle = PersistentPeerLinkLifecycle(store: links, trustStore: trust)

        XCTAssertThrowsError(try lifecycle.remember(listenPort: 43123, peerFingerprint: fingerprint))

        XCTAssertNil(links.descriptor)
        XCTAssertEqual(links.clearCalls, 1)
    }
}
