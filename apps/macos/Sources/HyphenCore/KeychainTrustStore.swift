import Foundation
import Security

/// Keychain-backed peer trust store (HYP-M2-005, plan §8.2 HyphenCore).
///
/// v0 uses the login keychain via generic-password items (service =
/// `dev.hyphen.peers`, account = fingerprint hex, value = JSON record),
/// which works for unsigned dev builds and tests. Migration to the
/// data-protection keychain (`kSecUseDataProtectionKeychain`) is queued
/// for the signed app (ADR-0002 / M5) — it needs entitlements.
/// Items are never synchronizable: trust must not ride iCloud Keychain.
public final class KeychainTrustStore: PeerTrustStore {

    public static let fingerprintLength = 32
    public static let defaultService = "dev.hyphen.peers"

    private let service: String

    public init(service: String = KeychainTrustStore.defaultService) {
        self.service = service
    }

    public func add(_ peer: TrustedPeer) throws {
        try validate(peer.spkiFingerprint)
        let data: Data
        do {
            data = try JSONEncoder().encode(peer)
        } catch {
            throw TrustStoreError.corruptRecord
        }

        var status = SecItemUpdate(
            baseQuery(account: peer.spkiFingerprint.hyphenHexString) as CFDictionary,
            [kSecValueData as String: data] as CFDictionary
        )
        if status == errSecItemNotFound {
            var attributes = baseQuery(account: peer.spkiFingerprint.hyphenHexString)
            attributes[kSecValueData as String] = data
            status = SecItemAdd(attributes as CFDictionary, nil)
        }
        guard status == errSecSuccess else { throw TrustStoreError.keychain(status) }
    }

    public func peer(withFingerprint fingerprint: Data) throws -> TrustedPeer? {
        try validate(fingerprint)
        var query = baseQuery(account: fingerprint.hyphenHexString)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else {
            throw TrustStoreError.keychain(status)
        }
        guard let peer = try? JSONDecoder().decode(TrustedPeer.self, from: data) else {
            throw TrustStoreError.corruptRecord
        }
        return peer
    }

    @discardableResult
    public func remove(fingerprint: Data) throws -> Bool {
        try validate(fingerprint)
        let status = SecItemDelete(baseQuery(account: fingerprint.hyphenHexString) as CFDictionary)
        if status == errSecItemNotFound { return false }
        guard status == errSecSuccess else { throw TrustStoreError.keychain(status) }
        return true
    }

    public func allPeers() throws -> [TrustedPeer] {
        // macOS rejects kSecReturnData + kSecMatchLimitAll for generic
        // passwords (errSecParam): list accounts first, then fetch each.
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecReturnAttributes as String: true,
            kSecMatchLimit as String: kSecMatchLimitAll,
        ]

        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return [] }
        guard status == errSecSuccess, let items = result as? [[String: Any]] else {
            throw TrustStoreError.keychain(status)
        }
        return try items.compactMap { attributes in
            guard let account = attributes[kSecAttrAccount as String] as? String,
                  let fingerprint = Data(hyphenHexString: account)
            else { return nil }
            return try peer(withFingerprint: fingerprint)
        }
    }

    /// Removes every peer in this store's service (tests, trust reset).
    public func removeAll() throws {
        let status = SecItemDelete([
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
        ] as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw TrustStoreError.keychain(status)
        }
    }

    private func validate(_ fingerprint: Data) throws {
        guard fingerprint.count == Self.fingerprintLength else {
            throw TrustStoreError.invalidFingerprintLength(fingerprint.count)
        }
    }

    private func baseQuery(account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            // Trust never syncs across devices via iCloud Keychain.
            kSecAttrSynchronizable as String: false,
        ]
    }
}
