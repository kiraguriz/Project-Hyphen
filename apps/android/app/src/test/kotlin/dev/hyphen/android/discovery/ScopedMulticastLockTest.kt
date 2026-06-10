package dev.hyphen.android.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeHandle : MulticastLockHandle {
    var acquires = 0
    var releases = 0

    override fun acquire() {
        acquires++
    }

    override fun release() {
        releases++
    }
}

class ScopedMulticastLockTest {

    private val handle = FakeHandle()
    private val transitions = mutableListOf<LockTransition>()
    private val lock = ScopedMulticastLock(handle) { transitions += it }

    @Test
    fun `acquire then release drives the platform lock once each`() {
        lock.acquire()
        assertTrue(lock.isHeld)
        lock.release()
        assertFalse(lock.isHeld)
        assertEquals(1, handle.acquires)
        assertEquals(1, handle.releases)
        assertEquals(listOf(LockTransition.ACQUIRED, LockTransition.RELEASED), transitions)
    }

    @Test
    fun `double acquire holds once`() {
        lock.acquire()
        lock.acquire()
        assertEquals(1, handle.acquires)
        assertEquals(listOf(LockTransition.ACQUIRED), transitions)
    }

    @Test
    fun `double release never over-releases the platform lock`() {
        lock.acquire()
        lock.release()
        lock.release()
        assertEquals(1, handle.releases)
        assertEquals(listOf(LockTransition.ACQUIRED, LockTransition.RELEASED), transitions)
    }

    @Test
    fun `release without acquire is a no-op`() {
        lock.release()
        assertEquals(0, handle.releases)
        assertTrue(transitions.isEmpty())
    }

    // --- integration: the lock is scoped exactly to a discovery window -----

    private class WindowBackend : NsdBackend {
        lateinit var cb: BackendCallbacks
        override fun startDiscovery(serviceType: String, callbacks: BackendCallbacks) {
            cb = callbacks
        }

        override fun stopDiscovery() {
            cb.onStopped()
        }

        override fun resolve(serviceName: String) = Unit
    }

    private class ManualScheduler : Scheduler {
        val actions = mutableListOf<() -> Unit>()
        override fun schedule(delayMillis: Long, action: () -> Unit): Any {
            actions += action
            return action
        }

        override fun cancel(token: Any) {
            actions.remove(token)
        }
    }

    @Test
    fun `discovery window holds the lock from start to timeout, exactly once`() {
        val backend = WindowBackend()
        val scheduler = ManualScheduler()
        val manager = DiscoveryManager(backend, scheduler, lock, 20_000L) {}

        assertFalse(lock.isHeld)
        manager.start()
        assertTrue(lock.isHeld)
        assertEquals(1, handle.acquires)

        scheduler.actions.toList().forEach { it() } // window elapses
        assertFalse(lock.isHeld)
        assertEquals(1, handle.releases)

        manager.stop() // extra stop after timeout
        assertEquals(1, handle.releases)
        assertEquals(listOf(LockTransition.ACQUIRED, LockTransition.RELEASED), transitions)
    }

    @Test
    fun `start failure still releases the lock`() {
        val backend = WindowBackend()
        val manager = DiscoveryManager(backend, ManualScheduler(), lock, 20_000L) {}
        manager.start()
        assertTrue(lock.isHeld)
        backend.cb.onStartFailed(0)
        assertFalse(lock.isHeld)
        assertEquals(1, handle.acquires)
        assertEquals(1, handle.releases)
    }
}
