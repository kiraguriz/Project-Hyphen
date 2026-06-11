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
        fun onAckTimeout(messageId: String) {}
        fun onProtocolError(code: String, detail: String) {}
    }

    private val stopped = AtomicBoolean(false)
    private var consecutiveFailures = 0
    private var resumeToken: String? = null
    private var lastSessionId: String? = null
    private var activeSession: ProtocolSession? = null

    /** First attempt runs immediately on a scheduler thread. */
    fun start() {
        scheduler.schedule(0) { attempt() }
    }

    fun stop() {
        if (stopped.getAndSet(true)) return
        activeSession?.stop()
        scheduler.shutdown()
    }

    private fun attempt() {
        if (stopped.get()) return
        val handshake: SessionHandshake.Result
        val socket: SSLSocket
        try {
            socket = dial()
            handshake = SessionHandshake.initiate(
                socket = socket,
                device = device,
                resumeToken = resumeToken,
                previousSessionId = lastSessionId,
            )
        } catch (e: Exception) {
            listener.onAttemptFailed(e.message)
            scheduleRetry()
            return
        }
        // A presented token is spent either way (§4.6 single-use).
        resumeToken = handshake.resumeToken
        lastSessionId = handshake.sessionId
        consecutiveFailures = 0

        val session = ProtocolSession(
            socket = socket,
            sessionId = handshake.sessionId,
            config = sessionConfig.copy(
                // Hello consumed seq 1 on this connection.
                startingSeq = 1,
            ),
            listener = object : ProtocolSession.Listener {
                override fun onEnvelope(envelope: Envelope) = listener.onEnvelope(envelope)
                override fun onLiveness(state: HeartbeatMonitor.State) = listener.onLiveness(state)
                override fun onAckTimeout(messageId: String) = listener.onAckTimeout(messageId)
                override fun onProtocolError(code: String, detail: String) =
                    listener.onProtocolError(code, detail)

                override fun onClosed() {
                    if (!stopped.get()) scheduleRetry()
                }
            },
        )
        activeSession = session
        session.start()
        listener.onSession(session, handshake)
    }

    private fun scheduleRetry() {
        if (stopped.get()) return
        val delaySeconds = BACKOFF_SECONDS[consecutiveFailures.coerceAtMost(BACKOFF_SECONDS.size - 1)]
        val attemptNumber = consecutiveFailures
        consecutiveFailures++
        listener.onRetryScheduled(attemptNumber, delaySeconds)
        scheduler.schedule(delaySeconds * 1000) { attempt() }
    }
}
