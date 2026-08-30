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
    private const val MAX_TRACKED_REMOTE_KEYS = 128
    private const val MAX_EARLY_TRANSPORT_SIGNALS = 128
    private const val MAX_REMOVAL_WIRE_ATTEMPTS_PER_SESSION = 3

    private val lifecycle = NotificationListenerLifecycle()
    private var eventSender: NotificationMirrorEventSender? = null
    private var dispatcher: NotificationDispatchQueue? = null
    private var remotePrivacyPolicy: NotificationPrivacyPolicy = NotificationPrivacyPolicy()
    /** Phone-owner setting. A remote Mac can never make disclosure less restrictive. */
    private var localPrivacyFloor: NotificationPrivacyMode = NotificationPrivacyMode.SHOW_FULL
    /** Fail-closed until Mac `notification.privacy.policy` is applied (negotiated sessions only). */
    private var privacyPolicyAwaitingRemote: Boolean = false
    private var remotePrivacyPolicyApplied: Boolean = false
    private var canceller: NotificationCanceller? = null
    private var replier: NotificationReplier? = null
    private val activePayloads = linkedMapOf<String, NormalizedNotificationPayload>()
    /** Keys conservatively known to have been attempted for the current trusted peer. */
    private val peerVisibleKeys = linkedSetOf<String>()
    /** Source of truth for removals not yet ACKed by that peer. */
    private val pendingRemovedKeys = linkedSetOf<String>()
    private val inFlightRemovalByMessageId = linkedMapOf<String, String>()
    private val inFlightRemovalKeys = linkedSetOf<String>()
    private val removalWireAttempts = mutableMapOf<String, Int>()
    private val removalRetryBlockedForSession = linkedSetOf<String>()
    private val earlyRemovalAcks = linkedSetOf<String>()
    private val earlyRemovalTimeouts = linkedSetOf<String>()
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
        applyEffectivePrivacyPolicyLocked()
        submitSnapshotsIfConnectedLocked()
        requestRemovalSweepIfOutboxBoundLocked()
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
        applyEffectivePrivacyPolicyLocked()
        submitSnapshotsIfConnectedLocked()
        requestRemovalSweepIfOutboxBoundLocked()
    }

    fun onDisconnected() = synchronized(lifecycle) {
        retractMirroredRowsLocked()
        lifecycle.onDisconnected()
        activePayloads.clear()
        canceller = null
        replier = null
        requestRemovalSweepIfOutboxBoundLocked()
    }

    fun onDestroyed() = synchronized(lifecycle) {
        retractMirroredRowsLocked()
        lifecycle.onDestroyed()
        activePayloads.clear()
        canceller = null
        replier = null
        requestRemovalSweepIfOutboxBoundLocked()
    }

    fun bindNotificationOutbox(
        outbox: NotificationOutbox,
        allowReplyActions: Boolean = true,
        requireRemotePrivacyPolicy: Boolean = false,
    ) = synchronized(lifecycle) {
        outboxGeneration += 1
        retireRemovalSessionLocked()
        dispatcher?.shutdown()
        privacyPolicyAwaitingRemote = requireRemotePrivacyPolicy && !remotePrivacyPolicyApplied
        dispatcher = NotificationDispatchQueue().apply { setRemovalSweep(::drainPendingRemovals) }
        eventSender = NotificationMirrorEventSender(
            outbox = outbox,
            initialActiveKeys = peerVisibleKeys,
            allowReplyActions = allowReplyActions,
        )
        applyEffectivePrivacyPolicyLocked()
        submitSnapshotsIfConnectedLocked()
        requestRemovalSweepIfOutboxBoundLocked()
    }

    fun clearNotificationOutbox() = synchronized(lifecycle) {
        clearNotificationOutboxLocked(clearPeerState = false)
    }

    /** Trust replacement/revocation must never replay peer A identifiers to peer B. */
    fun clearNotificationStateAfterTrustChange() = synchronized(lifecycle) {
        clearNotificationOutboxLocked(clearPeerState = true)
    }

    fun notificationPrivacyMode(): NotificationPrivacyMode = synchronized(lifecycle) {
        localPrivacyFloor
    }

    fun setNotificationPrivacyMode(mode: NotificationPrivacyMode) = synchronized(lifecycle) {
        localPrivacyFloor = mode
        // Negotiated sessions remain at the stricter fail-closed policy until
        // the Mac policy arrives; changing the local floor need not emit an
        // identical existsOnly snapshot refresh in that window.
        if (privacyPolicyAwaitingRemote) return@synchronized
        applyEffectivePrivacyPolicyLocked()
        refreshActiveSnapshotsAfterPolicyApplyLocked()
    }

    /** Apply the full per-app policy pushed by the Mac (notification.privacy.policy). */
    fun setNotificationPrivacyPolicy(policy: NotificationPrivacyPolicy) = synchronized(lifecycle) {
        remotePrivacyPolicy = policy
        remotePrivacyPolicyApplied = true
        privacyPolicyAwaitingRemote = false
        applyEffectivePrivacyPolicyLocked()
        refreshActiveSnapshotsAfterPolicyApplyLocked()
    }

    data class PrivacyState(
        val localFloor: NotificationPrivacyMode,
        val awaitingRemotePolicy: Boolean,
        val remotePolicyApplied: Boolean,
    )

    fun privacyState(): PrivacyState = synchronized(lifecycle) {
        PrivacyState(localPrivacyFloor, privacyPolicyAwaitingRemote, remotePrivacyPolicyApplied)
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
        data class PostWork(
            val sender: NotificationMirrorEventSender,
            val queue: NotificationDispatchQueue,
            val generation: Long,
        )
        val work = synchronized(lifecycle) {
            if (lifecycle.state != NotificationListenerConnectionState.CONNECTED) return false
            pendingRemovedKeys.remove(payload.sbnKey)
            removalRetryBlockedForSession.remove(payload.sbnKey)
            activePayloads[payload.sbnKey] = payload
            val sender = eventSender ?: return false
            val queue = dispatcher ?: return false
            if (!effectivePrivacyPolicyLocked().isMirroringEnabled) return false
            if (!reservePeerVisibleKeyLocked(payload.sbnKey)) return false
            PostWork(sender, queue, outboxGeneration)
        }
        return work.queue.submit {
            val owns = synchronized(lifecycle) {
                outboxGeneration == work.generation &&
                    peerVisibleKeys.contains(payload.sbnKey) &&
                    !pendingRemovedKeys.contains(payload.sbnKey)
            }
            if (owns) runCatching { work.sender.sendPostedOrUpdated(payload) }
        }
    }

    fun onNotificationRemoved(sbn: StatusBarNotification): Boolean {
        return onNotificationRemoved(sbn.key)
    }

    internal fun onNotificationRemoved(sbnKey: String): Boolean = synchronized(lifecycle) {
        if (lifecycle.state != NotificationListenerConnectionState.CONNECTED) return false
        activePayloads.remove(sbnKey)
        // Unknown/non-mirrored Android callbacks never allocate tombstone state.
        if (
            !peerVisibleKeys.contains(sbnKey) &&
            !pendingRemovedKeys.contains(sbnKey) &&
            !inFlightRemovalKeys.contains(sbnKey)
        ) return false
        if (!pendingRemovedKeys.add(sbnKey)) removalCoalesced.incrementAndGet()
        val queue = dispatcher ?: return false
        eventSender ?: return false
        queue.requestRemovalSweep()
        true
    }

    internal fun resetForTests() = synchronized(lifecycle) {
        activePayloads.clear()
        peerVisibleKeys.clear()
        pendingRemovedKeys.clear()
        inFlightRemovalByMessageId.clear()
        inFlightRemovalKeys.clear()
        removalWireAttempts.clear()
        removalRetryBlockedForSession.clear()
        earlyRemovalAcks.clear()
        earlyRemovalTimeouts.clear()
        eventSender = null
        dispatcher?.shutdown()
        dispatcher = null
        remotePrivacyPolicy = NotificationPrivacyPolicy()
        localPrivacyFloor = NotificationPrivacyMode.SHOW_FULL
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

    internal fun pendingRemovedCount(): Int = synchronized(lifecycle) {
        pendingRemovedKeys.size
    }

    internal fun trackedPeerKeyCount(): Int = synchronized(lifecycle) {
        peerVisibleKeys.size
    }

    /** A peer ACK is the commit point for a notification tombstone. */
    fun onTransportAcknowledged(messageId: String) = synchronized(lifecycle) {
        val sbnKey = inFlightRemovalByMessageId.remove(messageId)
        if (sbnKey == null) {
            rememberEarlySignalLocked(earlyRemovalAcks, messageId)
            return@synchronized
        }
        inFlightRemovalKeys.remove(sbnKey)
        removalWireAttempts.remove(sbnKey)
        removalRetryBlockedForSession.remove(sbnKey)
        if (pendingRemovedKeys.remove(sbnKey)) {
            peerVisibleKeys.remove(sbnKey)
        }
        requestRemovalSweepIfOutboxBoundLocked()
    }

    /** Timed-out tombstones remain durable and retry at most three times per session. */
    fun onTransportAckTimeout(messageId: String) = synchronized(lifecycle) {
        val sbnKey = inFlightRemovalByMessageId.remove(messageId)
        if (sbnKey == null) {
            rememberEarlySignalLocked(earlyRemovalTimeouts, messageId)
            return@synchronized
        }
        inFlightRemovalKeys.remove(sbnKey)
        if (pendingRemovedKeys.contains(sbnKey)) {
            val attempts = removalWireAttempts[sbnKey] ?: 0
            if (attempts >= MAX_REMOVAL_WIRE_ATTEMPTS_PER_SESSION) {
                removalRetryBlockedForSession += sbnKey
            }
        } else {
            removalWireAttempts.remove(sbnKey)
        }
        requestRemovalSweepIfOutboxBoundLocked()
    }

    /** A transport ACK/timeout freed capacity; retry correctness-sensitive removals in-session. */
    fun onTransportCapacityAvailable() = synchronized(lifecycle) {
        requestRemovalSweepIfOutboxBoundLocked()
    }

    private fun replaceActivePayloads(payloads: Iterable<NormalizedNotificationPayload>) {
        activePayloads.clear()
        payloads.forEach { payload -> activePayloads[payload.sbnKey] = payload }
        // A notification that is active again supersedes an unsent retraction from
        // an earlier listener disconnect. A removal already accepted by the outbox
        // is followed by the fresh snapshot on the same serial dispatcher.
        pendingRemovedKeys.removeAll(activePayloads.keys)
        removalRetryBlockedForSession.removeAll(activePayloads.keys)
    }

    private fun retractMirroredRowsLocked() {
        eventSender
            ?.setPrivacyPolicy(NotificationPrivacyPolicy(isMirroringEnabled = false))
            ?.forEach(::reservePeerVisibleKeyLocked)
        peerVisibleKeys.forEach { sbnKey ->
            if (!pendingRemovedKeys.add(sbnKey)) removalCoalesced.incrementAndGet()
        }
    }

    private fun reservePeerVisibleKeyLocked(sbnKey: String): Boolean {
        if (peerVisibleKeys.contains(sbnKey)) return true
        if (peerVisibleKeys.size >= MAX_TRACKED_REMOTE_KEYS) return false
        peerVisibleKeys += sbnKey
        return true
    }

    private fun clearNotificationOutboxLocked(clearPeerState: Boolean) {
        outboxGeneration += 1
        retireRemovalSessionLocked()
        eventSender = null
        dispatcher?.shutdown()
        dispatcher = null
        remotePrivacyPolicyApplied = false
        privacyPolicyAwaitingRemote = false
        remotePrivacyPolicy = NotificationPrivacyPolicy()
        if (clearPeerState) {
            peerVisibleKeys.clear()
            pendingRemovedKeys.clear()
        }
    }

    private fun retireRemovalSessionLocked() {
        inFlightRemovalByMessageId.clear()
        inFlightRemovalKeys.clear()
        removalWireAttempts.clear()
        removalRetryBlockedForSession.clear()
        earlyRemovalAcks.clear()
        earlyRemovalTimeouts.clear()
    }

    private fun rememberEarlySignalLocked(target: LinkedHashSet<String>, messageId: String) {
        if (target.size >= MAX_EARLY_TRANSPORT_SIGNALS) {
            target.remove(target.first())
        }
        target += messageId
    }

    private fun effectivePrivacyPolicyLocked(): NotificationPrivacyPolicy =
        (if (privacyPolicyAwaitingRemote) {
            NotificationPrivacyPolicy(defaultMode = NotificationPrivacyMode.EXISTS_ONLY)
        } else {
            remotePrivacyPolicy
        }).restrictedBy(localPrivacyFloor)

    private fun applyEffectivePrivacyPolicyLocked() {
        val sender = eventSender ?: return
        val queue = dispatcher ?: return
        val effectivePolicy = effectivePrivacyPolicyLocked()
        val mirroredKeysToRemove = sender.setPrivacyPolicy(effectivePolicy)
        mirroredKeysToRemove.forEach(::reservePeerVisibleKeyLocked)
        if (effectivePolicy.isMirroringEnabled) {
            // A still-active Android row must be published again. Removing it from
            // this set cancels only a privacy-suppression removal; genuine Android
            // removals have already been deleted from activePayloads.
            pendingRemovedKeys.removeAll(activePayloads.keys)
        } else {
            peerVisibleKeys.forEach { sbnKey ->
                if (!pendingRemovedKeys.add(sbnKey)) removalCoalesced.incrementAndGet()
            }
        }
        if (pendingRemovedKeys.isNotEmpty()) {
            // Privacy cleanup is correctness-sensitive and therefore uses the
            // non-evicting removal sweep, not the best-effort snapshot queue.
            queue.requestRemovalSweep()
        }
    }

    private fun submitSnapshotsIfConnectedLocked() {
        if (lifecycle.state != NotificationListenerConnectionState.CONNECTED) return
        val sender = eventSender ?: return
        val queue = dispatcher ?: return
        val generation = outboxGeneration
        activePayloads.values
            .filterNot { payload -> pendingRemovedKeys.contains(payload.sbnKey) }
            .filter { payload -> reservePeerVisibleKeyLocked(payload.sbnKey) }
            .forEach { payload ->
                queue.submit {
                    val owns = synchronized(lifecycle) {
                        outboxGeneration == generation &&
                            peerVisibleKeys.contains(payload.sbnKey) &&
                            !pendingRemovedKeys.contains(payload.sbnKey)
                    }
                    if (owns) runCatching { sender.sendSnapshot(payload) }
                }
            }
    }

    private fun refreshActiveSnapshotsAfterPolicyApplyLocked() {
        if (lifecycle.state != NotificationListenerConnectionState.CONNECTED) return
        val sender = eventSender ?: return
        val queue = dispatcher ?: return
        val generation = outboxGeneration
        activePayloads.values
            .filterNot { payload -> pendingRemovedKeys.contains(payload.sbnKey) }
            .filter { payload -> reservePeerVisibleKeyLocked(payload.sbnKey) }
            .forEach { payload ->
                queue.submit {
                    val owns = synchronized(lifecycle) {
                        outboxGeneration == generation &&
                            peerVisibleKeys.contains(payload.sbnKey) &&
                            !pendingRemovedKeys.contains(payload.sbnKey)
                    }
                    if (owns) runCatching { sender.sendPostedOrUpdated(payload) }
                }
            }
    }

    private fun requestRemovalSweepIfOutboxBoundLocked() {
        if (pendingRemovedKeys.none { sbnKey ->
                !inFlightRemovalKeys.contains(sbnKey) &&
                    !removalRetryBlockedForSession.contains(sbnKey)
            }
        ) return
        eventSender ?: return
        dispatcher?.requestRemovalSweep()
    }

    /**
     * Drains [pendingRemovedKeys] on the dispatch worker. Local send acceptance
     * moves a tombstone to in-flight; only a peer ACK clears it. Flow-control
     * refusal leaves it pending, timeout retries are capped per session, and a
     * fresh same-peer session resets the cap without losing the tombstone.
     */
    private fun drainPendingRemovals() {
        val (sender, generation, keys) = synchronized(lifecycle) {
            val currentSender = eventSender ?: return
            Triple(
                currentSender,
                outboxGeneration,
                pendingRemovedKeys.filterNot { sbnKey ->
                    inFlightRemovalKeys.contains(sbnKey) ||
                        removalRetryBlockedForSession.contains(sbnKey)
                },
            )
        }
        for (sbnKey in keys) {
            val stillPending = synchronized(lifecycle) {
                if (outboxGeneration != generation) return
                pendingRemovedKeys.contains(sbnKey) &&
                    !inFlightRemovalKeys.contains(sbnKey) &&
                    !removalRetryBlockedForSession.contains(sbnKey)
            }
            if (!stillPending) continue
            val messageId = runCatching { sender.sendRemoved(sbnKey) }.getOrNull() ?: continue
            synchronized(lifecycle) {
                if (outboxGeneration != generation) return@synchronized
                val attempts = (removalWireAttempts[sbnKey] ?: 0) + 1
                removalWireAttempts[sbnKey] = attempts
                when {
                    earlyRemovalAcks.remove(messageId) -> {
                        removalWireAttempts.remove(sbnKey)
                        removalRetryBlockedForSession.remove(sbnKey)
                        if (pendingRemovedKeys.remove(sbnKey)) peerVisibleKeys.remove(sbnKey)
                    }
                    earlyRemovalTimeouts.remove(messageId) -> {
                        if (
                            pendingRemovedKeys.contains(sbnKey) &&
                            attempts >= MAX_REMOVAL_WIRE_ATTEMPTS_PER_SESSION
                        ) {
                            removalRetryBlockedForSession += sbnKey
                        }
                    }
                    else -> {
                        inFlightRemovalByMessageId[messageId] = sbnKey
                        inFlightRemovalKeys += sbnKey
                    }
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
 * coalesces pokes and runs the drain. Peer-visible ownership is capped to the same
 * 128-key budget as this queue, and ACK-owned tombstones are never eviction-shuffled.
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
