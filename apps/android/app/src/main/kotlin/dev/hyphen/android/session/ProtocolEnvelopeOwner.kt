package dev.hyphen.android.session

import android.content.Context
import dev.hyphen.android.R
import dev.hyphen.android.notifications.HyphenNotificationListenerRuntime
import dev.hyphen.android.notifications.NotificationCapabilityGate
import dev.hyphen.android.notifications.NotificationDismissRequestHandler
import dev.hyphen.android.notifications.NotificationOutbox
import dev.hyphen.android.notifications.NotificationPrivacyPolicyHandler
import dev.hyphen.android.notifications.NotificationPrivacyPolicyResult
import dev.hyphen.android.notifications.NotificationProtocol
import dev.hyphen.android.notifications.NotificationReplyRequestHandler
import dev.hyphen.android.notifications.NotificationPrivacyMode
import dev.hyphen.android.text.TextLinkConfirmationRequest
import dev.hyphen.android.text.TextLinkMessage
import dev.hyphen.android.text.TextLinkReceiver
import dev.hyphen.android.text.TextConfirmationQueueFullException
import dev.hyphen.android.transfer.FileTransferStorage
import dev.hyphen.android.transfer.ReceivedFileDelivery
import dev.hyphen.android.transfer.TransferCancel
import dev.hyphen.android.transfer.TransferCheckpointStore
import dev.hyphen.android.transfer.TransferCompleted
import dev.hyphen.android.transfer.TransferEvent
import dev.hyphen.android.transfer.TransferProgress
import dev.hyphen.android.transfer.TransferProtocol
import dev.hyphen.android.transfer.TransferReceiver
import dev.hyphen.android.transfer.TransferResumeInfo
import dev.hyphen.android.transfer.TransferSender
import dev.hyphen.android.transport.Envelope
import dev.hyphen.android.transport.Json
import dev.hyphen.android.transport.ProtocolSession
import dev.hyphen.android.transport.SessionHandshake
import dev.hyphen.android.ui.ActivityEvent
import dev.hyphen.android.ui.TransferDirection
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Opaque identity for one installed transport generation. */
@JvmInline
internal value class ProtocolSessionEpoch private constructor(private val value: Long) {
    companion object {
        fun of(generation: Long): ProtocolSessionEpoch = ProtocolSessionEpoch(generation)
    }
}

internal data class ProtocolOwnerSession(
    val epoch: ProtocolSessionEpoch,
    val capabilities: SessionHandshake.NegotiatedCapabilities?,
    val transferSender: TransferSender?,
    val peerFingerprint: ByteArray,
    val sessionId: String,
    val send: (
        type: String,
        capability: String?,
        requiresAck: Boolean,
        payload: Json.Obj,
    ) -> String,
)

internal interface ProtocolOwnerEvents {
    fun log(line: String) {}
    fun activity(event: ActivityEvent) {}
    fun textConfirmation(request: TextLinkConfirmationRequest) {}
    fun privacyPolicyApplied() {}
}

/**
 * The sole consumer of raw plugin envelopes. UI/services observe structured
 * outputs only and can therefore disappear without changing protocol behavior.
 */
internal interface ProtocolEnvelopeOwner {
    fun activate(session: ProtocolOwnerSession) {}
    fun retire(epoch: ProtocolSessionEpoch) {}
    fun handleEnvelope(epoch: ProtocolSessionEpoch, envelope: Envelope) {}
    fun handleAck(epoch: ProtocolSessionEpoch, messageId: String) {}
    fun handleAckTimeout(epoch: ProtocolSessionEpoch, messageId: String) {}
    fun pendingTextConfirmations(): List<TextLinkConfirmationRequest> = emptyList()
    fun resolveTextConfirmation(messageId: String): Boolean = false
    fun retryIncomingDelivery(fileId: String): Boolean = false
    fun cancelTransfer(fileId: String): Boolean = false
    fun invalidatePeerCheckpoints(peerFingerprint: ByteArray) {}
    fun invalidateAllCheckpoints() {}
}

internal object NoOpProtocolEnvelopeOwner : ProtocolEnvelopeOwner

internal interface NotificationRuntimeFacade {
    fun setPrivacyPolicy(policy: dev.hyphen.android.notifications.NotificationPrivacyPolicy)
    fun localPrivacyFloor(): NotificationPrivacyMode
    fun canceller(): dev.hyphen.android.notifications.NotificationCanceller
    fun isActive(sbnKey: String): Boolean
    fun replier(): dev.hyphen.android.notifications.NotificationReplier
}

private object AndroidNotificationRuntimeFacade : NotificationRuntimeFacade {
    override fun setPrivacyPolicy(policy: dev.hyphen.android.notifications.NotificationPrivacyPolicy) =
        HyphenNotificationListenerRuntime.setNotificationPrivacyPolicy(policy)

    override fun localPrivacyFloor(): NotificationPrivacyMode =
        HyphenNotificationListenerRuntime.notificationPrivacyMode()

    override fun canceller() = HyphenNotificationListenerRuntime.notificationCanceller()

    override fun isActive(sbnKey: String): Boolean =
        HyphenNotificationListenerRuntime.isNotificationActive(sbnKey)

    override fun replier() = HyphenNotificationListenerRuntime.notificationReplier()
}

internal data class PendingProtocolResult(
    val type: String,
    val capability: String,
    val payload: Json.Obj,
    val token: Long = RESULT_TOKENS.incrementAndGet(),
    val attempts: Int = 0,
) {
    private companion object {
        val RESULT_TOKENS = AtomicLong()
    }
}

/** Latest-wins bounded result queue: one pending and one in flight per epoch. */
internal class BoundedProtocolResultRetry(
    private val latestWins: Boolean = true,
    private val maxTotal: Int = 2,
) {
    private data class InFlight(val messageId: String, val result: PendingProtocolResult)

    private val lock = Any()
    private var session: ProtocolOwnerSession? = null
    private val pending = ArrayDeque<PendingProtocolResult>()
    private var inFlight: InFlight? = null
    private var sendingToken: Long? = null
    private val earlyAcks = mutableSetOf<String>()
    private var reservations = 0
    private var pumping = false
    private var pumpRequested = false

    fun activate(newSession: ProtocolOwnerSession) = synchronized(lock) {
        session = newSession
        pending.clear()
        inFlight = null
        sendingToken = null
        earlyAcks.clear()
        reservations = 0
        pumping = false
        pumpRequested = false
    }

    fun retire(epoch: ProtocolSessionEpoch) = synchronized(lock) {
        if (session?.epoch == epoch) {
            session = null
            pending.clear()
            inFlight = null
            sendingToken = null
            earlyAcks.clear()
            reservations = 0
            pumping = false
            pumpRequested = false
        }
    }

    fun offer(epoch: ProtocolSessionEpoch, result: PendingProtocolResult): Boolean {
        val accepted = synchronized(lock) {
            if (session?.epoch != epoch) return false
            if (latestWins) {
                pending.clear()
            } else if (pending.size + (if (inFlight != null || sendingToken != null) 1 else 0) + reservations >= maxTotal) {
                return false
            }
            pending.addLast(result)
            true
        }
        pump(epoch)
        return accepted
    }

    fun tryReserve(epoch: ProtocolSessionEpoch): Boolean = synchronized(lock) {
        if (latestWins || session?.epoch != epoch) return false
        val used = pending.size + (if (inFlight != null || sendingToken != null) 1 else 0) + reservations
        if (used >= maxTotal) return false
        reservations += 1
        true
    }

    fun offerReserved(epoch: ProtocolSessionEpoch, result: PendingProtocolResult): Boolean {
        val accepted = synchronized(lock) {
            if (latestWins || session?.epoch != epoch || reservations <= 0) return false
            reservations -= 1
            pending.addLast(result)
            true
        }
        pump(epoch)
        return accepted
    }

    fun releaseReservation(epoch: ProtocolSessionEpoch) = synchronized(lock) {
        if (session?.epoch == epoch && reservations > 0) reservations -= 1
    }

    fun onAck(epoch: ProtocolSessionEpoch, messageId: String) {
        synchronized(lock) {
            if (session?.epoch != epoch) return
            if (inFlight?.messageId == messageId) {
                inFlight = null
            } else if (sendingToken != null) {
                earlyAcks += messageId
            }
        }
        // Any ACK may have opened the ProtocolSession flow-control window.
        pump(epoch)
    }

    fun onAckTimeout(epoch: ProtocolSessionEpoch, messageId: String) {
        synchronized(lock) {
            if (session?.epoch != epoch) return
            val timedOut = inFlight?.takeIf { it.messageId == messageId } ?: return
            inFlight = null
            if (timedOut.result.attempts < MAX_ATTEMPTS) {
                if (latestWins && (pending.lastOrNull()?.token ?: Long.MIN_VALUE) < timedOut.result.token) {
                    pending.clear()
                    pending.addLast(timedOut.result)
                } else if (!latestWins) {
                    pending.addFirst(timedOut.result)
                }
            }
        }
        pump(epoch)
    }

    internal fun hasPendingOrInFlight(): Boolean = synchronized(lock) {
        pending.isNotEmpty() || inFlight != null || sendingToken != null || reservations > 0
    }

    private fun pump(epoch: ProtocolSessionEpoch) {
        val target: Pair<ProtocolOwnerSession, PendingProtocolResult> = synchronized(lock) {
            if (pumping) {
                pumpRequested = true
                return
            }
            val current = session
            val queued = pending.firstOrNull()
            if (current?.epoch != epoch || queued == null || inFlight != null || sendingToken != null) return
            pumping = true
            pumpRequested = false
            sendingToken = queued.token
            current to queued
        }
        val sentId = try {
            target.first.send(
                target.second.type,
                target.second.capability,
                true,
                target.second.payload,
            )
        } catch (rejected: ProtocolSession.SendRejected) {
            // Flow control is ordinary backpressure. Keep the latest result and
            // retry after a later ACK; never let it tear down the read loop.
            synchronized(lock) {
                if (sendingToken == target.second.token) sendingToken = null
                pumping = false
            }
            if (rejected.code != "protocol/flow-control") return
            val retryNow = synchronized(lock) {
                pumpRequested.also { pumpRequested = false }
            }
            if (retryNow) pump(epoch)
            return
        } catch (_: Exception) {
            // The transport owner will retire a failed session. A result write
            // must never escape the plugin handler after its side effect ran.
            synchronized(lock) {
                if (sendingToken == target.second.token) sendingToken = null
                pumping = false
                pumpRequested = false
            }
            return
        }
        var shouldPumpAgain = false
        synchronized(lock) {
            if (sendingToken == target.second.token) sendingToken = null
            pumping = false
            if (session?.epoch != epoch || inFlight != null) return@synchronized
            if (pending.firstOrNull()?.token == target.second.token) pending.removeFirst()
            if (earlyAcks.remove(sentId)) {
                shouldPumpAgain = pending.isNotEmpty()
            } else {
                inFlight = InFlight(sentId, target.second.copy(attempts = target.second.attempts + 1))
            }
            earlyAcks.clear()
            pumpRequested = false
        }
        if (shouldPumpAgain) pump(epoch)
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}

internal class DefaultProtocolEnvelopeOwner(
    private val receiver: TransferReceiver,
    private val deliverIncoming: (TransferCompleted) -> File,
    private val notificationRuntime: NotificationRuntimeFacade,
    private val events: ProtocolOwnerEvents,
    private val textReceiver: TextLinkReceiver = TextLinkReceiver(),
    private val privacyResultRetry: BoundedProtocolResultRetry = BoundedProtocolResultRetry(),
    private val actionResultRetry: BoundedProtocolResultRetry =
        BoundedProtocolResultRetry(latestWins = false, maxTotal = 32),
    private val notificationLabel: String = "Notification",
    private val notificationClearedAction: String = "Cleared",
    private val notificationRepliedAction: String = "Replied",
) : ProtocolEnvelopeOwner {
    private val stateLock = Any()
    private var active: ProtocolOwnerSession? = null

    override fun activate(session: ProtocolOwnerSession) {
        synchronized(stateLock) { active = session }
        textReceiver.activate(session.epoch)
        privacyResultRetry.activate(session)
        actionResultRetry.activate(session)
        session.transferSender?.bindSession(session.peerFingerprint, session.sessionId)
        receiver.bindSession(session.peerFingerprint, session.sessionId).forEach(::deliverVerified)
    }

    override fun retire(epoch: ProtocolSessionEpoch) {
        val accepted = synchronized(stateLock) {
            if (active?.epoch != epoch) false else {
                active = null
                true
            }
        }
        if (!accepted) return
        textReceiver.retire(epoch)
        privacyResultRetry.retire(epoch)
        actionResultRetry.retire(epoch)
        receiver.recycleSession()
    }

    override fun handleEnvelope(epoch: ProtocolSessionEpoch, envelope: Envelope) {
        val session = synchronized(stateLock) { active?.takeIf { it.epoch == epoch } } ?: return
        try {
            if (envelope.type == Envelope.TYPE_ERROR) {
                events.log(peerErrorLine(envelope))
                return
            }
            val expected = expectedCapability(envelope.type)
            if (expected != null && envelope.capability != expected) {
                sendProtocolError(session, envelope, "plugin/unsupported-capability", "Message type was sent under the wrong capability.")
                return
            }
            if (envelope.capability != null && session.capabilities?.contains(envelope.capability) == false) {
                sendProtocolError(session, envelope, "plugin/unsupported-capability", "Capability is not negotiated for this session.")
                return
            }
            if (!NotificationCapabilityGate.allowsInboundRequest(envelope.type, session.capabilities)) {
                sendProtocolError(session, envelope, "plugin/unsupported-capability", "Notification action is not negotiated for this session.")
                return
            }
            if (handleNotification(session, envelope)) return
            if (envelope.capability == TransferProtocol.CAPABILITY) {
                handleTransfer(session, envelope)
                return
            }
            val request = textReceiver.handle(epoch, envelope)
            if (request != null) {
                events.textConfirmation(request)
                return
            }
        } catch (_: TextConfirmationQueueFullException) {
            sendProtocolError(
                session,
                envelope,
                "protocol/flow-control",
                "Text confirmation queue is full.",
                retryable = true,
            )
            return
        } catch (e: IllegalArgumentException) {
            sendProtocolError(session, envelope, "protocol/invalid-envelope", "Envelope payload is invalid for its type.")
            events.log("session envelope rejected: ${e.message}")
            return
        }
        if (isPluginEnvelope(envelope)) {
            sendProtocolError(session, envelope, "protocol/unknown-type", "No handler is registered for this message type.")
        }
    }

    override fun handleAck(epoch: ProtocolSessionEpoch, messageId: String) {
        if (synchronized(stateLock) { active?.epoch } != epoch) return
        privacyResultRetry.onAck(epoch, messageId)
        actionResultRetry.onAck(epoch, messageId)
    }

    override fun handleAckTimeout(epoch: ProtocolSessionEpoch, messageId: String) {
        if (synchronized(stateLock) { active?.epoch } != epoch) return
        privacyResultRetry.onAckTimeout(epoch, messageId)
        actionResultRetry.onAckTimeout(epoch, messageId)
    }

    override fun pendingTextConfirmations(): List<TextLinkConfirmationRequest> = textReceiver.pending

    override fun resolveTextConfirmation(messageId: String): Boolean = textReceiver.resolve(messageId)

    override fun retryIncomingDelivery(fileId: String): Boolean {
        val completed = receiver.pendingDelivery(fileId) ?: return false
        deliverVerified(completed)
        return true
    }

    override fun cancelTransfer(fileId: String): Boolean {
        val session = synchronized(stateLock) { active } ?: return false
        val sender = session.transferSender
        val cancelled = when {
            sender?.containsTransfer(fileId) == true -> {
                sender.cancel(fileId, discard = true)
                true
            }
            receiver.cancelLocal(fileId, discard = true) -> {
                sender?.sendCancel(TransferCancel(fileId, discard = true))
                true
            }
            else -> false
        }
        if (cancelled) events.activity(ActivityEvent.TransferCancelled(fileId, System.currentTimeMillis()))
        return cancelled
    }

    override fun invalidatePeerCheckpoints(peerFingerprint: ByteArray) {
        receiver.invalidatePeerCheckpoints(peerFingerprint)
        synchronized(stateLock) { active?.transferSender }?.invalidatePeerCheckpoints(peerFingerprint)
    }

    override fun invalidateAllCheckpoints() {
        receiver.invalidateAllCheckpoints()
    }

    private fun handleNotification(session: ProtocolOwnerSession, envelope: Envelope): Boolean {
        if (envelope.type == NotificationProtocol.TYPE_PRIVACY_POLICY) {
            val policy = NotificationPrivacyPolicyHandler.parse(envelope.payload)
            notificationRuntime.setPrivacyPolicy(policy)
            privacyResultRetry.offer(
                session.epoch,
                PendingProtocolResult(
                    NotificationProtocol.TYPE_PRIVACY_RESULT,
                    NotificationProtocol.CAPABILITY,
                    NotificationPrivacyPolicyResult.payload(envelope.messageId, notificationRuntime.localPrivacyFloor()),
                ),
            )
            events.privacyPolicyApplied()
            events.log("notification privacy policy applied: default=${policy.defaultMode.wire}, ${policy.perPackageModes.size} app overrides")
            return true
        }
        if (envelope.type == NotificationProtocol.TYPE_DISMISS_REQUEST) {
            if (!actionResultRetry.tryReserve(session.epoch)) {
                sendProtocolError(
                    session,
                    envelope,
                    "protocol/flow-control",
                    "Notification result queue is full.",
                    retryable = true,
                )
                return true
            }
            val captured = CapturingNotificationOutbox()
            try {
                NotificationDismissRequestHandler(
                    notificationRuntime.canceller(),
                    notificationRuntime::isActive,
                    captured,
                ).handle(envelope)
                if (!actionResultRetry.offerReserved(session.epoch, checkNotNull(captured.result))) {
                    return true
                }
            } catch (error: Exception) {
                actionResultRetry.releaseReservation(session.epoch)
                throw error
            }
            if (captured.wasSuccessful()) {
                events.activity(
                    ActivityEvent.NotificationActionPerformed(
                        notificationLabel,
                        notificationClearedAction,
                        System.currentTimeMillis(),
                    ),
                )
            } else {
                events.log("notification dismiss request completed without clearing the notification")
            }
            return true
        }
        if (envelope.type == NotificationProtocol.TYPE_REPLY_REQUEST) {
            if (!actionResultRetry.tryReserve(session.epoch)) {
                sendProtocolError(
                    session,
                    envelope,
                    "protocol/flow-control",
                    "Notification result queue is full.",
                    retryable = true,
                )
                return true
            }
            val captured = CapturingNotificationOutbox()
            try {
                NotificationReplyRequestHandler(notificationRuntime.replier(), captured).handle(envelope)
                if (!actionResultRetry.offerReserved(session.epoch, checkNotNull(captured.result))) {
                    return true
                }
            } catch (error: Exception) {
                actionResultRetry.releaseReservation(session.epoch)
                throw error
            }
            if (captured.wasSuccessful()) {
                events.activity(
                    ActivityEvent.NotificationActionPerformed(
                        notificationLabel,
                        notificationRepliedAction,
                        System.currentTimeMillis(),
                    ),
                )
            } else {
                events.log("notification reply request completed without sending a reply")
            }
            return true
        }
        return false
    }

    private fun handleTransfer(session: ProtocolOwnerSession, envelope: Envelope) {
        val sender = session.transferSender
        if (envelope.type == TransferProtocol.TYPE_RESUME_INFO) {
            val info = TransferResumeInfo.fromJson(envelope.payload)
            sender?.handleResumeInfo(info)
            events.log("transfer resume continued: ${info.fileId}")
            return
        }
        if (envelope.type == TransferProtocol.TYPE_CANCEL) {
            val cancel = TransferCancel.fromJson(envelope.payload)
            if (sender?.handleRemoteCancel(cancel) == true) {
                events.log("outbound transfer cancelled by peer: ${cancel.fileId}")
            }
        }
        when (val event = receiver.handle(envelope)) {
            is TransferEvent.Completed -> deliverVerified(event.completed)
            is TransferEvent.ResumeRequested -> {
                val id = sender?.sendResumeInfo(event.info)
                events.log(if (id == null) "transfer resume info queued: ${event.info.fileId}" else "transfer resume info sent: $id (${event.info.fileId})")
            }
            is TransferEvent.Cancelled -> {
                events.log("transfer cancelled: ${event.cancel.fileId}")
                events.activity(ActivityEvent.TransferCancelled(event.cancel.fileId, System.currentTimeMillis()))
            }
            TransferEvent.Ignored -> Unit
        }
    }

    private fun deliverVerified(completed: TransferCompleted) {
        try {
            deliverIncoming(completed)
            receiver.markDeliverySucceeded(completed.manifest.fileId)
            events.activity(
                ActivityEvent.TransferCompleted(
                    completed.manifest.fileId,
                    completed.manifest.filename,
                    completed.manifest.sizeBytes,
                    TransferDirection.INCOMING,
                    verified = true,
                    atMillis = System.currentTimeMillis(),
                ),
            )
            events.log("transfer received: ${completed.manifest.filename} (${completed.manifest.sizeBytes} bytes)")
        } catch (e: Exception) {
            receiver.markDeliveryFailed(completed.manifest.fileId)
            events.activity(
                ActivityEvent.TransferDeliveryFailed(
                    completed.manifest.fileId,
                    completed.manifest.filename,
                    completed.manifest.sizeBytes,
                    System.currentTimeMillis(),
                ),
            )
            events.log("transfer received but save failed; verified source queued for retry: ${e.message}")
        }
    }

    private fun sendProtocolError(
        session: ProtocolOwnerSession,
        regarding: Envelope,
        code: String,
        message: String,
        retryable: Boolean = false,
    ) {
        runCatching {
            session.send(
                Envelope.TYPE_ERROR,
                null,
                false,
                Json.obj(
                    "code" to Json.Str(code),
                    "message" to Json.Str(message.take(256)),
                    "regarding" to Json.Str(regarding.messageId),
                    "retryable" to Json.Bool(retryable),
                ),
            )
        }
        events.log("protocol error reported: $code (${regarding.type})")
    }

    private fun expectedCapability(type: String): String? = when (type) {
        NotificationProtocol.TYPE_DISMISS_REQUEST,
        NotificationProtocol.TYPE_REPLY_REQUEST,
        NotificationProtocol.TYPE_PRIVACY_POLICY,
        NotificationProtocol.TYPE_PRIVACY_RESULT -> NotificationProtocol.CAPABILITY
        TransferProtocol.TYPE_MANIFEST,
        TransferProtocol.TYPE_CHUNK,
        TransferProtocol.TYPE_RESUME_REQUEST,
        TransferProtocol.TYPE_RESUME_INFO,
        TransferProtocol.TYPE_CANCEL -> TransferProtocol.CAPABILITY
        TextLinkMessage.TYPE_SEND -> TextLinkMessage.CAPABILITY
        else -> null
    }

    private fun isPluginEnvelope(envelope: Envelope): Boolean = envelope.type !in setOf(
        Envelope.TYPE_ACK,
        Envelope.TYPE_HEARTBEAT,
        Envelope.TYPE_HELLO,
        Envelope.TYPE_ERROR,
    )

    private fun peerErrorLine(envelope: Envelope): String {
        val code = (envelope.payload["code"] as? Json.Str)?.value ?: "unknown"
        val regarding = (envelope.payload["regarding"] as? Json.Str)?.value ?: "none"
        return "peer error: $code regarding $regarding"
    }

    private class CapturingNotificationOutbox : NotificationOutbox {
        var result: PendingProtocolResult? = null
            private set

        override fun send(type: String, capability: String, requiresAck: Boolean, payload: Json.Obj): String {
            result = PendingProtocolResult(type, capability, payload)
            return "queued"
        }

        fun wasSuccessful(): Boolean = (result?.payload?.get("success") as? Json.Bool)?.value == true
    }
}

internal fun createAndroidProtocolEnvelopeOwner(
    context: Context,
    checkpointStore: TransferCheckpointStore,
    events: ProtocolOwnerEvents,
): ProtocolEnvelopeOwner {
    val app = context.applicationContext
    val receiver = TransferReceiver(
        storage = FileTransferStorage(
            File(app.cacheDir, "transfers"),
            isCheckpointActive = { fileId -> checkpointStore.load(fileId) != null },
        ),
        checkpointStore = checkpointStore,
        onProgress = { progress ->
            events.activity(progress.toActivityEvent(TransferDirection.INCOMING, resuming = false))
        },
        onRestored = { progress ->
            events.activity(progress.toActivityEvent(TransferDirection.INCOMING, resuming = true))
        },
    )
    return DefaultProtocolEnvelopeOwner(
        receiver = receiver,
        deliverIncoming = { completed ->
            val destinations = listOfNotNull(app.getExternalFilesDir(null), app.filesDir)
                .distinctBy { it.absolutePath }
                .map { File(it, "received") }
            ReceivedFileDelivery(destinations).deliver(completed)
        },
        notificationRuntime = AndroidNotificationRuntimeFacade,
        events = events,
        notificationLabel = app.getString(R.string.notif_action_name),
        notificationClearedAction = app.getString(R.string.notif_action_cleared),
        notificationRepliedAction = app.getString(R.string.notif_action_replied),
    )
}

private fun TransferProgress.toActivityEvent(
    direction: TransferDirection,
    resuming: Boolean,
): ActivityEvent = if (resuming) {
    ActivityEvent.TransferResuming(
        fileId,
        filename,
        direction,
        completedBytes,
        totalBytes,
        completedChunks,
        totalChunks,
        System.currentTimeMillis(),
    )
} else {
    ActivityEvent.TransferProgressed(
        fileId,
        filename,
        direction,
        completedBytes,
        totalBytes,
        completedChunks,
        totalChunks,
        isComplete,
        System.currentTimeMillis(),
    )
}
