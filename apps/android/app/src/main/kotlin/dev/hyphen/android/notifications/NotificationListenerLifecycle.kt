package dev.hyphen.android.notifications

import android.service.notification.StatusBarNotification

enum class NotificationListenerConnectionState {
    DISCONNECTED,
    CONNECTED,
}

enum class NotificationListenerLifecycleEvent {
    CONNECTED,
    DISCONNECTED,
    DESTROYED,
}

class NotificationListenerLifecycle {
    private val mutableEvents = mutableListOf<NotificationListenerLifecycleEvent>()

    var state: NotificationListenerConnectionState = NotificationListenerConnectionState.DISCONNECTED
        private set

    val events: List<NotificationListenerLifecycleEvent>
        get() = mutableEvents.toList()

    fun onConnected() {
        state = NotificationListenerConnectionState.CONNECTED
        mutableEvents += NotificationListenerLifecycleEvent.CONNECTED
    }

    fun onDisconnected() {
        state = NotificationListenerConnectionState.DISCONNECTED
        mutableEvents += NotificationListenerLifecycleEvent.DISCONNECTED
    }

    fun onDestroyed() {
        state = NotificationListenerConnectionState.DISCONNECTED
        mutableEvents += NotificationListenerLifecycleEvent.DESTROYED
    }
}

object HyphenNotificationListenerRuntime {
    private val lifecycle = NotificationListenerLifecycle()
    private var eventSender: NotificationMirrorEventSender? = null

    fun state(): NotificationListenerConnectionState = synchronized(lifecycle) {
        lifecycle.state
    }

    fun events(): List<NotificationListenerLifecycleEvent> = synchronized(lifecycle) {
        lifecycle.events
    }

    fun onConnected() = synchronized(lifecycle) {
        lifecycle.onConnected()
    }

    fun onDisconnected() = synchronized(lifecycle) {
        lifecycle.onDisconnected()
    }

    fun onDestroyed() = synchronized(lifecycle) {
        lifecycle.onDestroyed()
    }

    fun bindNotificationOutbox(outbox: NotificationOutbox) = synchronized(lifecycle) {
        eventSender = NotificationMirrorEventSender(outbox)
    }

    fun clearNotificationOutbox() = synchronized(lifecycle) {
        eventSender = null
    }

    fun onNotificationPosted(sbn: StatusBarNotification): String? {
        val sender = synchronized(lifecycle) { eventSender } ?: return null
        val payload = NormalizedNotificationPayload.fromStatusBarNotification(sbn)
        return runCatching { sender.sendPostedOrUpdated(payload) }.getOrNull()
    }

    fun onNotificationRemoved(sbn: StatusBarNotification): String? {
        val sender = synchronized(lifecycle) { eventSender } ?: return null
        return runCatching { sender.sendRemoved(sbn.key) }.getOrNull()
    }
}
