package dev.hyphen.android.pairing

import javax.net.ssl.SSLSocket
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class PairingIntentRejectReason {
    WRONG_ACTION,
    INVALID_PAYLOAD,
}

sealed interface PairingIntentRoute {
    data class Accepted(val payload: ParsedEndpoint.QrPayload) : PairingIntentRoute
    data class Rejected(val reason: PairingIntentRejectReason) : PairingIntentRoute
}

/** Pure boundary for exported/browsable pairing intents. */
object PairingIntentRouter {
    private const val ACTION_VIEW = "android.intent.action.VIEW"

    fun route(action: String?, data: String?): PairingIntentRoute {
        if (action != ACTION_VIEW) {
            return PairingIntentRoute.Rejected(PairingIntentRejectReason.WRONG_ACTION)
        }
        val payload = data
            ?.let(EndpointParser::parseQr)
            ?.let { it as? ParseResult.Ok }
            ?.endpoint as? ParsedEndpoint.QrPayload
            ?: return PairingIntentRoute.Rejected(PairingIntentRejectReason.INVALID_PAYLOAD)
        return PairingIntentRoute.Accepted(payload)
    }
}

/** Opaque ownership ticket for one provisional TLS/SAS attempt. */
class PairingAttemptToken internal constructor()

/**
 * Process-level, token-scoped ownership for a provisional pairing socket.
 *
 * Multiple Activity entry points share one coordinator. A callback from an
 * older attempt cannot finish or close the socket of a newer attempt because
 * every mutation must present the exact current token.
 */
class PairingAttemptCoordinator {
    private val lock = ReentrantLock()
    private var currentToken: PairingAttemptToken? = null
    private var ownedSocket: SSLSocket? = null
    private var commitClaimed = false
    private var trustCommitted = false
    @Volatile
    private var recreationRequestedToken: PairingAttemptToken? = null
    @Volatile
    private var cancellationRequestedToken: PairingAttemptToken? = null

    fun tryBegin(): PairingAttemptToken? = lock.withLock {
        if (currentToken != null) {
            null
        } else {
            PairingAttemptToken().also {
                currentToken = it
                commitClaimed = false
                trustCommitted = false
            }
        }
    }

    fun isActive(token: PairingAttemptToken): Boolean =
        lock.withLock { currentToken === token }

    fun claimSocket(token: PairingAttemptToken, socket: SSLSocket): Boolean =
        lock.withLock {
            if (currentToken !== token || ownedSocket != null) {
                false
            } else {
                ownedSocket = socket
                true
            }
        }

    fun releaseSocket(token: PairingAttemptToken, socket: SSLSocket): Boolean =
        lock.withLock {
            if (currentToken !== token || ownedSocket !== socket) {
                false
            } else {
                ownedSocket = null
                true
            }
        }

    /** Claims the single SAS decision without holding the lock across wire I/O. */
    fun claimCommit(token: PairingAttemptToken): Boolean = lock.withLock {
        if (currentToken !== token || commitClaimed) {
            false
        } else {
            commitClaimed = true
            true
        }
    }

    /** Runs a trust/route commit only while [token] still owns the attempt. */
    fun <T : Any> runIfActive(token: PairingAttemptToken, action: () -> T): T? =
        lock.withLock {
            if (currentToken !== token) null else action()
        }

    /** Records that trust and its reconnect route were durably committed. */
    fun markTrustCommitted(token: PairingAttemptToken): Boolean = lock.withLock {
        if (currentToken !== token) {
            false
        } else {
            trustCommitted = true
            true
        }
    }

    /**
     * Atomically retires an Activity-owned attempt for configuration change.
     * The decision is made under the same lock as trust commit, so a rotation
     * can never both retain a restart payload and commit that attempt.
     */
    fun tryCancelForRecreation(
        token: PairingAttemptToken,
        payload: ParsedEndpoint.QrPayload?,
    ): PairingRecreationRetirement {
        recreationRequestedToken = token
        if (!lock.tryLock()) {
            // A durable commit owns the monitor. Never make Activity teardown
            // wait for storage. The exact outcome is not yet knowable, so the
            // recreated Activity asks for fresh user input rather than guessing.
            return PairingRecreationRetirement(PairingRecreationDecision.RequireFreshInput, emptyList())
        }
        val socketsToClose = linkedSetOf<SSLSocket>()
        val decision = try {
            if (currentToken !== token) {
                PairingRecreationDecision.Suppress
            } else {
                val result = if (trustCommitted || payload == null) {
                    PairingRecreationDecision.Suppress
                } else {
                    PairingRecreationDecision.Restart(payload)
                }
                currentToken = null
                commitClaimed = false
                trustCommitted = false
                ownedSocket?.let(socketsToClose::add)
                ownedSocket = null
                result
            }
        } finally {
            lock.unlock()
        }
        return PairingRecreationRetirement(decision, socketsToClose.toList())
    }

    fun isRecreationRequested(token: PairingAttemptToken): Boolean =
        recreationRequestedToken === token

    /** Marks lifecycle cancellation without acquiring the durable-commit lock. */
    fun requestCancellation(token: PairingAttemptToken) {
        cancellationRequestedToken = token
    }

    fun isCancellationRequested(token: PairingAttemptToken): Boolean =
        cancellationRequestedToken === token

    fun complete(token: PairingAttemptToken): Boolean = end(token, null)

    fun cancel(token: PairingAttemptToken, candidate: SSLSocket? = null): Boolean =
        end(token, candidate)

    private fun end(token: PairingAttemptToken, candidate: SSLSocket?): Boolean {
        val retirement = retire(token, candidate)
        retirement.closeSockets()
        return retirement.ended
    }

    /**
     * Atomically retires ownership but leaves potentially blocking TLS closure
     * to the caller. Lifecycle owners use this only from a process worker.
     */
    fun retire(
        token: PairingAttemptToken,
        candidate: SSLSocket? = null,
    ): PairingAttemptRetirement {
        val socketsToClose = linkedSetOf<SSLSocket>()
        val ended = lock.withLock {
            if (currentToken !== token) {
                // A stale callback may close only its own unclaimed candidate,
                // never the socket currently owned by a newer attempt.
                if (candidate != null && candidate !== ownedSocket) socketsToClose += candidate
                false
            } else {
                currentToken = null
                commitClaimed = false
                trustCommitted = false
                ownedSocket?.let(socketsToClose::add)
                ownedSocket = null
                candidate?.let(socketsToClose::add)
                true
            }
        }
        return PairingAttemptRetirement(ended, socketsToClose.toList())
    }
}

data class PairingAttemptRetirement(
    val ended: Boolean,
    private val socketsToClose: List<SSLSocket>,
) {
    fun closeSockets() {
        socketsToClose.forEach { socket -> runCatching { socket.close() } }
    }
}

data class PairingRecreationRetirement(
    val decision: PairingRecreationDecision,
    private val socketsToClose: List<SSLSocket>,
) {
    fun closeSockets() {
        socketsToClose.forEach { socket -> runCatching { socket.close() } }
    }
}

sealed interface PairingRecreationDecision {
    data class Restart(val payload: ParsedEndpoint.QrPayload) : PairingRecreationDecision
    data object RequireFreshInput : PairingRecreationDecision
    data object Suppress : PairingRecreationDecision
}

enum class PairingRestoreAction {
    CONSUME_LAUNCH_INTENT,
    REQUIRE_FRESH_INPUT,
    RESTART_HANDOFF,
    SUPPRESS,
}

/**
 * Chooses Activity restore behavior without trying to reconstruct pairing
 * ownership from the launch Intent. Saved state intentionally contains no
 * pairing payload, so process death requires a fresh scan or paste.
 */
fun pairingRestoreAction(
    pairingWasActive: Boolean,
    recreationDecision: PairingRecreationDecision?,
): PairingRestoreAction = when (recreationDecision) {
    is PairingRecreationDecision.Restart -> PairingRestoreAction.RESTART_HANDOFF
    PairingRecreationDecision.RequireFreshInput -> PairingRestoreAction.REQUIRE_FRESH_INPUT
    PairingRecreationDecision.Suppress -> PairingRestoreAction.SUPPRESS
    null -> if (pairingWasActive) {
        PairingRestoreAction.REQUIRE_FRESH_INPUT
    } else {
        PairingRestoreAction.CONSUME_LAUNCH_INTENT
    }
}

/**
 * One-shot, memory-only handoff used when Android recreates the Activity.
 *
 * Pairing material is intentionally never written to preferences or a saved
 * instance-state Bundle. The short expiry prevents an abandoned recreation
 * from unexpectedly reopening a stale attempt on a later Activity launch.
 */
class PairingRecreationHandoff(
    private val nowNanos: () -> Long = System::nanoTime,
    private val ttlNanos: Long = 10_000_000_000L,
) {
    private data class Pending(
        val decision: PairingRecreationDecision,
        val expiresAtNanos: Long,
    )

    private val lock = Any()
    private var pending: Pending? = null

    fun offer(decision: PairingRecreationDecision) = synchronized(lock) {
        pending = Pending(decision, nowNanos() + ttlNanos)
    }

    fun take(): PairingRecreationDecision? = synchronized(lock) {
        val candidate = pending
        pending = null
        candidate?.decision?.takeIf { nowNanos() <= candidate.expiresAtNanos }
    }
}

/** The process-scoped state shared by every MainActivity instance. */
object PairingAttemptRuntime {
    val coordinator = PairingAttemptCoordinator()
    val recreationHandoff = PairingRecreationHandoff()
}
