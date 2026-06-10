import XCTest
@testable import HyphenCore

/// Runs against the real login keychain under a unique throwaway service
/// name per test run; tearDown removes everything it created.
final class KeychainTrustStoreTests: XCTestCase {

    private var store: KeychainTrustStore!

    private let fpA = Data(repeating: 0xAA, count: 32)
    private let fpB = Data(repeating: 0xBB, count: 32)

    private func peer(_ fp: Data, name: String) -> TrustedPeer {
        TrustedPeer(spkiFingerprint: fp, displayName: name, addedAt: Date(timeIntervalSince1970: 1_781_020_800))
    }

    override func setUp() {
        super.setUp()
        store = KeychainTrustStore(service: "dev.hyphen.peers.test.\(UUID().uuidString)")
    }

    override func tearDown() {
        try? store.removeAll()
        store = nil
        super.tearDown()
    }

    func testAddGetRoundtripPreservesRecord() throws {
        let original = peer(fpA, name: "Pixel")
        try store.add(original)
        let loaded = try store.peer(withFingerprint: fpA)
        XCTAssertEqual(loaded, original)
    }

    func testGetUnknownFingerprintReturnsNil() throws {
        XCTAssertNil(try store.peer(withFingerprint: fpA))
    }

    func testAddIsUpsert() throws {
        try store.add(peer(fpA, name: "Pixel"))
        try store.add(peer(fpA, name: "Pixel 9 Pro"))
        XCTAssertEqual(try store.peer(withFingerprint: fpA)?.displayName, "Pixel 9 Pro")
        XCTAssertEqual(try store.allPeers().count, 1)
    }

    func testRemoveReportsPresence() throws {
        try store.add(peer(fpA, name: "Pixel"))
        XCTAssertTrue(try store.remove(fingerprint: fpA))
        XCTAssertNil(try store.peer(withFingerprint: fpA))
        XCTAssertFalse(try store.remove(fingerprint: fpA))
    }

    func testAllPeersListsEverything() throws {
        try store.add(peer(fpA, name: "Pixel"))
        try store.add(peer(fpB, name: "Galaxy"))
        let names = Set(try store.allPeers().map(\.displayName))
        XCTAssertEqual(names, ["Pixel", "Galaxy"])
    }

    func testWrongFingerprintLengthIsRejectedEverywhere() {
        let short = Data(repeating: 0x01, count: 31)
        XCTAssertThrowsError(try store.add(peer(short, name: "x"))) { error in
            XCTAssertEqual(error as? TrustStoreError, .invalidFingerprintLength(31))
        }
        XCTAssertThrowsError(try store.peer(withFingerprint: short))
        XCTAssertThrowsError(try store.remove(fingerprint: short))
    }

    func testStoresAreIsolatedByService() throws {
        try store.add(peer(fpA, name: "Pixel"))
        let other = KeychainTrustStore(service: "dev.hyphen.peers.test.\(UUID().uuidString)")
        defer { try? other.removeAll() }
        XCTAssertNil(try other.peer(withFingerprint: fpA))
        XCTAssertEqual(try other.allPeers(), [])
    }
}
