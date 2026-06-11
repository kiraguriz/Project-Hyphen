import CryptoKit
import Foundation
import Security

public enum IdentityError: Error, Equatable {
    case keyGeneration(String)
    case publicKeyExport(String)
    case signing(String)
    case certificateParse
    case keychain(OSStatus)
    case identityNotConstructible(OSStatus)
}

/// SubjectPublicKeyInfo handling for EC P-256 keys. The SPKI DER built
/// here is both embedded in minted certificates and hashed for the pin:
/// fingerprint = SHA-256(DER SPKI) (protocol v0 §2) — one encoder, so
/// cert and pin can never disagree.
public enum SPKI {

    static let idECPublicKey = "1.2.840.10045.2.1"
    static let prime256v1 = "1.2.840.10045.3.1.7"

    /// Wraps a P-256 public key (X9.63 raw point from
    /// `SecKeyCopyExternalRepresentation`) into DER SubjectPublicKeyInfo.
    public static func der(for publicKey: SecKey) throws -> Data {
        var error: Unmanaged<CFError>?
        guard let point = SecKeyCopyExternalRepresentation(publicKey, &error) as Data? else {
            throw IdentityError.publicKeyExport(String(describing: error?.takeRetainedValue()))
        }
        return DER.sequence(
            DER.sequence(
                DER.objectIdentifier(idECPublicKey),
                DER.objectIdentifier(prime256v1)
            ),
            DER.bitString(point)
        )
    }

    /// 32-byte pin: SHA-256 over the DER SubjectPublicKeyInfo.
    public static func fingerprint(for publicKey: SecKey) throws -> Data {
        Data(SHA256.hash(data: try der(for: publicKey)))
    }
}

/// Mints the long-lived self-signed identity certificate (protocol v0 §2).
/// No extensions on purpose: peers never run default X.509 chain
/// evaluation — the verify block replaces it with SPKI pinning — so SAN/
/// EKU/BasicConstraints would be dead weight in v0.
enum SelfSignedCertificate {

    static let ecdsaWithSHA256 = "1.2.840.10045.4.3.2"
    static let commonNameOID = "2.5.4.3"

    static func makeDER(
        privateKey: SecKey,
        publicKey: SecKey,
        commonName: String,
        validFrom: Date,
        validForDays: Int
    ) throws -> Data {
        var serial = Data((0..<16).map { _ in UInt8.random(in: 0...255) })
        serial[0] = (serial[0] & 0x7F) | 0x01 // positive, no leading zero

        let signatureAlgorithm = DER.sequence(DER.objectIdentifier(ecdsaWithSHA256))
        let name = DER.sequence(
            DER.set(
                DER.sequence(
                    DER.objectIdentifier(commonNameOID),
                    DER.utf8String(commonName)
                )
            )
        )
        let validity = DER.sequence(
            DER.utcTime(validFrom),
            DER.utcTime(validFrom.addingTimeInterval(TimeInterval(validForDays) * 86_400))
        )

        let tbsCertificate = DER.sequence(
            DER.contextTag(0, DER.integer(2)), // version v3
            DER.integer(serial),
            signatureAlgorithm,
            name, // issuer == subject: self-signed
            validity,
            name,
            try SPKI.der(for: publicKey)
        )

        var error: Unmanaged<CFError>?
        guard let signature = SecKeyCreateSignature(
            privateKey,
            .ecdsaSignatureMessageX962SHA256,
            tbsCertificate as CFData,
            &error
        ) as Data? else {
            throw IdentityError.signing(String(describing: error?.takeRetainedValue()))
        }

        return DER.sequence(tbsCertificate, signatureAlgorithm, DER.bitString(signature))
    }
}
