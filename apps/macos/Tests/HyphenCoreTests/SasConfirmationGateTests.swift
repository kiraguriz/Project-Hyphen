import XCTest
@testable import HyphenCore

/// The acceptance property of HYP-M2-011: the user confirms the matching
/// code BEFORE trust is stored — proven against the real keychain store.
final class SasConfirmationGateTests: XCTestCase {

    private var store: KeychainTrustStore!
    private let peerFp = Data(repeating: 0xAB, count: 32)

    override func setUp() {
        super.setUp()
        store = KeychainTrustStore(service: "dev.hyphen.peers.test.\(UUID().uuidString)")
    }

    override func tearDown() {
        try? store.removeAll()
        store = nil
        super.tearDown()
    }

    private func makeGate() -> SasConfirmationGate {
        SasConfirmationGate(
            transcript: PairingTranscript(
                nonce: Data(repeating: 0x01, count: 16),
                macSpkiFingerprint: Data(repeating: 0x02, count: 32),
                androidSpkiFingerprint: peerFp,
                protocolVersion: "hyphen/0.3"
            )!,
            peerFingerprint: peerFp,
            peerDisplayName: "Pixel",
            trustStore: store
        )
    }

    func testNothingIsStoredBeforeConfirmation() throws {
        let gate = makeGate()
        XCTAssertEqual(gate.sas.count, 6)
        XCTAssertNil(gate.outcome)
        XCTAssertEqual(try store.allPeers(), [], "displaying the SAS must not persist anything")
    }

    func testConfirmStoresExactlyThePeerFingerprint() throws {
        let gate = makeGate()
        XCTAssertEqual(try gate.confirm(), .trusted)
        let stored = try XCTUnwrap(store.peer(withFingerprint: peerFp))
        XCTAssertEqual(stored.displayName, "Pixel")
        XCTAssertEqual(try store.allPeers().count, 1)
    }

    func testRejectPersistsNothingAndIsSticky() throws {
        let gate = makeGate()
        XCTAssertEqual(gate.reject(), .rejected)
        XCTAssertEqual(try store.allPeers(), [])
        // A dead pairing session can never become trusted.
        XCTAssertEqual(try gate.confirm(), .rejected)
        XCTAssertEqual(try store.allPeers(), [])
    }

    func testDoubleConfirmIsIdempotent() throws {
        let gate = makeGate()
        try gate.confirm()
        try gate.confirm()
        XCTAssertEqual(try store.allPeers().count, 1)
        XCTAssertEqual(gate.reject(), .trusted, "reject after confirm must not undo trust silently")
        XCTAssertEqual(try store.allPeers().count, 1)
    }
}
