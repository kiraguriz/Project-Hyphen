package dev.hyphen.android.notifications

import android.service.notification.StatusBarNotification
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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
    private val activePayloads = linkedMapOf<String, NormalizedNotificationPayload>()
    private val pendingRemovedKeys = linkedSetOf<String>()
    private var outboxGeneration = 0L

    fun state(): NotificationListenerConnectionState = synchronized(lifecycle) {
        lifecycle.state
    }

    fun events(): List<NotificationListenerLifecycleEvent> = synchronized(lifecycle) {
        lifecycle.events
    }

    fun onConnected(activeNotifications: Iterable<NormalizedNotificationPayload> = emptyList()) = synchronized(lifecycle) {
        replaceActivePayloads(activeNotifications)
        lifecycle.onConnected()
        submitSnapshotsIfConnectedLocked()
    }

    fun onConnected(
        notificationCanceller: NotificationCanceller,
        notificationReplier: NotificationReplier,
        activeNotifications: Iterable<NormalizedNotificationPayload> = emptyList(),
    ) = synchronized(lifecycle) {
        canceller = notificationCanceller
        replier = notificationReplier
        replaceActivePayloads(activeNotifications)
        lifecycle.onConnected()
        submitSnapshotsIfConnectedLocked()
    }

    fun onDisconnected() = synchronized(lifecycle) {
        lifecycle.onDisconnected()
        activePayloads.clear()
        pendingRemovedKeys.clear()
        outboxGeneration += 1
        canceller = null
        replier = null
    }

    fun onDestroyed() = synchronized(lifecycle) {
        lifecycle.onDestroyed()
        activePayloads.clear()
        pendingRemovedKeys.clear()
        outboxGeneration += 1
        canceller = null
        replier = null
    }

    fun bindNotificationOutbox(
        outbox: NotificationOutbox,
        allowReplyActions: Boolean = true,
    ) = synchronized(lifecycle) {
        dispatcher?.shutdown()
        outboxGeneration += 1
        dispatcher = NotificationDispatchQueue()
        eventSender = NotificationMirrorEventSender(outbox, privacyMode, activePayloads.keys, allowReplyActions)
        submitSnapshotsIfConnectedLocked()
        submitPendingRemovalsIfConnectedLocked()
    }

    fun clearNotificationOutbox() = synchronized(lifecycle) {
        eventSender = null
        dispatcher?.shutdown()
        dispatcher = null
        outboxGeneration += 1
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

    fun isNotificationActive(sbnKey: String): Boolean = synchronized(lifecycle) {
        lifecycle.state == NotificationListenerConnectionState.CONNECTED &&
            activePayloads.containsKey(sbnKey) &&
            !pendingRemovedKeys.contains(sbnKey)
    }

    fun notificationReplier(): NotificationReplier = NotificationReplier { sbnKey, actionIndex, actionId, text ->
        val current = synchronized(lifecycle) { replier }
            ?: return@NotificationReplier NotificationReplyAttempt.Failed("permission/notifications-denied")
        current.reply(sbnKey, actionIndex, actionId, text)
    }

    fun onNotificationPosted(sbn: StatusBarNotification): Boolean {
        val payload = NormalizedNotificationPayload.fromStatusBarNotification(sbn)
        return onNotificationPosted(payload)
    }

    internal fun onNotificationPosted(payload: NormalizedNotificationPayload): Boolean {
        val (sender, queue) = synchronized(lifecycle) {
            if (lifecycle.state != NotificationListenerConnectionState.CONNECTED) return false
            pendingRemovedKeys.remove(payload.sbnKey)
            activePayloads[payload.sbnKey] = payload
            val sender = eventSender ?: return false
            val queue = dispatcher ?: return false
            sender to queue
        }
        return queue.submit { runCatching { sender.sendPostedOrUpdated(payload) } }
    }

    fun onNotificationRemoved(sbn: StatusBarNotification): Boolean {
        return onNotificationRemoved(sbn.key)
    }

    internal fun onNotificationRemoved(sbnKey: String): Boolean {
        val removal = synchronized(lifecycle) {
            if (lifecycle.state != NotificationListenerConnectionState.CONNECTED) return false
            activePayloads.remove(sbnKey)
            pendingRemovedKeys += sbnKey
            val sender = eventSender ?: return false
            val queue = dispatcher ?: return false
            RemovalSubmission(sbnKey, sender, queue, outboxGeneration)
        }
        return submitRemoval(removal)
    }

    internal fun resetForTests() = synchronized(lifecycle) {
        activePayloads.clear()
        pendingRemovedKeys.clear()
        eventSender = null
        dispatcher?.shutdown()
        dispatcher = null
        privacyMode = NotificationPrivacyMode.SHOW_FULL
        canceller = null
        replier = null
        outboxGeneration += 1
        lifecycle.onDestroyed()
    }

    private fun replaceActivePayloads(payloads: Iterable<NormalizedNotificationPayload>) {
        activePayloads.clear()
        pendingRemovedKeys.clear()
        payloads.forEach { payload -> activePayloads[payload.sbnKey] = payload }
    }

    private fun submitSnapshotsIfConnectedLocked() {
        if (lifecycle.state != NotificationListenerConnectionState.CONNECTED) return
        val sender = eventSender ?: return
        val queue = dispatcher ?: return
        activePayloads.values
            .filterNot { payload -> pendingRemovedKeys.contains(payload.sbnKey) }
            .forEach { payload ->
                queue.submit { runCatching { sender.sendSnapshot(payload) } }
            }
    }

    private fun submitPendingRemovalsIfConnectedLocked() {
        if (lifecycle.state != NotificationListenerConnectionState.CONNECTED) return
        val sender = eventSender ?: return
        val queue = dispatcher ?: return
        val generation = outboxGeneration
        pendingRemovedKeys.forEach { sbnKey ->
            submitRemoval(RemovalSubmission(sbnKey, sender, queue, generation))
        }
    }

    private fun submitRemoval(removal: RemovalSubmission): Boolean =
        removal.queue.submitCritical {
            val sent = runCatching { removal.sender.sendRemoved(removal.sbnKey) }.isSuccess
            if (sent) {
                synchronized(lifecycle) {
                    if (outboxGeneration == removal.generation) {
                        pendingRemovedKeys.remove(removal.sbnKey)
                    }
                }
            }
        }

    private data class RemovalSubmission(
        val sbnKey: String,
        val sender: NotificationMirrorEventSender,
        val queue: NotificationDispatchQueue,
        val generation: Long,
    )
}

class NotificationDispatchQueue(
    private val capacity: Int = 128,
    private val dropped: AtomicInteger = AtomicInteger(0),
) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val stateLock = ReentrantLock()
    private val notEmpty = stateLock.newCondition()
    private val running = AtomicBoolean(true)
    private val queue = ArrayDeque<QueuedTask>(capacity)
    private val worker = Thread(::runLoop, "hyphen-notification-dispatch").apply {
        isDaemon = true
        start()
    }

    fun submit(task: () -> Unit): Boolean = submit(task, critical = false)

    fun submitCritical(task: () -> Unit): Boolean = submit(task, critical = true)

    private fun submit(task: () -> Unit, critical: Boolean): Boolean {
        stateLock.withLock {
            if (!running.get()) return false
            if (!critical && bestEffortCountLocked() >= capacity) {
                dropOldestBestEffortLocked()
            } else if (critical && queue.size >= capacity) {
                dropOldestBestEffortLocked()
            }
            queue.addLast(QueuedTask(task, critical))
            notEmpty.signal()
            return true
        }
    }

    fun droppedCount(): Int = dropped.get()

    fun shutdown() {
        stateLock.withLock {
            running.set(false)
            notEmpty.signalAll()
        }
    }

    private fun runLoop() {
        while (true) {
            val task = takeTask() ?: return
            runCatching { task.block() }
        }
    }

    private fun takeTask(): QueuedTask? = stateLock.withLock {
        while (running.get() && queue.isEmpty()) {
            try {
                notEmpty.await(50, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                if (!running.get() && queue.isEmpty()) return null
            }
        }
        if (!running.get() && queue.isEmpty()) return null
        queue.removeFirst()
    }

    private fun bestEffortCountLocked(): Int = queue.count { !it.critical }

    private fun dropOldestBestEffortLocked() {
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            if (!iterator.next().critical) {
                iterator.remove()
                dropped.incrementAndGet()
                return
            }
        }
    }

    private data class QueuedTask(
        val block: () -> Unit,
        val critical: Boolean,
    )
}
