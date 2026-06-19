package dev.hyphen.android.transport

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket

/**
 * Client-side reconnect with protocol §4.5 backoff (HYP-M2-013): dial →
 * handshake (presenting the held resume token) → session; on loss, retry
 * after 1/5/15/30 s, then every 30 s; a successful connect resets the
 * schedule. Android counterpart of the macOS reconnector built on the
 * M1-014 state machine (sleep/suspend states are macOS concerns).
 */
class SessionReconnector(
    /** Opens a fresh authenticated socket (throws on failure). */
    private val dial: () -> SSLSocket,
    private val device: SessionHandshake.DeviceInfo,
    private val sessionConfig: ProtocolSession.Config = ProtocolSession.Config(),
    private val scheduler: RetryScheduler = ExecutorRetryScheduler(),
    /** Resume token carried over from the prior session, presented on the first dial. */
    initialResumeToken: String? = null,
    initialPreviousSessionId: String? = null,
    private val listener: Listener,
) {
    companion object {
        val BACKOFF_SECONDS = listOf(1L, 5L, 15L, 30L)
    }

    /** Seam so tests drive retries without wall-clock waits. */
    interface RetryScheduler {
        fun schedule(delayMs: Long, action: () -> Unit)
        fun shutdown() {}
    }

    class ExecutorRetryScheduler : RetryScheduler {
        private val executor = Executors.newSingleThreadScheduledExecutor {
            Thread(it, "hyphen-reconnect").apply { isDaemon = true }
        }
        private var pending: ScheduledFuture<*>? = null

        @Synchronized
        override fun schedule(delayMs: Long, action: () -> Unit) {
            pending?.cancel(false)
            pending = executor.schedule(action, delayMs, TimeUnit.MILLISECONDS)
        }

        @Synchronized
        override fun shutdown() {
            executor.shutdownNow()
        }
    }

    interface Listener {
        /** A live session (fresh or resumed). Runs on the dial thread. */
        fun onSession(session: ProtocolSession, handshake: SessionHandshake.Result)
        fun onRetryScheduled(attempt: Int, delaySeconds: Long) {}
        fun onAttemptFailed(reason: String?) {}
        /** Forwarded from the active session. */
        fun onEnvelope(envelope: Envelope) {}
        fun onLiveness(state: HeartbeatMonitor.State) {}
        fun onAck(messageId: String) {}
        fun onAckTimeout(messageId: String) {}
        fun onProtocolError(code: String, detail: String) {}
        /** Active session closed; a retry may follow unless [stop] was called. */
        fun onSessionLost() {}
    }

    private val stopped = AtomicBoolean(false)
    private val stateLock = Any()
    private var consecutiveFailures = 0
    private var resumeToken: String? = initialResumeToken
    private var lastSessionId: String? = initialPreviousSessionId
    private var activeSession: ProtocolSession? = null
    private var connecting = false
    private var attemptInFlight = false
    private var nextAttemptId = 0L

    /** First attempt runs immediately on a scheduler thread. */
    fun start() {
        val attempt = synchronized(stateLock) {
            if (stopped.get() || connecting || activeSession != null) {
                null
            } else {
                scheduleAttemptLocked(0)
            }
        }
        attempt?.let { scheduler.schedule(it.delayMs) { attempt(it.id) } }
    }

    fun stop() {
        if (stopped.getAndSet(true)) return
        val session = synchronized(stateLock) {
            connecting = false
            attemptInFlight = false
            activeSession.also { activeSession = null }
        }
        session?.stop()
        scheduler.shutdown()
    }

    private data class AttemptSnapshot(
        val id: Long,
        val resumeToken: String?,
        val lastSessionId: String?,
    )

    private data class ScheduledAttempt(
        val id: Long,
        val delayMs: Long,
    )

    private data class RetryPlan(
        val attempt: ScheduledAttempt,
        val attemptNumber: Int,
        val delaySeconds: Long,
    )

    private fun attempt(id: Long) {
        val snapshot = synchronized(stateLock) {
            if (
                stopped.get() ||
                id != nextAttemptId ||
                !connecting ||
                attemptInFlight ||
                activeSession != null
            ) {
                return
            }
            attemptInFlight = true
            AttemptSnapshot(id, resumeToken, lastSessionId)
        }
        val handshake: SessionHandshake.Result
        var socket: SSLSocket? = null
        try {
            socket = dial()
            handshake = SessionHandshake.initiate(
                socket = socket,
                device = device,
                resumeToken = snapshot.resumeToken,
                previousSessionId = snapshot.lastSessionId,
            )
        } catch (e: Exception) {
            runCatching { socket?.close() }
            listener.onAttemptFailed(e.message)
            scheduleRetryAfterFailedAttempt(snapshot.id)
            return
        }
        if (stopped.get()) {
            finishAttempt(snapshot.id)
            runCatching { socket.close() }
            return
        }
        val connectedSocket = checkNotNull(socket)

        var sessionRef: ProtocolSession? = null
        val session = ProtocolSession(
            socket = connectedSocket,
            sessionId = handshake.sessionId,
            config = sessionConfig.copy(
                // Hello consumed seq 1 on this connection.
                startingSeq = 1,
            ),
            listener = object : ProtocolSession.Listener {
                override fun onEnvelope(envelope: Envelope) = listener.onEnvelope(envelope)
                override fun onLiveness(state: HeartbeatMonitor.State) = listener.onLiveness(state)
                override fun onAck(messageId: String) = listener.onAck(messageId)
                override fun onAckTimeout(messageId: String) = listener.onAckTimeout(messageId)
                override fun onProtocolError(code: String, detail: String) =
                    listener.onProtocolError(code, detail)

                override fun onClosed() {
                    sessionRef?.let { scheduleRetryAfterSessionClosed(it) }
                }
            },
        )
        sessionRef = session
        val assigned = synchronized(stateLock) {
            attemptInFlight = false
            connecting = false
            if (stopped.get() || snapshot.id != nextAttemptId || activeSession != null) {
                false
            } else {
                // A presented token is spent either way (§4.6 single-use).
                resumeToken = handshake.resumeToken
                lastSessionId = handshake.sessionId
                consecutiveFailures = 0
                activeSession = session
                true
            }
        }
        if (!assigned) {
            runCatching { connectedSocket.close() }
            return
        }
        session.start()
        if (stopped.get()) {
            session.stop()
            return
        }
        listener.onSession(session, handshake)
    }

    private fun finishAttempt(id: Long) {
        synchronized(stateLock) {
            if (id == nextAttemptId) {
                attemptInFlight = false
                connecting = false
            }
        }
    }

    private fun scheduleRetryAfterFailedAttempt(id: Long) {
        val retry = synchronized(stateLock) {
            attemptInFlight = false
            connecting = false
            if (stopped.get() || id != nextAttemptId || activeSession != null) {
                null
            } else {
                nextRetryLocked()
            }
        }
        retry?.schedule()
    }

    private fun scheduleRetryAfterSessionClosed(session: ProtocolSession) {
        val retry = synchronized(stateLock) {
            if (activeSession !== session) {
                null
            } else {
                activeSession = null
                if (stopped.get() || connecting) null else nextRetryLocked()
            }
        }
        listener.onSessionLost()
        retry?.schedule()
    }

    private fun nextRetryLocked(): RetryPlan {
        val delaySeconds = BACKOFF_SECONDS[consecutiveFailures.coerceAtMost(BACKOFF_SECONDS.size - 1)]
        val attemptNumber = consecutiveFailures
        consecutiveFailures++
        return RetryPlan(
            attempt = scheduleAttemptLocked(delaySeconds * 1000),
            attemptNumber = attemptNumber,
            delaySeconds = delaySeconds,
        )
    }

    private fun scheduleAttemptLocked(delayMs: Long): ScheduledAttempt {
        connecting = true
        val id = ++nextAttemptId
        return ScheduledAttempt(id, delayMs)
    }

    private fun RetryPlan.schedule() {
        listener.onRetryScheduled(attemptNumber, delaySeconds)
        scheduler.schedule(attempt.delayMs) { attempt(attempt.id) }
    }
}
