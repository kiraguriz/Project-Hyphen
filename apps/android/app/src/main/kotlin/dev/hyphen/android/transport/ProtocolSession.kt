package dev.hyphen.android.transport

import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * Steady-state protocol session over an authenticated TLS socket
 * (HYP-M2-012, protocol v0 §4): heartbeats every interval, receive-
 * silence watchdog (degraded after 2 missed intervals), automatic acks
 * for `requiresAck` envelopes, ack-timeout detection, per-sender seq.
 * Hello/capability negotiation and reconnect/resume land with M2-013.
 */
class ProtocolSession(
    private val socket: SSLSocket,
    private val sessionId: String,
    private val config: Config = Config(),
    private val listener: Listener,
) {
    data class Config(
        /** Protocol §4 default 10 s; tests shrink it. */
        val heartbeatIntervalMs: Long = 10_000,
        val missThreshold: Int = 2,
        val ackTimeoutMs: Long = 10_000,
        /** Test hook only: false simulates a protocol-violating silent peer. */
        val autoAck: Boolean = true,
        /** Seq already consumed on this connection (1 after a hello). */
        val startingSeq: Long = 0,
    )

    interface Listener {
        /** Plugin/feature envelopes; core types (heartbeat/ack) stay internal. */
        fun onEnvelope(envelope: Envelope) {}
        fun onLiveness(state: HeartbeatMonitor.State) {}
        /** A tracked requiresAck envelope was acknowledged by the peer. */
        fun onAck(messageId: String) {}
        fun onAckTimeout(messageId: String) {}
        fun onProtocolError(code: String, detail: String) {}
        fun onClosed() {}
    }

    private val seq = AtomicLong(config.startingSeq)
    private val closed = AtomicBoolean(false)
    private val writeLock = Any()
    private var nextInboundSeq = config.startingSeq + 1
    private lateinit var scheduler: ScheduledExecutorService

    private val monitor = HeartbeatMonitor(
        intervalMs = config.heartbeatIntervalMs,
        missThreshold = config.missThreshold,
        startedAtMs = monotonicNowMs(),
        onStateChange = listener::onLiveness,
    )
    private val ackTracker = AckTracker(config.ackTimeoutMs, listener::onAckTimeout)

    @Synchronized
    fun start() {
        if (closed.get()) return
        // Read deadline at 2x the degraded threshold: with live heartbeats
        // it never trips; on a truly dead link the reader wakes itself up.
        // It also bounds a JSSE hazard — SSLSocket.close() from another
        // thread can block until a concurrent blocking read times out, so
        // an unbounded read would make stop() hang and the peer never see
        // the FIN (found via the M2-013 reconnect integration test).
        runCatching {
            socket.soTimeout = (config.heartbeatIntervalMs * (config.missThreshold + 2))
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "hyphen-session-timer").apply { isDaemon = true }
        }
        scheduler.scheduleAtFixedRate(
            { runCatching { send(Envelope.TYPE_HEARTBEAT) } },
            config.heartbeatIntervalMs, config.heartbeatIntervalMs, TimeUnit.MILLISECONDS,
        )
        val tickMs = (config.heartbeatIntervalMs / 2).coerceAtLeast(1)
        scheduler.scheduleAtFixedRate(
            {
                val now = monotonicNowMs()
                monitor.tick(now)
                ackTracker.tick(now)
            },
            tickMs, tickMs, TimeUnit.MILLISECONDS,
        )
        thread(isDaemon = true, name = "hyphen-session-reader") { readLoop() }
    }

    /** @return the sent messageId (registered for ack tracking if required). */
    fun send(
        type: String,
        payload: Json.Obj = Json.Obj.EMPTY,
        requiresAck: Boolean = false,
        capability: String? = null,
        ackOf: String? = null,
    ): String {
        val wallNow = wallNowMs()
        val timeoutNow = monotonicNowMs()
        val envelope = Envelope(
            messageId = Ulid.generate(wallNow),
            sessionId = sessionId,
            type = type,
            capability = capability,
            seq = seq.incrementAndGet(),
            ackOf = ackOf,
            sentAtUnixMs = wallNow,
            requiresAck = requiresAck,
            payload = payload,
        )
        val bytes = envelope.encode()
        if (requiresAck) ackTracker.registerSent(envelope.messageId, timeoutNow)
        try {
            synchronized(writeLock) { FrameIO.write(socket.outputStream, bytes) }
        } catch (e: IOException) {
            if (requiresAck) ackTracker.unregisterSent(envelope.messageId)
            close()
            throw e
        }
        return envelope.messageId
    }

    fun stop() = close()

    private fun readLoop() {
        while (!closed.get()) {
            val frame = try {
                FrameIO.read(socket.inputStream) ?: break
            } catch (e: FrameIO.FrameTooLarge) {
                listener.onProtocolError("transport/frame-too-large", e.message ?: "")
                break
            } catch (_: IOException) {
                break
            }
            val envelope = try {
                Envelope.decode(frame)
            } catch (e: EnvelopeException) {
                listener.onProtocolError("protocol/invalid-envelope", e.message ?: "")
                break
            }
            if (!validateSessionAndSeq(envelope)) break
            monitor.envelopeReceived(monotonicNowMs())
            when (envelope.type) {
                Envelope.TYPE_ACK -> envelope.ackOf?.let { if (ackTracker.ackReceived(it)) listener.onAck(it) }
                Envelope.TYPE_HEARTBEAT -> Unit // liveness already recorded
                else -> {
                    if (envelope.requiresAck && config.autoAck) {
                        runCatching { send(Envelope.TYPE_ACK, ackOf = envelope.messageId) }
                    }
                    listener.onEnvelope(envelope)
                }
            }
        }
        close()
    }

    private fun validateSessionAndSeq(envelope: Envelope): Boolean {
        if (envelope.type == Envelope.TYPE_HELLO) {
            listener.onProtocolError("protocol/invalid-envelope", "hello is only valid during handshake")
            return false
        }
        if (envelope.sessionId != sessionId) {
            listener.onProtocolError("protocol/invalid-envelope", "sessionId does not match active session")
            return false
        }
        if (envelope.seq != nextInboundSeq) {
            listener.onProtocolError(
                "protocol/invalid-envelope",
                "expected seq $nextInboundSeq, got ${envelope.seq}",
            )
            return false
        }
        nextInboundSeq += 1
        return true
    }

    private fun monotonicNowMs(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime())

    private fun wallNowMs(): Long = System.currentTimeMillis()

    @Synchronized
    private fun close() {
        if (closed.getAndSet(true)) return
        if (::scheduler.isInitialized) scheduler.shutdownNow()
        // onClosed reports terminal session state; socket close is best-effort and may block in JSSE.
        listener.onClosed()
        runCatching { socket.close() }
    }
}
