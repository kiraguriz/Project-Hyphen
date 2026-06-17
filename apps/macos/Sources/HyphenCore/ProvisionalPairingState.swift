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
    public private(set) var pendingFingerprint: Data?
    public private(set) var connectionID: ObjectIdentifier?

    public init() {
        pendingFingerprint = nil
        connectionID = nil
    }

    /// True once a live connection has attached and is awaiting SAS confirmation.
    public var hasAttachedConnection: Bool { connectionID != nil }

    /// Claim the provisional slot for a fingerprint seen in the TLS verify
    /// block. Succeeds when no connection has attached yet — a free slot, or a
    /// prior claim whose connection never arrived (a dropped pre-SAS attempt),
    /// which is overwritten so a retry can proceed. Rejected once a connection
    /// has attached, so only one peer is ever in SAS confirmation at a time.
    public mutating func claimFingerprint(_ fingerprint: Data) -> Bool {
        guard connectionID == nil else { return false }
        pendingFingerprint = fingerprint
        return true
    }

    /// Bind a live connection to the pending fingerprint. Returns the fingerprint
    /// to confirm, or nil when there is no pending claim or a connection has
    /// already attached (the caller should drop the surplus connection).
    public mutating func attachConnection(_ id: ObjectIdentifier) -> Data? {
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
        connectionID = nil
        return true
    }

    /// Clear the slot at SAS confirmation, after the owner has taken the
    /// connection for the steady-state session.
    public mutating func reset() {
        pendingFingerprint = nil
        connectionID = nil
    }
}
