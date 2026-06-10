package dev.hyphen.android.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeBackend : NsdBackend {
    var startCalls = 0
    var stopCalls = 0
    var lastServiceType: String? = null
    val resolveRequests = mutableListOf<String>()
    lateinit var cb: BackendCallbacks

    override fun startDiscovery(serviceType: String, callbacks: BackendCallbacks) {
        startCalls++
        lastServiceType = serviceType
        cb = callbacks
    }

    override fun stopDiscovery() {
        stopCalls++
        cb.onStopped()
    }

    override fun resolve(serviceName: String) {
        resolveRequests += serviceName
    }
}

private class FakeScheduler : Scheduler {
    val pending = mutableMapOf<Any, () -> Unit>()
    val cancelled = mutableListOf<Any>()
    private var nextToken = 0

    override fun schedule(delayMillis: Long, action: () -> Unit): Any {
        val token = "t${nextToken++}"
        pending[token] = action
        return token
    }

    override fun cancel(token: Any) {
        cancelled += token
        pending.remove(token)
    }

    fun fireAll() {
        pending.values.toList().forEach { it() }
        pending.clear()
    }
}

private class CountingLock : DiscoveryLock {
    var acquires = 0
    var releases = 0
    override fun acquire() {
        acquires++
    }

    override fun release() {
        releases++
    }
}

class DiscoveryManagerTest {

    private val backend = FakeBackend()
    private val scheduler = FakeScheduler()
    private val lock = CountingLock()
    private val events = mutableListOf<DiscoveryEvent>()

    private fun manager(window: Long = 20_000L) =
        DiscoveryManager(backend, scheduler, lock, window) { events += it }

    @Test
    fun `start begins discovery with hyphen service type and acquires lock`() {
        val m = manager()
        assertTrue(m.start())
        assertEquals(1, backend.startCalls)
        assertEquals("_hyphen._tcp", backend.lastServiceType)
        assertEquals(1, lock.acquires)
        assertEquals(0, lock.releases)
    }

    @Test
    fun `window timeout stops discovery and releases lock exactly once`() {
        val m = manager()
        m.start()
        scheduler.fireAll() // window elapses
        assertEquals(1, backend.stopCalls)
        assertEquals(1, lock.releases)
        assertFalse(m.isRunning())
        assertTrue(events.any { it is DiscoveryEvent.WindowEnded })
        m.stop() // explicit stop after timeout must be a no-op
        assertEquals(1, backend.stopCalls)
        assertEquals(1, lock.releases)
    }

    @Test
    fun `found service is resolved and emitted`() {
        manager().start()
        backend.cb.onServiceFound("Mac")
        assertEquals(listOf("Mac"), backend.resolveRequests)
        backend.cb.onResolved("Mac", "192.168.1.20", 48273)
        val resolved = events.filterIsInstance<DiscoveryEvent.ServiceResolved>().single()
        assertEquals(DiscoveredService("Mac", "192.168.1.20", 48273), resolved.service)
    }

    @Test
    fun `duplicate found is resolved once`() {
        manager().start()
        backend.cb.onServiceFound("Mac")
        backend.cb.onServiceFound("Mac")
        assertEquals(1, backend.resolveRequests.size)
    }

    @Test
    fun `resolves are serialized one at a time`() {
        manager().start()
        backend.cb.onServiceFound("MacA")
        backend.cb.onServiceFound("MacB")
        assertEquals(listOf("MacA"), backend.resolveRequests)
        backend.cb.onResolved("MacA", "192.168.1.20", 1)
        assertEquals(listOf("MacA", "MacB"), backend.resolveRequests)
    }

    @Test
    fun `resolve failure is recorded and queue continues`() {
        val m = manager()
        m.start()
        backend.cb.onServiceFound("MacA")
        backend.cb.onServiceFound("MacB")
        backend.cb.onResolveFailed("MacA", 0)
        assertEquals(listOf(DiscoveryFailure.RESOLVE_FAILED), m.failureLog())
        assertEquals(listOf("MacA", "MacB"), backend.resolveRequests)
        backend.cb.onResolved("MacB", "192.168.1.21", 2)
        assertEquals(1, events.filterIsInstance<DiscoveryEvent.ServiceResolved>().size)
    }

    @Test
    fun `start failure releases lock and records reason`() {
        val m = manager()
        m.start()
        backend.cb.onStartFailed(0)
        assertEquals(listOf(DiscoveryFailure.START_FAILED), m.failureLog())
        assertEquals(1, lock.releases)
        assertFalse(m.isRunning())
        assertEquals(0, backend.stopCalls) // nothing to stop
    }

    @Test
    fun `second start while running is rejected and recorded`() {
        val m = manager()
        assertTrue(m.start())
        assertFalse(m.start())
        assertEquals(listOf(DiscoveryFailure.ALREADY_RUNNING), m.failureLog())
        assertEquals(1, backend.startCalls)
        assertEquals(1, lock.acquires)
    }

    @Test
    fun `lost service is emitted and can be re-resolved when refound`() {
        manager().start()
        backend.cb.onServiceFound("Mac")
        backend.cb.onResolved("Mac", "192.168.1.20", 1)
        backend.cb.onServiceLost("Mac")
        assertTrue(events.any { it is DiscoveryEvent.ServiceLost })
        backend.cb.onServiceFound("Mac")
        assertEquals(listOf("Mac", "Mac"), backend.resolveRequests)
    }

    @Test
    fun `stop is idempotent when never started`() {
        val m = manager()
        m.stop()
        assertEquals(0, backend.stopCalls)
        assertEquals(0, lock.releases)
    }
}
