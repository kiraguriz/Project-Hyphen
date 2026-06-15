package dev.hyphen.android.discovery

/** A resolved `_hyphen._tcp` peer candidate. Discovery is a hint, never trust. */
data class DiscoveredService(val name: String, val host: String, val port: Int)

sealed class DiscoveryEvent {
    data class ServiceResolved(val service: DiscoveredService) : DiscoveryEvent()
    data class ServiceLost(val name: String) : DiscoveryEvent()
    data class Failed(val reason: DiscoveryFailure) : DiscoveryEvent()
    data class WindowEnded(val resolvedCount: Int) : DiscoveryEvent()
}

/** Failure reasons, recorded locally by default (plan §7.6). */
enum class DiscoveryFailure { START_FAILED, STOP_FAILED, RESOLVE_FAILED, ALREADY_RUNNING }

/** Platform mDNS/NSD operations; `AndroidNsdBackend` is the real one. */
interface NsdBackend {
    fun startDiscovery(serviceType: String, callbacks: BackendCallbacks)
    fun stopDiscovery()
    /** Resolve a service previously reported via onServiceFound. */
    fun resolve(serviceName: String)
}

interface BackendCallbacks {
    fun onStartFailed(errorCode: Int)
    fun onStopFailed(errorCode: Int)
    fun onServiceFound(name: String)
    fun onServiceLost(name: String)
    fun onResolved(name: String, host: String, port: Int)
    fun onResolveFailed(name: String, errorCode: Int)
    fun onStopped()
}

/** Injectable timer so window behavior is unit-testable. */
interface Scheduler {
    fun schedule(delayMillis: Long, action: () -> Unit): Any
    fun cancel(token: Any)
}

/**
 * Acquired for the duration of a discovery window, always released
 * (HYP-M1-005 provides the real scoped MulticastLock implementation).
 */
interface DiscoveryLock {
    fun acquire()
    fun release()
}

object NoopDiscoveryLock : DiscoveryLock {
    override fun acquire() = Unit
    override fun release() = Unit
}

/**
 * Time-boxed NSD discovery session (HYP-M1-004, plan §7.6):
 * bounded window, lock held only inside it, one resolve in flight at a
 * time (NsdManager historically rejects concurrent resolves), duplicate
 * suppression, and local failure recording.
 */
class DiscoveryManager(
    private val backend: NsdBackend,
    private val scheduler: Scheduler,
    private val lock: DiscoveryLock = NoopDiscoveryLock,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val onEvent: (DiscoveryEvent) -> Unit,
) : BackendCallbacks {

    private var running = false
    private var windowToken: Any? = null
    private val seen = mutableSetOf<String>()
    private val resolveQueue = ArrayDeque<String>()
    private var resolving = false
    private var resolvedCount = 0

    private val failures = mutableListOf<DiscoveryFailure>()

    /** Local failure record, default-on (plan §7.6); never transmitted. */
    fun failureLog(): List<DiscoveryFailure> = failures.toList()

    fun isRunning(): Boolean = running

    /** Starts one discovery window. Returns false if one is already running. */
    fun start(): Boolean {
        if (running) {
            record(DiscoveryFailure.ALREADY_RUNNING)
            return false
        }
        running = true
        resolvedCount = 0
        seen.clear()
        resolveQueue.clear()
        resolving = false
        lock.acquire()
        windowToken = scheduler.schedule(windowMillis) { stop() }
        backend.startDiscovery(SERVICE_TYPE, this)
        return true
    }

    /** Ends the window. Idempotent; the lock is always released exactly once. */
    fun stop() {
        if (!running) return
        running = false
        windowToken?.let(scheduler::cancel)
        windowToken = null
        try {
            backend.stopDiscovery()
        } finally {
            lock.release()
        }
    }

    override fun onStartFailed(errorCode: Int) {
        record(DiscoveryFailure.START_FAILED)
        // startDiscovery never took effect: unwind without stopDiscovery.
        if (running) {
            running = false
            windowToken?.let(scheduler::cancel)
            windowToken = null
            lock.release()
        }
    }

    override fun onStopFailed(errorCode: Int) {
        record(DiscoveryFailure.STOP_FAILED)
    }

    override fun onServiceFound(name: String) {
        if (!running || name in seen) return
        seen += name
        resolveQueue.addLast(name)
        pumpResolveQueue()
    }

    override fun onServiceLost(name: String) {
        if (!running) return
        seen -= name // allow re-resolve if it comes back
        resolveQueue.remove(name)
        onEvent(DiscoveryEvent.ServiceLost(name))
    }

    override fun onResolved(name: String, host: String, port: Int) {
        if (!running) return
        resolveQueue.remove(name)
        resolving = false
        resolvedCount++
        onEvent(DiscoveryEvent.ServiceResolved(DiscoveredService(name, host, port)))
        pumpResolveQueue()
    }

    override fun onResolveFailed(name: String, errorCode: Int) {
        if (!running) return
        resolveQueue.remove(name)
        resolving = false
        // Stays in `seen`: retry happens in the next window, not a hot loop.
        record(DiscoveryFailure.RESOLVE_FAILED)
        pumpResolveQueue()
    }

    override fun onStopped() {
        onEvent(DiscoveryEvent.WindowEnded(resolvedCount))
    }

    private fun pumpResolveQueue() {
        if (!running || resolving) return
        val next = resolveQueue.firstOrNull() ?: return
        resolving = true
        backend.resolve(next)
    }

    private fun record(reason: DiscoveryFailure) {
        failures += reason
        onEvent(DiscoveryEvent.Failed(reason))
    }

    companion object {
        const val SERVICE_TYPE = "_hyphen._tcp"

        /** Mid-range of the 15–30 s budget from plan §7.6. */
        const val DEFAULT_WINDOW_MILLIS = 20_000L
    }
}
