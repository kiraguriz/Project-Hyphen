package dev.hyphen.android.notifications

import android.service.notification.StatusBarNotification
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
    private var dispatcher: NotificationDispatchQueue? = null
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
        dispatcher?.shutdown()
        dispatcher = NotificationDispatchQueue()
    }

    fun clearNotificationOutbox() = synchronized(lifecycle) {
        eventSender = null
        dispatcher?.shutdown()
        dispatcher = null
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

    fun onNotificationPosted(sbn: StatusBarNotification): Boolean {
        val (sender, queue) = synchronized(lifecycle) {
            val sender = eventSender ?: return false
            val queue = dispatcher ?: return false
            sender to queue
        }
        val payload = NormalizedNotificationPayload.fromStatusBarNotification(sbn)
        return queue.submit { runCatching { sender.sendPostedOrUpdated(payload) } }
    }

    fun onNotificationRemoved(sbn: StatusBarNotification): Boolean {
        val (sender, queue) = synchronized(lifecycle) {
            val sender = eventSender ?: return false
            val queue = dispatcher ?: return false
            sender to queue
        }
        val key = sbn.key
        return queue.submit { runCatching { sender.sendRemoved(key) } }
    }
}

class NotificationDispatchQueue(
    capacity: Int = 128,
    private val dropped: AtomicInteger = AtomicInteger(0),
) {
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(capacity),
        { runnable -> Thread(runnable, "hyphen-notification-dispatch").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    fun submit(task: () -> Unit): Boolean =
        try {
            executor.execute(task)
            true
        } catch (_: RuntimeException) {
            dropped.incrementAndGet()
            false
        }

    fun droppedCount(): Int = dropped.get()

    fun shutdown() {
        executor.shutdownNow()
    }
}
