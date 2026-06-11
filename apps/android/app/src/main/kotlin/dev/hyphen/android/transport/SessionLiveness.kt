package dev.hyphen.android.transport

/**
 * Receive-silence watchdog (protocol v0 §4, HYP-M2-012): DEGRADED after
 * `missThreshold` heartbeat intervals with no envelope received (the
 * spec's "two consecutive missed heartbeats" — peers send heartbeats
 * every interval, so silence is the observable). Any received envelope
 * recovers to HEALTHY. Pure logic; time is injected.
 */
class HeartbeatMonitor(
    private val intervalMs: Long,
    private val missThreshold: Int = 2,
    startedAtMs: Long,
    private val onStateChange: (State) -> Unit,
) {
    enum class State { HEALTHY, DEGRADED }

    var state: State = State.HEALTHY
        private set

    private var lastReceivedAtMs = startedAtMs

    @Synchronized
    fun envelopeReceived(nowMs: Long) {
        lastReceivedAtMs = nowMs
        if (state == State.DEGRADED) {
            state = State.HEALTHY
            onStateChange(state)
        }
    }

    @Synchronized
    fun tick(nowMs: Long) {
        if (state == State.HEALTHY && nowMs - lastReceivedAtMs > intervalMs * missThreshold) {
            state = State.DEGRADED
            onStateChange(state)
        }
    }
}

/**
 * Tracks sent `requiresAck` envelopes (protocol v0 §3): if no ack arrives
 * within `timeoutMs`, the sender treats it as `protocol/ack-timeout`.
 * Each timeout fires exactly once. Pure logic; time is injected.
 */
class AckTracker(
    private val timeoutMs: Long,
    private val onTimeout: (messageId: String) -> Unit,
) {
    private val pending = LinkedHashMap<String, Long>()

    @Synchronized
    fun registerSent(messageId: String, nowMs: Long) {
        pending[messageId] = nowMs
    }

    @Synchronized
    fun ackReceived(ackOf: String): Boolean = pending.remove(ackOf) != null

    fun tick(nowMs: Long) {
        val expired: List<String>
        synchronized(this) {
            expired = pending.filterValues { sentAt -> nowMs - sentAt > timeoutMs }.keys.toList()
            expired.forEach(pending::remove)
        }
        expired.forEach(onTimeout)
    }

    @Synchronized
    fun pendingCount(): Int = pending.size
}
