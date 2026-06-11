import Foundation

/// The trust-write gate behind the SAS confirmation UI (HYP-M2-011,
/// protocol v0 §5.3): the peer fingerprint reaches the trust store ONLY
/// through `confirm()`, and a rejection is sticky — nothing persists, the
/// pairing session is dead (`trust/sas-rejected`). One gate instance per
/// pairing attempt; a new attempt builds a new gate (fresh nonce → fresh
/// transcript → fresh SAS).
public final class SasConfirmationGate {

    public enum Outcome: Equatable {
        case trusted
        case rejected
    }

    public let transcript: PairingTranscript
    public private(set) var outcome: Outcome?

    private let peerFingerprint: Data
    private let peerDisplayName: String
    private let trustStore: PeerTrustStore

    /// The 6-digit code the UI displays; the user compares it with the
    /// code on the other device.
    public var sas: String { transcript.sas }

    /// - Parameter peerFingerprint: the fingerprint that will be trusted
    ///   on confirm — on the Mac this is the Android device's SPKI
    ///   fingerprint captured from the provisional TLS handshake.
    public init(
        transcript: PairingTranscript,
        peerFingerprint: Data,
        peerDisplayName: String,
        trustStore: PeerTrustStore
    ) {
        self.transcript = transcript
        self.peerFingerprint = peerFingerprint
        self.peerDisplayName = peerDisplayName
        self.trustStore = trustStore
    }

    /// Persists the peer. Idempotent after a confirm; refused after a
    /// rejection (a dead pairing session can never become trusted).
    @discardableResult
    public func confirm(at date: Date = Date()) throws -> Outcome {
        switch outcome {
        case .trusted:
            return .trusted
        case .rejected:
            return .rejected
        case nil:
            try trustStore.add(
                TrustedPeer(
                    spkiFingerprint: peerFingerprint,
                    displayName: peerDisplayName,
                    addedAt: date
                )
            )
            outcome = .trusted
            return .trusted
        }
    }

    /// Aborts the pairing session. Persists nothing, ever.
    @discardableResult
    public func reject() -> Outcome {
        if outcome == nil { outcome = .rejected }
        return outcome!
    }
}
