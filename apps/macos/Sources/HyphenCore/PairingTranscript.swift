import CryptoKit
import Foundation

/// Pairing transcript and SAS (protocol v0 §5.3, HYP-M2-009/011):
///
/// ```text
/// transcript     = "hyphen-pair-v0" || nonce || macSpkiFp || androidSpkiFp || protocolVersion
/// transcriptHash = SHA-256(transcript)
/// SAS            = uint64_be(hash[0..7]) mod 10^6, zero-padded to 6 digits
/// ```
///
/// Field order binds roles and the version is bound last (downgrade
/// detection); all of it is pinned by the normative vectors in
/// `protocol/test-vectors/pairing/sas-vectors.json`, which the test suite
/// reproduces case by case.
public struct PairingTranscript: Equatable {

    public static let label = "hyphen-pair-v0"

    public let transcriptData: Data
    public let hash: Data
    /// Exactly 6 decimal digits, leading zeros preserved.
    public let sas: String

    public init?(
        nonce: Data,
        macSpkiFingerprint: Data,
        androidSpkiFingerprint: Data,
        protocolVersion: String
    ) {
        guard nonce.count == PairingQRPayload.nonceLength,
              macSpkiFingerprint.count == PairingQRPayload.fingerprintLength,
              androidSpkiFingerprint.count == PairingQRPayload.fingerprintLength,
              !protocolVersion.isEmpty
        else { return nil }

        var transcript = Data(Self.label.utf8)
        transcript += nonce
        transcript += macSpkiFingerprint
        transcript += androidSpkiFingerprint
        transcript += Data(protocolVersion.utf8)

        let digest = Data(SHA256.hash(data: transcript))
        var value: UInt64 = 0
        for byte in digest.prefix(8) {
            value = (value << 8) | UInt64(byte)
        }

        self.transcriptData = transcript
        self.hash = digest
        self.sas = String(format: "%06d", value % 1_000_000)
    }
}
