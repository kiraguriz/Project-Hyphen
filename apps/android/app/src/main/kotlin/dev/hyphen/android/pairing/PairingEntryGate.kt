package dev.hyphen.android.pairing

import javax.net.ssl.SSLSocket

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
    private val lock = Any()
    private var currentToken: PairingAttemptToken? = null
    private var ownedSocket: SSLSocket? = null
    private var commitClaimed = false

    fun tryBegin(): PairingAttemptToken? = synchronized(lock) {
        if (currentToken != null) {
            null
        } else {
            PairingAttemptToken().also {
                currentToken = it
                commitClaimed = false
            }
        }
    }

    fun isActive(token: PairingAttemptToken): Boolean =
        synchronized(lock) { currentToken === token }

    fun claimSocket(token: PairingAttemptToken, socket: SSLSocket): Boolean =
        synchronized(lock) {
            if (currentToken !== token || ownedSocket != null) {
                false
            } else {
                ownedSocket = socket
                true
            }
        }

    fun releaseSocket(token: PairingAttemptToken, socket: SSLSocket): Boolean =
        synchronized(lock) {
            if (currentToken !== token || ownedSocket !== socket) {
                false
            } else {
                ownedSocket = null
                true
            }
        }

    /** Claims the single SAS decision without holding the lock across wire I/O. */
    fun claimCommit(token: PairingAttemptToken): Boolean = synchronized(lock) {
        if (currentToken !== token || commitClaimed) {
            false
        } else {
            commitClaimed = true
            true
        }
    }

    /** Runs a trust/route commit only while [token] still owns the attempt. */
    fun <T : Any> runIfActive(token: PairingAttemptToken, action: () -> T): T? =
        synchronized(lock) {
            if (currentToken !== token) null else action()
        }

    fun complete(token: PairingAttemptToken): Boolean = end(token, null)

    fun cancel(token: PairingAttemptToken, candidate: SSLSocket? = null): Boolean =
        end(token, candidate)

    private fun end(token: PairingAttemptToken, candidate: SSLSocket?): Boolean {
        val socketsToClose = linkedSetOf<SSLSocket>()
        val ended = synchronized(lock) {
            if (currentToken !== token) {
                // A stale callback may close only its own unclaimed candidate,
                // never the socket currently owned by a newer attempt.
                if (candidate != null && candidate !== ownedSocket) socketsToClose += candidate
                false
            } else {
                currentToken = null
                commitClaimed = false
                ownedSocket?.let(socketsToClose::add)
                ownedSocket = null
                candidate?.let(socketsToClose::add)
                true
            }
        }
        socketsToClose.forEach { socket -> runCatching { socket.close() } }
        return ended
    }
}

/** The single coordinator shared by every MainActivity instance in this process. */
object PairingAttemptRuntime {
    val coordinator = PairingAttemptCoordinator()
}
