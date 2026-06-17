import XCTest
@testable import HyphenCore

final class ProvisionalPairingStateTests: XCTestCase {

    /// Stand-in for the provisional `NWConnection`; only its identity matters.
    private final class FakeConnection {}

    private let fpA = Data([0x01, 0x02, 0x03])
    private let fpB = Data([0x0A, 0x0B, 0x0C])

    func testClaimRecordsFingerprintWhenSlotIsFree() {
        var state = ProvisionalPairingState()
        XCTAssertTrue(state.claimFingerprint(fpA))
        XCTAssertEqual(state.pendingFingerprint, fpA)
        XCTAssertFalse(state.hasAttachedConnection)
    }

    func testSecondClaimBeforeAttachReclaimsTheSlot() {
        // A dropped or aborted pre-SAS attempt leaves a pending fingerprint with
        // no connection; a retry must be able to reclaim it. (pairing wedge fix)
        var state = ProvisionalPairingState()
        XCTAssertTrue(state.claimFingerprint(fpA))
        XCTAssertTrue(state.claimFingerprint(fpB))
        XCTAssertEqual(state.pendingFingerprint, fpB)
    }

    func testClaimIsRejectedOnceAConnectionHasAttached() {
        var state = ProvisionalPairingState()
        let connection = FakeConnection()
        XCTAssertTrue(state.claimFingerprint(fpA))
        XCTAssertEqual(state.attachConnection(ObjectIdentifier(connection)), fpA)
        // One peer in SAS confirmation at a time: a second peer is refused.
        XCTAssertFalse(state.claimFingerprint(fpB))
        XCTAssertEqual(state.pendingFingerprint, fpA)
    }

    func testAttachReturnsPendingFingerprintAndRefusesSurplusConnections() {
        var state = ProvisionalPairingState()
        let first = FakeConnection()
        let second = FakeConnection()
        XCTAssertTrue(state.claimFingerprint(fpA))
        XCTAssertEqual(state.attachConnection(ObjectIdentifier(first)), fpA)
        XCTAssertTrue(state.hasAttachedConnection)
        XCTAssertNil(state.attachConnection(ObjectIdentifier(second)))
    }

    func testAttachWithoutPendingClaimReturnsNil() {
        var state = ProvisionalPairingState()
        let connection = FakeConnection()
        XCTAssertNil(state.attachConnection(ObjectIdentifier(connection)))
    }

    func testReleaseClearsAttachedConnectionAndAllowsRetry() {
        var state = ProvisionalPairingState()
        let dropped = FakeConnection()
        XCTAssertTrue(state.claimFingerprint(fpA))
        XCTAssertEqual(state.attachConnection(ObjectIdentifier(dropped)), fpA)

        XCTAssertTrue(state.releaseConnection(ObjectIdentifier(dropped)))
        XCTAssertNil(state.pendingFingerprint)
        XCTAssertFalse(state.hasAttachedConnection)

        // Retry: a fresh peer can claim and attach after the drop.
        let retry = FakeConnection()
        XCTAssertTrue(state.claimFingerprint(fpB))
        XCTAssertEqual(state.attachConnection(ObjectIdentifier(retry)), fpB)
    }

    func testReleaseIsNoOpForANonMatchingConnection() {
        // A rejected concurrent peer (or the post-confirm session connection)
        // dropping must not clear the live provisional slot.
        var state = ProvisionalPairingState()
        let attached = FakeConnection()
        let other = FakeConnection()
        XCTAssertTrue(state.claimFingerprint(fpA))
        XCTAssertEqual(state.attachConnection(ObjectIdentifier(attached)), fpA)

        XCTAssertFalse(state.releaseConnection(ObjectIdentifier(other)))
        XCTAssertEqual(state.pendingFingerprint, fpA)
        XCTAssertTrue(state.hasAttachedConnection)
    }

    func testResetClearsTheSlotAtConfirmation() {
        var state = ProvisionalPairingState()
        let connection = FakeConnection()
        XCTAssertTrue(state.claimFingerprint(fpA))
        _ = state.attachConnection(ObjectIdentifier(connection))
        state.reset()
        XCTAssertEqual(state, ProvisionalPairingState())
    }
}
