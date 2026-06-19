package dev.hyphen.android.notifications

import android.service.notification.StatusBarNotification
import java.util.ArrayList
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
    private var privacyPolicy: NotificationPrivacyPolicy = NotificationPrivacyPolicy()
    /** Fail-closed until Mac `notification.privacy.policy` is applied (negotiated sessions only). */
    private var privacyPolicyAwaitingRemote: Boolean = false
    private var remotePrivacyPolicyApplied: Boolean = false
    private var canceller: NotificationCanceller? = null
    private var replier: NotificationReplier? = null
    private val activePayloads = linkedMapOf<String, NormalizedNotificationPayload>()
    /** Source of truth for removals not yet acked by the Mac (drained by the dispatch sweep). */
    private val pendingRemovedKeys = linkedSetOf<String>()
    private val removalCoalesced = AtomicInteger(0)
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
        requireRemotePrivacyPolicy: Boolean = false,
    ) = synchronized(lifecycle) {
        dispatcher?.shutdown()
        outboxGeneration += 1
        privacyPolicyAwaitingRemote = requireRemotePrivacyPolicy && !remotePrivacyPolicyApplied
        dispatcher = NotificationDispatchQueue().apply { setRemovalSweep(::drainPendingRemovals) }
        eventSender = NotificationMirrorEventSender(
            outbox = outbox,
            initialActiveKeys = activePayloads.keys,
            allowReplyActions = allowReplyActions,
        ).apply { setPrivacyPolicy(effectivePrivacyPolicyLocked()) }
        submitSnapshotsIfConnectedLocked()
        requestRemovalSweepIfConnectedLocked()
    }

    fun clearNotificationOutbox() = synchronized(lifecycle) {
        eventSender = null
        dispatcher?.shutdown()
        dispatcher = null
        outboxGeneration += 1
        remotePrivacyPolicyApplied = false
        privacyPolicyAwaitingRemote = false
    }

    fun notificationPrivacyMode(): NotificationPrivacyMode = synchronized(lifecycle) {
        privacyPolicy.defaultMode
    }

    fun setNotificationPrivacyMode(mode: NotificationPrivacyMode) = synchronized(lifecycle) {
        if (privacyPolicyAwaitingRemote) return@synchronized
        privacyPolicy = NotificationPrivacyPolicy(defaultMode = mode)
        eventSender?.setPrivacyPolicy(privacyPolicy)
        refreshActiveSnapshotsAfterPolicyApplyLocked()
    }

    /** Apply the full per-app policy pushed by the Mac (notification.privacy.policy). */
    fun setNotificationPrivacyPolicy(policy: NotificationPrivacyPolicy) = synchronized(lifecycle) {
        privacyPolicy = policy
        remotePrivacyPolicyApplied = true
        privacyPolicyAwaitingRemote = false
        eventSender?.setPrivacyPolicy(policy)
        refreshActiveSnapshotsAfterPolicyApplyLocked()
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

    internal fun onNotificationRemoved(sbnKey: String): Boolean = synchronized(lifecycle) {
        if (lifecycle.state != NotificationListenerConnectionState.CONNECTED) return false
        activePayloads.remove(sbnKey)
        // pendingRemovedKeys is the single source of truth; a set coalesces repeats
        // of the same key for free and bounds memory by the live notification count.
        if (!pendingRemovedKeys.add(sbnKey)) removalCoalesced.incrementAndGet()
        val queue = dispatcher ?: return false
        eventSender ?: return false
        queue.requestRemovalSweep()
        true
    }

    internal fun resetForTests() = synchronized(lifecycle) {
        activePayloads.clear()
        pendingRemovedKeys.clear()
        eventSender = null
        dispatcher?.shutdown()
        dispatcher = null
        privacyPolicy = NotificationPrivacyPolicy()
        privacyPolicyAwaitingRemote = false
        remotePrivacyPolicyApplied = false
        removalCoalesced.set(0)
        canceller = null
        replier = null
        outboxGeneration += 1
        lifecycle.onDestroyed()
    }

    internal fun isPrivacyPolicyAwaitingRemote(): Boolean = synchronized(lifecycle) {
        privacyPolicyAwaitingRemote
    }

    internal fun removalCoalescedCount(): Int = removalCoalesced.get()

    private fun replaceActivePayloads(payloads: Iterable<NormalizedNotificationPayload>) {
        activePayloads.clear()
        pendingRemovedKeys.clear()
        payloads.forEach { payload -> activePayloads[payload.sbnKey] = payload }
    }

    private fun effectivePrivacyPolicyLocked(): NotificationPrivacyPolicy =
        if (privacyPolicyAwaitingRemote) {
            NotificationPrivacyPolicy(defaultMode = NotificationPrivacyMode.EXISTS_ONLY)
        } else {
            privacyPolicy
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

    private fun refreshActiveSnapshotsAfterPolicyApplyLocked() {
        if (lifecycle.state != NotificationListenerConnectionState.CONNECTED) return
        val sender = eventSender ?: return
        val queue = dispatcher ?: return
        activePayloads.values
            .filterNot { payload -> pendingRemovedKeys.contains(payload.sbnKey) }
            .forEach { payload ->
                queue.submit { runCatching { sender.sendPostedOrUpdated(payload) } }
            }
    }

    private fun requestRemovalSweepIfConnectedLocked() {
        if (lifecycle.state != NotificationListenerConnectionState.CONNECTED) return
        if (pendingRemovedKeys.isEmpty()) return
        eventSender ?: return
        dispatcher?.requestRemovalSweep()
    }

    /**
     * Drains [pendingRemovedKeys] on the dispatch worker: one send per still-pending
     * key, dropping it from the set on success and leaving it for the next poke or
     * rebind on failure. No eviction, no re-queue, no recursion — the set bounds
     * memory by the live notification count and coalesces repeats inherently.
     */
    private fun drainPendingRemovals() {
        val (sender, generation, keys) = synchronized(lifecycle) {
            val currentSender = eventSender ?: return
            Triple(currentSender, outboxGeneration, pendingRemovedKeys.toList())
        }
        for (sbnKey in keys) {
            val stillPending = synchronized(lifecycle) {
                if (outboxGeneration != generation) return
                pendingRemovedKeys.contains(sbnKey)
            }
            if (!stillPending) continue
            val sent = runCatching { sender.sendRemoved(sbnKey) }.isSuccess
            if (sent) {
                synchronized(lifecycle) {
                    if (outboxGeneration == generation) pendingRemovedKeys.remove(sbnKey)
                }
            }
        }
    }
}

/**
 * Single-thread dispatcher for outbound notification-mirror traffic.
 *
 * Best-effort events (posted/updated/snapshot) flow through a bounded FIFO queue;
 * when it is full the OLDEST queued event is dropped, because a newer post/update
 * for the same key — or the next full snapshot — supersedes it.
 *
 * Removals are deliberately NOT queued as events. They are state owned by
 * [HyphenNotificationListenerRuntime] (its pendingRemovedKeys set), which registers
 * a drain via [setRemovalSweep] and pokes it with [requestRemovalSweep]. The worker
 * coalesces pokes and runs the drain, so removals are bounded by the live
 * notification count and are never lost, eviction-shuffled, or recursively re-queued.
 */
class NotificationDispatchQueue(
    private val capacity: Int = 128,
    private val dropped: AtomicInteger = AtomicInteger(0),
) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val stateLock = ReentrantLock()
    private val workAvailable = stateLock.newCondition()
    private val running = AtomicBoolean(true)
    private val queue = ArrayList<() -> Unit>(capacity)
    private var removalSweep: (() -> Unit)? = null
    private var sweepRequested = false
    private val worker = Thread(::runLoop, "hyphen-notification-dispatch").apply {
        isDaemon = true
        start()
    }

    /** Register the runtime's pending-removal drain. It runs on the dispatch worker. */
    fun setRemovalSweep(sweep: () -> Unit) = stateLock.withLock {
        removalSweep = sweep
    }

    /** Enqueue a best-effort event; drops the oldest queued event when at capacity. */
    fun submit(task: () -> Unit): Boolean = stateLock.withLock {
        if (!running.get()) return@withLock false
        if (queue.size >= capacity) {
            queue.removeAt(0)
            dropped.incrementAndGet()
        }
        queue.add(task)
        workAvailable.signal()
        true
    }

    /** Coalesced poke asking the worker to drain the runtime's pending removals. */
    fun requestRemovalSweep(): Boolean = stateLock.withLock {
        if (!running.get()) return@withLock false
        sweepRequested = true
        workAvailable.signal()
        true
    }

    fun droppedCount(): Int = dropped.get()

    fun shutdown() {
        stateLock.withLock {
            running.set(false)
            workAvailable.signalAll()
        }
    }

    private fun runLoop() {
        while (true) {
            val work = awaitWork() ?: return
            work.first?.let { runCatching { it() } }
            work.second?.let { runCatching { it() } }
        }
    }

    private fun awaitWork(): Pair<(() -> Unit)?, (() -> Unit)?>? = stateLock.withLock {
        while (running.get() && queue.isEmpty() && !sweepRequested) {
            try {
                workAvailable.await(50, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
            }
        }
        // After shutdown, finish draining already-accepted best-effort work but skip
        // the removal sweep — a rebind installs a fresh dispatcher that owns the set.
        if (!running.get()) {
            sweepRequested = false
            if (queue.isEmpty()) return@withLock null
        }
        val task = if (queue.isNotEmpty()) queue.removeAt(0) else null
        val sweep = if (sweepRequested) {
            sweepRequested = false
            removalSweep
        } else {
            null
        }
        task to sweep
    }
}
