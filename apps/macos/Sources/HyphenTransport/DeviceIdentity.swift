import Foundation
import Security

/// The device's own TLS identity (HYP-M2-007): a P-256 key pair plus its
/// self-signed certificate. The key pair is long-lived — the SPKI
/// fingerprint peers pin survives certificate renewal; a key change is a
/// new identity and forces re-pairing (protocol v0 §2).
public struct DeviceIdentity {
    public let identity: SecIdentity
    public let certificateDER: Data
    /// SHA-256 of this device's DER SubjectPublicKeyInfo; 32 bytes.
    public let spkiFingerprint: Data
}

/// Stores the identity in the login keychain (same v0 decision as the
/// peer trust store, HYP-M2-005: works unsigned; data-protection
/// keychain migration queued for the signed app). Never synchronizable —
/// the private key must not ride iCloud Keychain.
///
/// Layout: the private key is a permanent keychain key addressed by
/// `kSecAttrLabel`; the certificate DER lives in a generic-password item
/// (service = label). Certificates are NOT stored as `kSecClassCertificate`
/// items — the file-based keychain ignores caller labels on certificate
/// items (it derives them from the subject), which makes them
/// un-addressable. `SecIdentityCreateWithCertificate` does not need the
/// certificate in a keychain, only the matching private key (verified by
/// test on macOS 15).
public final class KeychainIdentityStore {

    public static let defaultLabel = "dev.hyphen.identity"

    private let label: String

    public init(label: String = KeychainIdentityStore.defaultLabel) {
        self.label = label
    }

    /// Returns the stored identity, minting and persisting one first if
    /// none exists. Certificates are minted valid for ~10 years; renewal
    /// keeps the key pair (pin survives) and is an M5 concern.
    public func loadOrCreate(commonName: String = "Hyphen Mac") throws -> DeviceIdentity {
        if let existing = try load() { return existing }

        let attributes: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits as String: 256,
            kSecAttrLabel as String: label,
            kSecPrivateKeyAttrs as String: [
                kSecAttrIsPermanent as String: true,
                kSecAttrLabel as String: label,
            ],
        ]
        var error: Unmanaged<CFError>?
        guard let privateKey = SecKeyCreateRandomKey(attributes as CFDictionary, &error),
              let publicKey = SecKeyCopyPublicKey(privateKey)
        else {
            throw IdentityError.keyGeneration(String(describing: error?.takeRetainedValue()))
        }

        let certificateDER = try SelfSignedCertificate.makeDER(
            privateKey: privateKey,
            publicKey: publicKey,
            commonName: commonName,
            validFrom: Date().addingTimeInterval(-3600), // clock-skew slack
            validForDays: 3650
        )
        guard SecCertificateCreateWithData(nil, certificateDER as CFData) != nil else {
            throw IdentityError.certificateParse
        }

        let addStatus = SecItemAdd([
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: label,
            kSecAttrAccount as String: "certificate",
            kSecValueData as String: certificateDER,
            kSecAttrSynchronizable as String: false,
        ] as CFDictionary, nil)
        guard addStatus == errSecSuccess else { throw IdentityError.keychain(addStatus) }

        guard let loaded = try load() else { throw IdentityError.identityNotConstructible(errSecItemNotFound) }
        return loaded
    }

    /// Removes the stored certificate and private key (tests, identity reset).
    /// Resetting identity invalidates the fingerprint every paired peer has
    /// pinned — callers own re-pairing.
    public func removeIdentity() throws {
        for query in [
            [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: label],
            [kSecClass as String: kSecClassKey, kSecAttrLabel as String: label],
        ] {
            let status = SecItemDelete(query as CFDictionary)
            guard status == errSecSuccess || status == errSecItemNotFound else {
                throw IdentityError.keychain(status)
            }
        }
    }

    private func load() throws -> DeviceIdentity? {
        var certResult: CFTypeRef?
        let certStatus = SecItemCopyMatching([
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: label,
            kSecAttrAccount as String: "certificate",
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ] as CFDictionary, &certResult)
        if certStatus == errSecItemNotFound { return nil }
        guard certStatus == errSecSuccess, let certificateDER = certResult as? Data else {
            throw IdentityError.keychain(certStatus)
        }
        guard let cert = SecCertificateCreateWithData(nil, certificateDER as CFData) else {
            throw IdentityError.certificateParse
        }

        // Finds the matching private key by public-key hash in the same
        // keychain; fails if the key was deleted out from under the cert.
        var identity: SecIdentity?
        let identityStatus = SecIdentityCreateWithCertificate(nil, cert, &identity)
        guard identityStatus == errSecSuccess, let identity else {
            throw IdentityError.identityNotConstructible(identityStatus)
        }

        guard let publicKey = SecCertificateCopyKey(cert) else {
            throw IdentityError.certificateParse
        }
        return DeviceIdentity(
            identity: identity,
            certificateDER: SecCertificateCopyData(cert) as Data,
            spkiFingerprint: try SPKI.fingerprint(for: publicKey)
        )
    }
}
