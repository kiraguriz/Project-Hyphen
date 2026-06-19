import Foundation

/// Tracks the single provisional pairing peer between TLS-handshake admission
/// and SAS confirmation.
///
/// A peer's SPKI fingerprint is captured inside the TLS verify block — *before*
/// its `NWConnection` is delivered to the controller — so this state bridges the
/// gap from "fingerprint claimed during handshake" to "connection attached and
/// shown in SAS". Bridging it here lets a dropped or aborted attempt be
/// reclaimed by a retry without restarting the listener (HYP review: pairing
/// wedged after a provisional TLS drop before SAS confirm).
///
/// Lifecycle: `claimFingerprint` (verify block) → `attachConnection` (connection
/// ready) → `reset` (user confirmed) or `releaseConnection` (connection dropped
/// before confirm). The type is not thread-safe; the owner serializes access
/// (`PairingController` holds a lock around it).
public struct ProvisionalPairingState: Equatable {

    /// How long a pre-attach claim blocks a second fingerprint (security review M-02).
    public static let pendingClaimTimeoutMs: Int64 = 60_000

    public private(set) var pendingFingerprint: Data?
    public private(set) var pendingClaimedAtMs: Int64?
    public private(set) var connectionID: ObjectIdentifier?

    public init() {
        pendingFingerprint = nil
        pendingClaimedAtMs = nil
        connectionID = nil
    }

    /// True once a live connection has attached and is awaiting SAS confirmation.
    public var hasAttachedConnection: Bool { connectionID != nil }

    /// Claim the provisional slot for a fingerprint seen in the TLS verify
    /// block. Succeeds when no connection has attached yet and the slot is free
    /// or the prior pre-attach claim timed out. A second fingerprint is rejected
    /// while a pending claim is still live (M-02).
    public mutating func claimFingerprint(_ fingerprint: Data, nowMs: Int64? = nil) -> Bool {
        let clock = nowMs ?? Self.currentEpochMs()
        guard connectionID == nil else { return false }
        releaseExpiredPendingClaim(nowMs: clock)
        if let pending = pendingFingerprint, pending != fingerprint {
            return false
        }
        if pendingFingerprint == nil {
            pendingFingerprint = fingerprint
            pendingClaimedAtMs = clock
        }
        return true
    }

    /// Bind a live connection to the pending fingerprint. Returns the fingerprint
    /// to confirm, or nil when there is no pending claim or a connection has
    /// already attached (the caller should drop the surplus connection).
    public mutating func attachConnection(_ id: ObjectIdentifier, nowMs: Int64? = nil) -> Data? {
        let clock = nowMs ?? Self.currentEpochMs()
        releaseExpiredPendingClaim(nowMs: clock)
        guard let fingerprint = pendingFingerprint, connectionID == nil else { return nil }
        connectionID = id
        return fingerprint
    }

    /// Release the slot when the attached provisional connection drops before
    /// confirmation, so a retry can claim again. A no-op for any other
    /// connection (e.g. a rejected concurrent peer or the post-confirm session
    /// connection). Returns true only when the attached connection was released.
    public mutating func releaseConnection(_ id: ObjectIdentifier) -> Bool {
        guard connectionID == id else { return false }
        pendingFingerprint = nil
        pendingClaimedAtMs = nil
        connectionID = nil
        return true
    }

    /// Clear the slot at SAS confirmation, after the owner has taken the
    /// connection for the steady-state session.
    public mutating func reset() {
        pendingFingerprint = nil
        pendingClaimedAtMs = nil
        connectionID = nil
    }

    private mutating func releaseExpiredPendingClaim(nowMs: Int64) {
        guard connectionID == nil,
              let claimedAt = pendingClaimedAtMs,
              nowMs - claimedAt > Self.pendingClaimTimeoutMs else { return }
        pendingFingerprint = nil
        pendingClaimedAtMs = nil
    }

    private static func currentEpochMs() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }
}
