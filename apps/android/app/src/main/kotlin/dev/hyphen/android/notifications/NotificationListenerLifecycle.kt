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
    private var privacyMode: NotificationPrivacyMode = NotificationPrivacyMode.SHOW_FULL
    private var canceller: NotificationCanceller? = null
    private var replier: NotificationReplier? = null

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
        eventSender = NotificationMirrorEventSender(outbox, privacyMode)
    }

    fun clearNotificationOutbox() = synchronized(lifecycle) {
        eventSender = null
    }

    fun notificationPrivacyMode(): NotificationPrivacyMode = synchronized(lifecycle) {
        privacyMode
    }

    fun setNotificationPrivacyMode(mode: NotificationPrivacyMode) = synchronized(lifecycle) {
        privacyMode = mode
        eventSender?.setPrivacyMode(mode)
    }

    fun setCanceller(notificationCanceller: NotificationCanceller) = synchronized(lifecycle) {
        canceller = notificationCanceller
    }

    fun clearCanceller() = synchronized(lifecycle) {
        canceller = null
    }

    fun setReplier(notificationReplier: NotificationReplier) = synchronized(lifecycle) {
        replier = notificationReplier
    }

    fun clearReplier() = synchronized(lifecycle) {
        replier = null
    }

    fun notificationCanceller(): NotificationCanceller = NotificationCanceller { sbnKey ->
        val current = synchronized(lifecycle) { canceller } ?: return@NotificationCanceller false
        current.cancel(sbnKey)
    }

    fun notificationReplier(): NotificationReplier = NotificationReplier { sbnKey, actionIndex, text ->
        val current = synchronized(lifecycle) { replier }
            ?: return@NotificationReplier NotificationReplyAttempt.Failed("permission/notifications-denied")
        current.reply(sbnKey, actionIndex, text)
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
