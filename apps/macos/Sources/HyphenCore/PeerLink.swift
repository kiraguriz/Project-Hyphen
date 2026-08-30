import Foundation
import Security

/// Minimal v0 routing record. Pairing nonces, session ids, and resume tokens
/// are intentionally never persisted; cold start performs a fresh mTLS session.
public struct RememberedPeerLink: Codable, Equatable {
    public let peerFingerprint: Data
    public let listenPort: UInt16

    public init(peerFingerprint: Data, listenPort: UInt16) {
        self.peerFingerprint = peerFingerprint
        self.listenPort = listenPort
    }
}

public struct RestoredPeerLink: Equatable {
    public let listenPort: UInt16
    public let peer: TrustedPeer

    public init(listenPort: UInt16, peer: TrustedPeer) {
        self.listenPort = listenPort
        self.peer = peer
    }
}

public enum PeerLinkError: Error, Equatable {
    case invalidFingerprintLength(Int)
    case invalidPort
    case untrustedPeer
    case corruptRecord
    case keychain(OSStatus)
}

public protocol PeerLinkStore {
    func save(_ descriptor: RememberedPeerLink) throws
    func load() throws -> RememberedPeerLink?
    func clear() throws
}

/// Lifecycle seam shared by the app delegate, pairing controller, and wake path.
public protocol PeerLinkLifecycle {
    func remember(listenPort: UInt16, peerFingerprint: Data) throws
    func replaceCurrent(listenPort: UInt16, peerFingerprint: Data) throws
    func restoreTrusted() throws -> RestoredPeerLink?
    func invalidate() throws
}

public extension PeerLinkLifecycle {
    func replaceCurrent(listenPort: UInt16, peerFingerprint: Data) throws {
        try invalidate()
        try remember(listenPort: listenPort, peerFingerprint: peerFingerprint)
    }
}

public final class PersistentPeerLinkLifecycle: PeerLinkLifecycle {
    private let store: PeerLinkStore
    private let trustStore: PeerTrustStore
    private let lock = NSRecursiveLock()

    public init(store: PeerLinkStore, trustStore: PeerTrustStore) {
        self.store = store
        self.trustStore = trustStore
    }

    public func remember(listenPort: UInt16, peerFingerprint: Data) throws {
        lock.lock()
        defer { lock.unlock() }
        guard listenPort != 0 else { throw PeerLinkError.invalidPort }
        guard peerFingerprint.count == KeychainTrustStore.fingerprintLength else {
            throw PeerLinkError.invalidFingerprintLength(peerFingerprint.count)
        }
        guard try trustStore.peer(withFingerprint: peerFingerprint) != nil else {
            throw PeerLinkError.untrustedPeer
        }
        do {
            try store.save(RememberedPeerLink(peerFingerprint: peerFingerprint, listenPort: listenPort))
        } catch {
            // If replacement failed before committing, the old current
            // descriptor may still exist. Remove it so a later cold start is
            // fail-closed instead of selecting the superseded peer.
            try? store.clear()
            throw error
        }
    }

    public func restoreTrusted() throws -> RestoredPeerLink? {
        lock.lock()
        defer { lock.unlock() }
        guard let descriptor = try store.load() else { return nil }
        guard let peer = try trustStore.peer(withFingerprint: descriptor.peerFingerprint) else {
            try store.clear()
            return nil
        }
        return RestoredPeerLink(listenPort: descriptor.listenPort, peer: peer)
    }

    public func replaceCurrent(listenPort: UInt16, peerFingerprint: Data) throws {
        lock.lock()
        defer { lock.unlock() }
        // Clear first: a crash between removal and the new save leaves no
        // current route, never the superseded peer. The recursive lock keeps a
        // concurrent revoke ordered after the entire replacement.
        try store.clear()
        try remember(listenPort: listenPort, peerFingerprint: peerFingerprint)
    }

    public func invalidate() throws {
        lock.lock()
        defer { lock.unlock() }
        try store.clear()
    }
}

/// Non-synchronizing Keychain store for the current trusted peer's listener port.
public final class KeychainPeerLinkStore: PeerLinkStore {
    public static let defaultService = "dev.hyphen.peer-link"
    private static let account = "current"
    private let service: String

    public init(service: String = KeychainPeerLinkStore.defaultService) {
        self.service = service
    }

    public func save(_ descriptor: RememberedPeerLink) throws {
        try validate(descriptor)
        guard let data = try? JSONEncoder().encode(descriptor) else {
            throw PeerLinkError.corruptRecord
        }
        var status = SecItemUpdate(
            baseQuery() as CFDictionary,
            [kSecValueData as String: data] as CFDictionary
        )
        if status == errSecItemNotFound {
            var attributes = baseQuery()
            attributes[kSecValueData as String] = data
            status = SecItemAdd(attributes as CFDictionary, nil)
        }
        guard status == errSecSuccess else { throw PeerLinkError.keychain(status) }
    }

    public func load() throws -> RememberedPeerLink? {
        var query = baseQuery()
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else {
            throw PeerLinkError.keychain(status)
        }
        guard let descriptor = try? JSONDecoder().decode(RememberedPeerLink.self, from: data) else {
            throw PeerLinkError.corruptRecord
        }
        try validate(descriptor)
        return descriptor
    }

    public func clear() throws {
        let status = SecItemDelete(baseQuery() as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw PeerLinkError.keychain(status)
        }
    }

    private func validate(_ descriptor: RememberedPeerLink) throws {
        guard descriptor.peerFingerprint.count == KeychainTrustStore.fingerprintLength else {
            throw PeerLinkError.invalidFingerprintLength(descriptor.peerFingerprint.count)
        }
        guard descriptor.listenPort != 0 else { throw PeerLinkError.invalidPort }
    }

    private func baseQuery() -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: Self.account,
            kSecAttrSynchronizable as String: false,
        ]
    }
}
