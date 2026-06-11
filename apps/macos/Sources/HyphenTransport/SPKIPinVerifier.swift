import Foundation
import Security

/// Pin check for the TLS verify block (HYP-M2-007): extracts the peer
/// leaf certificate's SPKI fingerprint and asks `isTrusted` (in the app,
/// a `PeerTrustStore` lookup; in pairing, the QR-pre-shared fingerprint).
/// This REPLACES default X.509 chain evaluation — discovery is not trust,
/// and a CA chain proves nothing here; only the pinned key does.
public struct SPKIPinVerifier {

    private let isTrusted: (Data) -> Bool

    public init(isTrusted: @escaping (Data) -> Bool) {
        self.isTrusted = isTrusted
    }

    /// Convenience for pairing flows that expect exactly one fingerprint.
    public init(expectedFingerprint: Data) {
        self.init { $0 == expectedFingerprint }
    }

    /// Anything unexpected (no leaf, unreadable key) is a reject, never a
    /// crash and never a silent accept.
    public func verify(trust: SecTrust) -> Bool {
        guard let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate],
              let leaf = chain.first,
              let publicKey = SecCertificateCopyKey(leaf),
              let fingerprint = try? SPKI.fingerprint(for: publicKey)
        else { return false }
        return isTrusted(fingerprint)
    }
}
