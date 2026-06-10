import Foundation

/// A trusted peer record (HYP-M2-005). The SPKI fingerprint IS the peer
/// identity: a different key is a different (untrusted) peer, and key
/// changes always re-enter pairing (protocol v0 §2).
public struct TrustedPeer: Codable, Equatable {
    /// SHA-256 of the peer certificate's DER SubjectPublicKeyInfo; 32 bytes.
    public let spkiFingerprint: Data
    public let displayName: String
    public let addedAt: Date

    public init(spkiFingerprint: Data, displayName: String, addedAt: Date) {
        self.spkiFingerprint = spkiFingerprint
        self.displayName = displayName
        self.addedAt = addedAt
    }
}

public enum TrustStoreError: Error, Equatable {
    case invalidFingerprintLength(Int)
    case keychain(OSStatus)
    case corruptRecord
}

public protocol PeerTrustStore {
    /// Upsert: re-trusting the same fingerprint updates metadata.
    func add(_ peer: TrustedPeer) throws
    func peer(withFingerprint fingerprint: Data) throws -> TrustedPeer?
    @discardableResult
    func remove(fingerprint: Data) throws -> Bool
    func allPeers() throws -> [TrustedPeer]
}

extension Data {
    var hyphenHexString: String {
        map { String(format: "%02x", $0) }.joined()
    }

    init?(hyphenHexString: String) {
        guard hyphenHexString.count % 2 == 0 else { return nil }
        var data = Data(capacity: hyphenHexString.count / 2)
        var index = hyphenHexString.startIndex
        while index < hyphenHexString.endIndex {
            let next = hyphenHexString.index(index, offsetBy: 2)
            guard let byte = UInt8(hyphenHexString[index..<next], radix: 16) else { return nil }
            data.append(byte)
            index = next
        }
        self = data
    }
}
