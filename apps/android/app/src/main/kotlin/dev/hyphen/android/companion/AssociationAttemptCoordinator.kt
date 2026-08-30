package dev.hyphen.android.companion

/** Opaque ownership ticket for one platform association request. */
class AssociationAttemptToken internal constructor() {
    internal var completedAssociationId: Int? = null
}

data class PendingAssociationApproval<A>(
    val token: AssociationAttemptToken,
    val approval: A,
)

sealed interface AssociationAttemptCompletion {
    data class First(val peerFingerprint: ByteArray) : AssociationAttemptCompletion
    data object DuplicateSame : AssociationAttemptCompletion
    data object Stale : AssociationAttemptCompletion
}

/**
 * Process-scoped ownership for a self-managed CDM association attempt.
 *
 * Activity recreation must not permit a second request while the first
 * platform callback is live. Every pending/created callback presents its
 * token, so a callback from a destroyed Activity can never consume the peer
 * identity owned by a newer request.
 */
class AssociationAttemptCoordinator<A> {
    private val lock = Any()
    private var currentToken: AssociationAttemptToken? = null
    private var peerFingerprint: ByteArray? = null
    private var pendingApproval: A? = null
    private var approvalClaimed = false
    private var approvalListener: (() -> Unit)? = null

    fun tryBegin(fingerprint: ByteArray): AssociationAttemptToken? {
        require(fingerprint.size == 32) { "peer fingerprint must be 32 bytes" }
        return synchronized(lock) {
            if (currentToken != null) {
                null
            } else {
                AssociationAttemptToken().also { token ->
                    currentToken = token
                    peerFingerprint = fingerprint.copyOf()
                    pendingApproval = null
                    approvalClaimed = false
                }
            }
        }
    }

    fun isActive(token: AssociationAttemptToken): Boolean =
        synchronized(lock) { currentToken === token }

    /**
     * Stores a platform approval without retaining the Activity that received
     * the callback. The current Activity listener is invoked outside [lock].
     */
    fun offerApproval(token: AssociationAttemptToken, approval: A): Boolean {
        var listener: (() -> Unit)? = null
        val accepted = synchronized(lock) {
            if (currentToken !== token) {
                false
            } else {
                if (pendingApproval == null) {
                    pendingApproval = approval
                    listener = approvalListener
                }
                true
            }
        }
        listener?.invoke()
        return accepted
    }

    /** Installs the visible Activity owner and replays any unclaimed approval. */
    fun attachApprovalListener(listener: () -> Unit) {
        val shouldNotify = synchronized(lock) {
            approvalListener = listener
            pendingApproval != null && !approvalClaimed
        }
        if (shouldNotify) listener()
    }

    fun detachApprovalListener(listener: () -> Unit) {
        synchronized(lock) {
            if (approvalListener === listener) approvalListener = null
        }
    }

    /** The system approval launcher is one-shot for the current Activity owner. */
    fun claimPendingApproval(): PendingAssociationApproval<A>? = synchronized(lock) {
        val token = currentToken ?: return@synchronized null
        val approval = pendingApproval ?: return@synchronized null
        if (approvalClaimed) {
            null
        } else {
            approvalClaimed = true
            PendingAssociationApproval(token, approval)
        }
    }

    /** Makes a failed/dropped launch available to the next visible Activity. */
    fun releaseApprovalClaim(token: AssociationAttemptToken): Boolean = synchronized(lock) {
        if (currentToken !== token || !approvalClaimed || pendingApproval == null) {
            false
        } else {
            approvalClaimed = false
            true
        }
    }

    /**
     * An IntentSender that failed to launch is not reusable. Abandon this
     * request so the user can start again; any late association is stale and
     * will be disassociated by the terminal callback path.
     */
    fun abandonAfterApprovalLaunchFailure(token: AssociationAttemptToken): Boolean = cancel(token)

    /**
     * Consumes the peer binding exactly once. A duplicate terminal callback
     * for the same association is idempotent; a different/stale id is orphaned.
     */
    fun complete(
        token: AssociationAttemptToken,
        associationId: Int,
    ): AssociationAttemptCompletion = synchronized(lock) {
        if (token.completedAssociationId == associationId) {
            return@synchronized AssociationAttemptCompletion.DuplicateSame
        }
        if (currentToken !== token) {
            return@synchronized AssociationAttemptCompletion.Stale
        }
        val fingerprint = peerFingerprint?.copyOf()
            ?: return@synchronized AssociationAttemptCompletion.Stale
        token.completedAssociationId = associationId
        clearLocked()
        AssociationAttemptCompletion.First(fingerprint)
    }

    fun cancel(token: AssociationAttemptToken): Boolean = synchronized(lock) {
        if (currentToken !== token) {
            false
        } else {
            clearLocked()
            true
        }
    }

    private fun clearLocked() {
        currentToken = null
        peerFingerprint = null
        pendingApproval = null
        approvalClaimed = false
    }
}

/** The single association owner shared by every MainActivity instance. */
object AssociationAttemptRuntime {
    val coordinator = AssociationAttemptCoordinator<AssociationApprovalRequest>()
}
