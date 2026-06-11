import Foundation
import Security

/// Responder-side resume tokens (HYP-M2-013, protocol v0 §4.5–4.6,
/// resolving §9.1): a token is a random 32-byte handle (base64url,
/// 43 chars — fits the hello schema's 16–128 pattern), bound to one
/// session AND one peer fingerprint, single-use, expired after 10
/// minutes. In-memory only: tokens deliberately do not survive an app
/// restart (worst case is a fresh session). Trust revocation must call
/// `invalidatePeer`. Tokens are continuity hints, never authentication —
/// the TLS pin check has always already run by the time one is redeemed.
/// Twin of the Android `ResumeTokenStore`.
public final class ResumeTokenStore {

    public static let defaultTTLMs: Int64 = 10 * 60 * 1000

    private struct Entry {
        let sessionId: String
        let peerFingerprint: Data
        let issuedAtMs: Int64
    }

    private let ttlMs: Int64
    private let nowMs: () -> Int64
    private var entries: [String: Entry] = [:]
    private let lock = NSLock()

    public init(
        ttlMs: Int64 = ResumeTokenStore.defaultTTLMs,
        nowMs: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }
    ) {
        self.ttlMs = ttlMs
        self.nowMs = nowMs
    }

    /// Issues a fresh token for resuming `sessionId`; invalidates any
    /// previous token for the same session (one live token per session).
    public func issue(sessionId: String, peerFingerprint: Data) -> String {
        var bytes = Data(count: 32)
        let status = bytes.withUnsafeMutableBytes {
            SecRandomCopyBytes(kSecRandomDefault, 32, $0.baseAddress!)
        }
        precondition(status == errSecSuccess, "SecRandomCopyBytes failed: \(status)")
        let token = bytes.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")

        lock.lock()
        defer { lock.unlock() }
        entries = entries.filter { $0.value.sessionId != sessionId }
        entries[token] = Entry(sessionId: sessionId, peerFingerprint: peerFingerprint, issuedAtMs: nowMs())
        return token
    }

    /// - Returns: the resumable sessionId, or nil (unknown, expired, or
    ///   wrong peer). The token is consumed either way — single-use.
    public func redeem(token: String, peerFingerprint: Data) -> String? {
        lock.lock()
        defer { lock.unlock() }
        guard let entry = entries.removeValue(forKey: token) else { return nil }
        guard nowMs() - entry.issuedAtMs <= ttlMs else { return nil }
        guard entry.peerFingerprint == peerFingerprint else { return nil }
        return entry.sessionId
    }

    /// Trust revocation hook (§4.6): drops every token for the peer.
    public func invalidatePeer(_ peerFingerprint: Data) {
        lock.lock()
        defer { lock.unlock() }
        entries = entries.filter { $0.value.peerFingerprint != peerFingerprint }
    }

    public var liveCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return entries.count
    }
}
