package dev.hyphen.android.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

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
}
