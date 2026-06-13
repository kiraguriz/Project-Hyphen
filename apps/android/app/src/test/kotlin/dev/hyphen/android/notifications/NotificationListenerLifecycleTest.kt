package dev.hyphen.android.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class NotificationListenerLifecycleTest {

    @Test
    fun `listener starts disconnected`() {
        val lifecycle = NotificationListenerLifecycle()

        assertEquals(NotificationListenerConnectionState.DISCONNECTED, lifecycle.state)
        assertEquals(emptyList<NotificationListenerLifecycleEvent>(), lifecycle.events)
    }

    @Test
    fun `connected and disconnected callbacks update state and event log`() {
        val lifecycle = NotificationListenerLifecycle()

        lifecycle.onConnected()
        lifecycle.onDisconnected()

        assertEquals(NotificationListenerConnectionState.DISCONNECTED, lifecycle.state)
        assertEquals(
            listOf(
                NotificationListenerLifecycleEvent.CONNECTED,
                NotificationListenerLifecycleEvent.DISCONNECTED,
            ),
            lifecycle.events,
        )
    }

    @Test
    fun `destroy always leaves listener disconnected`() {
        val lifecycle = NotificationListenerLifecycle()

        lifecycle.onConnected()
        lifecycle.onDestroyed()

        assertEquals(NotificationListenerConnectionState.DISCONNECTED, lifecycle.state)
        assertEquals(
            listOf(
                NotificationListenerLifecycleEvent.CONNECTED,
                NotificationListenerLifecycleEvent.DESTROYED,
            ),
            lifecycle.events,
        )
    }

    @Test
    fun `dispatch queue does not block caller behind a slow send`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondRan = AtomicBoolean(false)
        val queue = NotificationDispatchQueue(capacity = 1)

        assertTrue(queue.submit {
            started.countDown()
            release.await(3, TimeUnit.SECONDS)
        })
        assertTrue(started.await(1, TimeUnit.SECONDS))
        assertTrue(queue.submit { secondRan.set(true) })

        release.countDown()
        Thread.sleep(50)
        assertTrue(secondRan.get())
        queue.shutdown()
    }

    @Test
    fun `dispatch queue drops when bounded backlog is full`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val queue = NotificationDispatchQueue(capacity = 1)

        assertTrue(queue.submit {
            started.countDown()
            release.await(3, TimeUnit.SECONDS)
        })
        assertTrue(started.await(1, TimeUnit.SECONDS))
        assertTrue(queue.submit { })
        assertFalse(queue.submit { })

        assertEquals(1, queue.droppedCount())
        release.countDown()
        queue.shutdown()
    }
}
