import XCTest
@testable import HyphenCore

final class KeychainPeerLinkStoreTests: XCTestCase {
    private var store: KeychainPeerLinkStore!
    private let fingerprint = Data(repeating: 0xA5, count: 32)

    override func setUp() {
        super.setUp()
        store = KeychainPeerLinkStore(service: "dev.hyphen.peer-link.test.\(UUID().uuidString)")
    }

    override func tearDown() {
        try? store.clear()
        store = nil
        super.tearDown()
    }

    func testDescriptorRoundtripsAndUpdates() throws {
        try store.save(RememberedPeerLink(peerFingerprint: fingerprint, listenPort: 43123))
        XCTAssertEqual(try store.load(), RememberedPeerLink(peerFingerprint: fingerprint, listenPort: 43123))

        try store.save(RememberedPeerLink(peerFingerprint: fingerprint, listenPort: 43124))
        XCTAssertEqual(try store.load()?.listenPort, 43124)
    }

    func testClearRemovesDescriptor() throws {
        try store.save(RememberedPeerLink(peerFingerprint: fingerprint, listenPort: 43123))
        try store.clear()
        XCTAssertNil(try store.load())
    }
}
