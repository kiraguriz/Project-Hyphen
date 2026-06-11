import Security
import XCTest
@testable import HyphenTransport

/// Runs against the real login keychain under unique throwaway labels.
final class CertificateMintingTests: XCTestCase {

    private var store: KeychainIdentityStore!

    override func setUp() {
        super.setUp()
        store = KeychainIdentityStore(label: "dev.hyphen.identity.test.\(UUID().uuidString)")
    }

    override func tearDown() {
        try? store.removeIdentity()
        store = nil
        super.tearDown()
    }

    func testKnownOIDEncoding() {
        // id-ecPublicKey 1.2.840.10045.2.1, from X.690 base-128 rules.
        XCTAssertEqual(
            DER.objectIdentifier("1.2.840.10045.2.1"),
            Data([0x06, 0x07, 0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x02, 0x01])
        )
    }

    func testMintedCertificateParsesAndPinMatchesKey() throws {
        let identity = try store.loadOrCreate(commonName: "Hyphen Test")

        XCTAssertEqual(identity.spkiFingerprint.count, 32)

        // Security.framework must accept our hand-built DER…
        let cert = SecCertificateCreateWithData(nil, identity.certificateDER as CFData)
        let parsed = try XCTUnwrap(cert, "minted certificate failed to parse")

        // …and the fingerprint derived from the parsed cert must match the
        // one the identity reports (cert and pin share one SPKI encoder).
        let publicKey = try XCTUnwrap(SecCertificateCopyKey(parsed))
        XCTAssertEqual(try SPKI.fingerprint(for: publicKey), identity.spkiFingerprint)

        XCTAssertEqual(
            SecCertificateCopySubjectSummary(parsed) as String?,
            "Hyphen Test"
        )
    }

    func testLoadOrCreateIsIdempotent() throws {
        let first = try store.loadOrCreate()
        let second = try store.loadOrCreate()
        XCTAssertEqual(first.spkiFingerprint, second.spkiFingerprint)
        XCTAssertEqual(first.certificateDER, second.certificateDER)
    }

    func testRemoveIdentityForcesANewKeyPair() throws {
        let first = try store.loadOrCreate()
        try store.removeIdentity()
        let second = try store.loadOrCreate()
        XCTAssertNotEqual(
            first.spkiFingerprint,
            second.spkiFingerprint,
            "identity reset must rotate the key (peers must re-pair)"
        )
    }
}
