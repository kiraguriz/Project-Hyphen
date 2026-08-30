package dev.hyphen.android.session

import android.content.Context
import android.os.Build
import dev.hyphen.android.diagnostics.DiagnosticProtocolSessionListener
import dev.hyphen.android.diagnostics.DiagnosticsRuntime
import dev.hyphen.android.diagnostics.LocalStructuredLogStore
import dev.hyphen.android.notifications.HyphenNotificationListenerRuntime
import dev.hyphen.android.notifications.NotificationCapabilityGate
import dev.hyphen.android.notifications.NotificationPrivacyPreferences
import dev.hyphen.android.notifications.ProtocolSessionNotificationOutbox
import dev.hyphen.android.pairing.ParsedEndpoint
import dev.hyphen.android.transport.AndroidKeystoreTlsIdentity
import dev.hyphen.android.transport.Envelope
import dev.hyphen.android.transport.HeartbeatMonitor
import dev.hyphen.android.transport.ProtocolSession
import dev.hyphen.android.transport.ReconnectRetryOutcome
import dev.hyphen.android.transport.SessionHandshake
import dev.hyphen.android.transport.SessionReconnector
import dev.hyphen.android.transport.TlsClient
import dev.hyphen.android.transfer.ProtocolSessionTransferOutbox
import dev.hyphen.android.transfer.TransferCheckpointStore
import dev.hyphen.android.transfer.TransferProgress
import dev.hyphen.android.transfer.TransferSender
import dev.hyphen.android.transfer.OutboundTransferRetirement
import dev.hyphen.android.text.TextLinkConfirmationRequest
import dev.hyphen.android.trust.TrustedPeer
import dev.hyphen.android.ui.ActivityEvent
import dev.hyphen.android.ui.ConnectionState
import dev.hyphen.android.service.CompanionConnectionPreferences
import dev.hyphen.android.service.CompanionConnectionServiceController
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLSocket

internal interface TrustedPeerReconnectOwner {
    fun start()
    fun stop()
    fun retryNow(): ReconnectRetryOutcome = ReconnectRetryOutcome.ALREADY_IN_PROGRESS
}

enum class ConnectionRetryResult {
    STARTED,
    ALREADY_IN_PROGRESS,
    UNAVAILABLE,
}

internal data class TrustedPeerReconnectRequest(
    val endpoint: ParsedEndpoint,
    val peerFingerprint: ByteArray,
    val initialResumeToken: String?,
    val initialPreviousSessionId: String?,
    val listener: SessionReconnector.Listener,
)

internal fun interface TrustedPeerReconnectFactory {
    fun create(request: TrustedPeerReconnectRequest): TrustedPeerReconnectOwner
}

private object DefaultTrustedPeerReconnectFactory : TrustedPeerReconnectFactory {
    override fun create(request: TrustedPeerReconnectRequest): TrustedPeerReconnectOwner {
        val reconnect = SessionReconnector(
            dial = {
                val identity = AndroidKeystoreTlsIdentity.getOrCreate()
                TlsClient.connect(
                    host = request.endpoint.host,
                    port = request.endpoint.port,
                    identity = identity,
                    isTrusted = { it.contentEquals(request.peerFingerprint) },
                )
            },
            device = SessionHandshake.DeviceInfo("android", "0.0.1", Build.MODEL),
            initialResumeToken = request.initialResumeToken,
            initialPreviousSessionId = request.initialPreviousSessionId,
            listener = request.listener,
        )
        return object : TrustedPeerReconnectOwner {
            override fun start() = reconnect.start()
            override fun stop() = reconnect.stop()
            override fun retryNow(): ReconnectRetryOutcome = reconnect.retryNow()
        }
    }
}

/**
 * Process-level owner for the paired transport session (HYP-M2-013 product
 * wiring). Activity destroy/recreate does not stop an active session; trust
 * revoke/reset calls [stopAfterTrustChange].
 */
class ConnectionSupervisor internal constructor(
    private val diagnosticLogs: LocalStructuredLogStore,
    private val peerLinkLifecycle: PeerLinkLifecycle,
    private val reconnectAfterRestore: (() -> Unit)? = null,
    private val reconnectFactory: TrustedPeerReconnectFactory = DefaultTrustedPeerReconnectFactory,
    private val beforeReconnectSessionActivation: () -> Unit = {},
    private val beforePairedSessionActivation: () -> Unit = {},
    private val beforeTransferSenderInspection: () -> Unit = {},
    private val beforeStateReplayDelivery: () -> Unit = {},
    private val beforePeerLinkConnectionPublished: () -> Unit = {},
    private val beforeRetryConnectionPublished: () -> Unit = {},
    private val beforeReconnectStartFailurePublished: () -> Unit = {},
    private val onOutboundReselectStateChanged: (Boolean) -> Unit = {},
    protocolOwnerFactory: (ProtocolOwnerEvents) -> ProtocolEnvelopeOwner = { NoOpProtocolEnvelopeOwner },
) {
    data class ActiveState(
        val session: ProtocolSession?,
        val capabilities: SessionHandshake.NegotiatedCapabilities?,
        val transferSender: TransferSender?,
        val lastTransferProgress: TransferProgress?,
    )

    private data class RetiredActiveState(
        val session: ProtocolSession?,
        val transferSender: TransferSender?,
        val epoch: ProtocolSessionEpoch,
        val peerGeneration: Long?,
        val peerFingerprint: ByteArray?,
    )

    private data class ConnectionEventSnapshot(
        val peerGeneration: Long,
        val sequence: Long,
        val event: ActivityEvent.ConnectionChanged,
    )

    private data class ConnectionDelivery(
        val peerGeneration: Long,
        val sequence: Long,
        val event: ActivityEvent.ConnectionChanged,
        val replayPeerName: String? = null,
        val includesPeerReplay: Boolean = false,
    )

    private data class UserPause(
        val reconnector: TrustedPeerReconnectOwner?,
        val previous: RetiredActiveState,
    )

    interface Listener {
        fun onLog(line: String) {}
        fun onActivityEvent(event: ActivityEvent) {}
        fun onTransferProgress(progress: TransferProgress, outgoing: Boolean) {}
        fun onTransferRestored(progress: TransferProgress, outgoing: Boolean) {
            onTransferProgress(progress, outgoing)
        }
        fun onTextConfirmation(request: TextLinkConfirmationRequest) {}
        fun onPrivacyPolicyApplied() {}
        fun onOutboundReselectRequiredChanged(required: Boolean) {}
    }

    /**
     * Serializes connection-state callbacks for one observer without holding
     * the connection-delivery lock while user code executes. A newer state
     * supersedes an older replay paused between snapshot and delivery.
     */
    private class ConnectionDeliverySlot(val listener: Listener) {
        var lastDeliveredSequence = 0L
        var inFlightSequence = 0L
        var pending: ConnectionDelivery? = null
        var delivering = false
        var active = true
    }

    private data class StateReplaySnapshot(
        val peerGeneration: Long,
        val connection: ConnectionDelivery?,
        val transferEvents: List<ActivityEvent>,
        val reselectRequired: Boolean,
        val pendingTextConfirmations: List<TextLinkConfirmationRequest>,
    )

    private val stateLock = Any()
    private val ownershipTransitionLock = Any()
    private val peerLinkStorageLock = Any()
    private val connectionDeliveryLock = Any()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val connectionDeliverySlots = IdentityHashMap<Listener, ConnectionDeliverySlot>()
    private var activeSession: ProtocolSession? = null
    private var latestConnectionEvent: ConnectionEventSnapshot? = null
    private var connectionEventSequence = 0L
    private var activeCapabilities: SessionHandshake.NegotiatedCapabilities? = null
    private var activeTransferSender: TransferSender? = null
    private var activeProtocolEpoch: ProtocolSessionEpoch? = null
    private var activePeerGeneration: Long? = null
    private var protocolEpochCounter = 0L
    private var lastTransferProgress: TransferProgress? = null
    private var resumeToken: String? = null
    private var lastSessionId: String? = null
    private var lastPeerName: String? = null
    private var rememberedEndpoint: ParsedEndpoint? = null
    private var trustedPeerFingerprint: ByteArray? = null
    private var reconnector: TrustedPeerReconnectOwner? = null
    private var reconnectOwnerToken: Any? = null
    private var activeSessionReconnectToken: Any? = null
    private var peerLinkGeneration = 0L
    private var stoppedForTrustChange = false
    private var pausedByUser = false
    private var transferCheckpointStore: TransferCheckpointStore? = null
    private val recentTransferEvents = linkedMapOf<String, ActivityEvent>()
    private var recentTransferEventsGeneration: Long? = null
    private val pendingOutboundReselect = linkedMapOf<String, OutboundTransferRetirement>()
    private var selectedOutboundReselectFileId: String? = null
    private val protocolOwner: ProtocolEnvelopeOwner = protocolOwnerFactory(
        object : ProtocolOwnerEvents {
            override fun log(line: String) = this@ConnectionSupervisor.log(line)
            override fun activity(event: ActivityEvent) = emit(event)
            override fun textConfirmation(request: TextLinkConfirmationRequest) {
                listeners.forEach { it.onTextConfirmation(request) }
            }
            override fun privacyPolicyApplied() {
                listeners.forEach { it.onPrivacyPolicyApplied() }
            }
        },
    )

    fun addListener(listener: Listener) {
        synchronized(connectionDeliveryLock) {
            if (listeners.none { it === listener }) listeners.add(listener)
            connectionDeliverySlots.getOrPut(listener) { ConnectionDeliverySlot(listener) }
        }
    }

    /** Atomically subscribes before replay so newer connection events win. */
    fun addListenerAndReplay(listener: Listener) {
        val slot = synchronized(connectionDeliveryLock) {
            if (listeners.none { it === listener }) listeners.add(listener)
            connectionDeliverySlots.getOrPut(listener) { ConnectionDeliverySlot(listener) }
        }
        deliverStateReplay(slot, stateReplaySnapshot())
    }

    /**
     * Replays the current connection state to a freshly attached listener so a
     * recreated Activity reflects a live session instead of showing disconnected.
     */
    fun replayStateTo(listener: Listener) {
        val slot = synchronized(connectionDeliveryLock) {
            connectionDeliverySlots[listener] ?: ConnectionDeliverySlot(listener)
        }
        deliverStateReplay(slot, stateReplaySnapshot())
    }

    private fun stateReplaySnapshot(): StateReplaySnapshot {
        val peerName: String?
        val peerGeneration: Long
        val hasTrustedPeer: Boolean
        val transferEvents: List<ActivityEvent>
        val reselectRequired: Boolean
        synchronized(stateLock) {
            peerName = lastPeerName
            peerGeneration = peerLinkGeneration
            hasTrustedPeer = trustedPeerFingerprint != null
            transferEvents = if (
                trustedPeerFingerprint != null && recentTransferEventsGeneration == peerLinkGeneration
            ) {
                recentTransferEvents.values.toList()
            } else {
                emptyList()
            }
            reselectRequired = pendingOutboundReselect.isNotEmpty()
        }
        val connection = synchronized(connectionDeliveryLock) {
            latestConnectionEvent
                ?.takeIf { hasTrustedPeer && it.peerGeneration == peerGeneration }
                ?.let {
                    ConnectionDelivery(
                        peerGeneration = it.peerGeneration,
                        sequence = it.sequence,
                        event = it.event,
                        replayPeerName = peerName ?: "Mac",
                        includesPeerReplay = true,
                    )
                }
        }
        val generationStillCurrent = synchronized(stateLock) {
            peerLinkGeneration == peerGeneration &&
                (trustedPeerFingerprint != null) == hasTrustedPeer
        }
        return StateReplaySnapshot(
            peerGeneration = peerGeneration,
            connection = connection.takeIf { generationStillCurrent },
            transferEvents = transferEvents.takeIf { generationStillCurrent }.orEmpty(),
            reselectRequired = reselectRequired && generationStillCurrent,
            pendingTextConfirmations = if (hasTrustedPeer && generationStillCurrent) {
                protocolOwner.pendingTextConfirmations()
            } else {
                emptyList()
            },
        )
    }

    private fun deliverStateReplay(slot: ConnectionDeliverySlot, snapshot: StateReplaySnapshot) {
        beforeStateReplayDelivery()
        snapshot.connection?.let { enqueueConnectionDelivery(slot, it) }
        snapshot.transferEvents.forEach { event ->
            if (!isReplayDeliveryCurrent(slot, snapshot.peerGeneration)) return
            runCatching { slot.listener.onActivityEvent(event) }
        }
        if (!isReplayDeliveryCurrent(slot, snapshot.peerGeneration)) return
        runCatching {
            slot.listener.onOutboundReselectRequiredChanged(snapshot.reselectRequired)
        }
        snapshot.pendingTextConfirmations.forEach { request ->
            if (!isReplayDeliveryCurrent(slot, snapshot.peerGeneration)) return
            runCatching { slot.listener.onTextConfirmation(request) }
        }
    }

    fun removeListener(listener: Listener) {
        synchronized(connectionDeliveryLock) {
            val index = listeners.indexOfFirst { it === listener }
            if (index >= 0) listeners.removeAt(index)
            connectionDeliverySlots.remove(listener)?.let { slot ->
                synchronized(slot) {
                    slot.active = false
                    slot.pending = null
                }
            }
        }
    }

    fun activeState(): ActiveState =
        synchronized(stateLock) {
            ActiveState(
                session = activeSession,
                capabilities = activeCapabilities,
                transferSender = activeTransferSender,
                lastTransferProgress = lastTransferProgress,
            )
        }

    fun currentSessionId(): String? = synchronized(stateLock) { lastSessionId }

    fun currentPeerFingerprint(): ByteArray? =
        synchronized(stateLock) { trustedPeerFingerprint?.copyOf() }

    fun configureTransferCheckpoints(store: TransferCheckpointStore) {
        synchronized(stateLock) { transferCheckpointStore = store }
    }

    fun invalidateTransferCheckpoints(peerFingerprint: ByteArray) {
        protocolOwner.invalidatePeerCheckpoints(peerFingerprint)
        val (sender, store) = synchronized(stateLock) {
            activeTransferSender to transferCheckpointStore
        }
        sender?.invalidatePeerCheckpoints(peerFingerprint)
        store?.invalidatePeer(peerFingerprint)
    }

    fun invalidateAllTransferCheckpoints() {
        protocolOwner.invalidateAllCheckpoints()
        val store = synchronized(stateLock) { transferCheckpointStore }
        store?.invalidateAll()
    }

    fun resolveTextConfirmation(messageId: String): Boolean =
        protocolOwner.resolveTextConfirmation(messageId)

    fun retryIncomingDelivery(fileId: String): Boolean =
        protocolOwner.retryIncomingDelivery(fileId)

    fun cancelTransfer(fileId: String): Boolean = protocolOwner.cancelTransfer(fileId)

    fun selectOutboundSourceReselection(fileId: String? = null): Boolean = synchronized(stateLock) {
        val selected = fileId ?: pendingOutboundReselect.keys.firstOrNull() ?: return false
        if (selected !in pendingOutboundReselect) return false
        selectedOutboundReselectFileId = selected
        true
    }

    fun selectedOutboundSourceReselectionFileId(): String? = synchronized(stateLock) {
        selectedOutboundReselectFileId?.takeIf { it in pendingOutboundReselect }
    }

    fun clearOutboundSourceReselectionSelection() {
        synchronized(stateLock) { selectedOutboundReselectFileId = null }
    }

    fun isOutboundSourceReselectionRequired(fileId: String): Boolean =
        synchronized(stateLock) { fileId in pendingOutboundReselect }

    fun outboundSourceReselection(fileId: String): OutboundTransferRetirement? =
        synchronized(stateLock) { pendingOutboundReselect[fileId] }

    fun completeOutboundSourceReselection(fileId: String, sender: TransferSender): Boolean {
        if (synchronized(stateLock) { activeTransferSender !== sender }) return false
        if (!sender.containsTransfer(fileId)) return false
        val required = synchronized(stateLock) {
            if (activeTransferSender !== sender) return false
            pendingOutboundReselect.remove(fileId)
            if (selectedOutboundReselectFileId == fileId) selectedOutboundReselectFileId = null
            if (recentTransferEvents[fileId] is ActivityEvent.TransferSourceReselectionRequired) {
                recentTransferEvents.remove(fileId)
            }
            pendingOutboundReselect.isNotEmpty()
        }
        onOutboundReselectStateChanged(required)
        listeners.forEach { it.onOutboundReselectRequiredChanged(required) }
        return true
    }

    internal fun dispatchProtocolEnvelopeForTest(epoch: ProtocolSessionEpoch, envelope: Envelope) {
        protocolOwner.handleEnvelope(epoch, envelope)
    }

    internal fun currentPeerGenerationForTest(): Long = synchronized(stateLock) { peerLinkGeneration }

    internal fun acceptOutboundRetirementsForTest(
        retirements: List<OutboundTransferRetirement>,
        peerGeneration: Long,
        peerFingerprint: ByteArray,
    ) {
        acceptOutboundRetirements(retirements, peerGeneration, peerFingerprint)
    }

    /** Process-owned work is not interrupted by Activity recreation. */
    fun launchProcessWorker(name: String, work: () -> Unit): Boolean = runCatching {
        Thread(work, name).apply { isDaemon = true }.start()
        true
    }.getOrDefault(false)

    /** Records a process-owned send failure without retaining an Activity or its selected URI. */
    fun reportProcessTransferFailure(fileId: String) {
        val retry = synchronized(stateLock) { pendingOutboundReselect[fileId] }
        if (retry == null) {
            emit(ActivityEvent.TransferCancelled(fileId, System.currentTimeMillis()))
        } else {
            emit(
                ActivityEvent.TransferSourceReselectionRequired(
                    retry.fileId,
                    retry.filename,
                    retry.sizeBytes,
                    System.currentTimeMillis(),
                ),
            )
            onOutboundReselectStateChanged(true)
            listeners.forEach { it.onOutboundReselectRequiredChanged(true) }
        }
        log("transfer/send failed")
    }

    /**
     * Resolve the begin-to-FGS-registration race without lying about resume.
     * If this sender still owns the current peer, detach the source without a
     * wire cancel and publish per-file reselection. If an FGS abort already
     * cancelled it, publish only a fresh-selection marker. Trust replacement
     * publishes neither into the new generation.
     */
    fun handleForegroundRegistrationLoss(fileId: String, sender: TransferSender) {
        val owner = synchronized(stateLock) {
            if (activeTransferSender !== sender) null else {
                Pair(activePeerGeneration, trustedPeerFingerprint?.copyOf())
            }
        }
        val retirement = sender.retireRegisteredForSourceReselection(fileId)
        if (retirement != null && owner != null) {
            acceptOutboundRetirements(listOf(retirement), owner.first, owner.second)
        }
        val outcome = synchronized(stateLock) {
            when {
                fileId in pendingOutboundReselect -> 1
                activeTransferSender === sender && trustedPeerFingerprint != null -> 2
                else -> 0
            }
        }
        when (outcome) {
            1 -> Unit // acceptOutboundRetirements already published the marker.
            2 -> {
                emit(ActivityEvent.TransferCancelled(fileId, System.currentTimeMillis()))
                onOutboundReselectStateChanged(true)
                listeners.forEach { it.onOutboundReselectRequiredChanged(true) }
            }
            else -> Unit // A trust/session generation replacement owns the outcome.
        }
        log("transfer/send foreground registration lost")
    }

    fun updateLastTransferProgress(progress: TransferProgress?) {
        synchronized(stateLock) { lastTransferProgress = progress }
    }

    /** Stops outbound transfer work without tearing down the shared link. */
    fun cancelOutboundTransfersForForegroundStop(): List<String> {
        val sender = synchronized(stateLock) {
            lastTransferProgress = null
            activeTransferSender
        } ?: return emptyList()
        val cancelled = runCatching { sender.cancelAll(discard = true) }
            .getOrElse {
                log("outbound transfer cleanup failed after foreground ownership ended")
                emptyList()
            }
        val now = System.currentTimeMillis()
        cancelled.forEach { emit(ActivityEvent.TransferCancelled(it, now)) }
        val reselectRequired = synchronized(stateLock) {
            cancelled.forEach { fileId ->
                pendingOutboundReselect.remove(fileId)
                if (selectedOutboundReselectFileId == fileId) selectedOutboundReselectFileId = null
            }
            pendingOutboundReselect.isNotEmpty()
        }
        if (cancelled.isNotEmpty()) {
            onOutboundReselectStateChanged(reselectRequired)
            listeners.forEach { it.onOutboundReselectRequiredChanged(reselectRequired) }
        }
        return cancelled
    }

    fun hasInFlightOutboundTransfers(): Boolean {
        val sender = synchronized(stateLock) { activeTransferSender }
        beforeTransferSenderInspection()
        return sender?.hasInFlightOutboundTransfers() == true
    }

    fun rememberEndpoint(endpoint: ParsedEndpoint, peerFingerprint: ByteArray): Boolean =
        replacePeerLink(endpoint, peerFingerprint) {
            val clearResult = runCatching { peerLinkLifecycle.invalidate() }
            val rememberResult = runCatching {
                peerLinkLifecycle.remember(endpoint, peerFingerprint)
            }
            rememberResult.exceptionOrNull()?.let { throw it }
            clearResult.exceptionOrNull()?.let { throw it }
        }

    /**
     * Persists a bilateral-SAS result without ever writing trust before its
     * durable route. The lifecycle owns both stores and publishes the peer
     * only after both writes succeed.
     */
    fun commitPairingEndpoint(endpoint: ParsedEndpoint, peer: TrustedPeer): Boolean =
        replacePeerLink(endpoint, peer.spkiFingerprint) {
            peerLinkLifecycle.commitPairing(endpoint, peer)
        }

    private fun replacePeerLink(
        endpoint: ParsedEndpoint,
        peerFingerprint: ByteArray,
        persist: () -> Unit,
    ): Boolean {
        data class PeerSwitch(
            val generation: Long,
            val previousReconnector: TrustedPeerReconnectOwner?,
            val previous: RetiredActiveState,
            val clearedReselectState: Boolean,
        )
        var persistenceFailure: Throwable? = null
        val switched = synchronized(ownershipTransitionLock) {
            val transition = synchronized(stateLock) {
                peerLinkGeneration += 1
                recentTransferEvents.clear()
                recentTransferEventsGeneration = null
                val clearedReselectState = pendingOutboundReselect.isNotEmpty()
                pendingOutboundReselect.clear()
                selectedOutboundReselectFileId = null
                val previousReconnector = reconnector
                reconnector = null
                reconnectOwnerToken = null
                val previous = retireActiveStateLocked()
                resumeToken = null
                lastSessionId = null
                lastPeerName = null
                // Enter a generation-owned transition before touching disk:
                // old callbacks/outboxes are dead, but B is not published as
                // current until its route replacement has been attempted.
                rememberedEndpoint = null
                trustedPeerFingerprint = null
                stoppedForTrustChange = true
                pausedByUser = false
                PeerSwitch(peerLinkGeneration, previousReconnector, previous, clearedReselectState)
            }
            HyphenNotificationListenerRuntime.clearNotificationStateAfterTrustChange()
            synchronized(peerLinkStorageLock) {
                persistenceFailure = runCatching(persist).exceptionOrNull()
                if (persistenceFailure != null) runCatching { peerLinkLifecycle.invalidate() }
            }
            if (persistenceFailure == null) {
                synchronized(stateLock) {
                    check(peerLinkGeneration == transition.generation)
                    rememberedEndpoint = endpoint
                    trustedPeerFingerprint = peerFingerprint.copyOf()
                    stoppedForTrustChange = false
                    pausedByUser = false
                }
                beforePeerLinkConnectionPublished()
                emit(ActivityEvent.ConnectionChanged(ConnectionState.RECONNECTING, null))
            } else {
                emit(ActivityEvent.ConnectionChanged(ConnectionState.SUSPENDED, null))
            }
            transition
        }
        // Generation changes reject every old callback before transport
        // teardown. Stop potentially blocking owners outside the transition
        // monitor, just as the trust-revocation path does.
        switched.previousReconnector?.stop()
        recycleRetiredState(switched.previous)
        switched.previous.session?.stop()
        if (switched.clearedReselectState) {
            onOutboundReselectStateChanged(false)
            listeners.forEach { it.onOutboundReselectRequiredChanged(false) }
        }
        if (persistenceFailure != null) {
            recordPeerLinkFailure("trust/peer-link-persist-failed", "remember-link")
            log("trusted peer link could not be saved for cold-start recovery")
        }
        return persistenceFailure == null
    }

    /**
     * Restores only a descriptor whose fingerprint is still present in the
     * trust store, then starts a fresh pinned reconnect. Session ids and resume
     * tokens are intentionally reset across process death.
     */
    fun restorePersistedLink(): Boolean {
        val restoreGeneration = synchronized(stateLock) { peerLinkGeneration }
        val restored = try {
            synchronized(peerLinkStorageLock) {
                peerLinkLifecycle.restoreTrusted()
            }
        } catch (_: Exception) {
            recordPeerLinkFailure("trust/peer-link-restore-failed", "restore-link")
            log("trusted peer link could not be restored")
            return false
        } ?: return false

        val adoptedGeneration = synchronized(ownershipTransitionLock) {
            val adoptedGeneration = synchronized(stateLock) {
                if (
                    restoreGeneration != peerLinkGeneration ||
                    stoppedForTrustChange ||
                    pausedByUser ||
                    activeSession != null ||
                    reconnector != null ||
                    rememberedEndpoint != null
                ) {
                    null
                } else {
                    peerLinkGeneration += 1
                    rememberedEndpoint = restored.endpoint
                    trustedPeerFingerprint = restored.peer.spkiFingerprint.copyOf()
                    lastPeerName = restored.peer.displayName.ifBlank { "Mac" }
                    resumeToken = null
                    lastSessionId = null
                    activeSessionReconnectToken = null
                    peerLinkGeneration
                }
            }
            if (adoptedGeneration == null) {
                null
            } else {
                emit(ActivityEvent.PeerChanged(isPaired = true, peerName = restored.peer.displayName.ifBlank { "Mac" }))
                val ownsAfterPeer = synchronized(stateLock) {
                    ownsPeerLinkLocked(adoptedGeneration, restored.endpoint, restored.peer.spkiFingerprint)
                }
                if (!ownsAfterPeer) {
                    null
                } else {
                    emitConnectionForGeneration(
                        ActivityEvent.ConnectionChanged(ConnectionState.RECONNECTING, null),
                        adoptedGeneration,
                    )
                    if (
                        synchronized(stateLock) {
                            ownsPeerLinkLocked(
                                adoptedGeneration,
                                restored.endpoint,
                                restored.peer.spkiFingerprint,
                            )
                        }
                    ) adoptedGeneration else null
                }
            }
        } ?: return false

        val reconnectStarted = reconnectAfterRestore?.let { start ->
            runCatching {
                start()
                synchronized(stateLock) {
                    ownsPeerLinkLocked(adoptedGeneration, restored.endpoint, restored.peer.spkiFingerprint)
                }
            }.getOrDefault(false)
        } ?: startReconnector(expectedGeneration = adoptedGeneration)
        if (!reconnectStarted) {
            beforeReconnectStartFailurePublished()
            emitConnectionForGeneration(
                ActivityEvent.ConnectionChanged(ConnectionState.SUSPENDED, null),
                adoptedGeneration,
            )
            log("trusted peer route restored, but reconnect owner did not start")
        }
        return reconnectStarted
    }

    fun adoptPairedSession(
        socket: SSLSocket,
        handshake: SessionHandshake.Result,
        peerName: String,
    ): Boolean {
        data class Ownership(
            val generation: Long,
            val oldReconnector: TrustedPeerReconnectOwner?,
            val previous: RetiredActiveState,
        )
        val ownership = synchronized(ownershipTransitionLock) {
            synchronized(stateLock) {
                if (
                    stoppedForTrustChange ||
                    pausedByUser ||
                    rememberedEndpoint == null ||
                    trustedPeerFingerprint == null
                ) {
                    null
                } else {
                    val current = Ownership(
                        generation = peerLinkGeneration,
                        oldReconnector = reconnector,
                        previous = retireActiveStateLocked(),
                    )
                    reconnector = null
                    reconnectOwnerToken = null
                    current
                }
            }
        }
        if (ownership == null) {
            runCatching { socket.close() }
            return false
        }
        ownership.oldReconnector?.stop()
        recycleRetiredState(ownership.previous)
        ownership.previous.session?.stop()
        val session = buildSession(socket, handshake.sessionId, ownership.generation)
        val activated = runCatching {
            beforePairedSessionActivation()
            bindPairedSession(session, handshake, peerName, ownership.generation)
        }.getOrDefault(false)
        val ownerActivated = activated && activateProtocolOwner(session, ownership.generation)
        if (ownerActivated) {
            session.start()
        }
        if (!ownerActivated) {
            session.stop()
        }
        return ownerActivated
    }

    fun stopAfterTrustChange() {
        data class TrustStop(
            val reconnector: TrustedPeerReconnectOwner?,
            val previous: RetiredActiveState,
            val peerFingerprint: ByteArray?,
            val checkpointStore: TransferCheckpointStore?,
            val clearedReselectState: Boolean,
        )
        val stop = synchronized(ownershipTransitionLock) {
            val captured = synchronized(stateLock) {
                peerLinkGeneration += 1
                recentTransferEvents.clear()
                recentTransferEventsGeneration = null
                val clearedReselectState = pendingOutboundReselect.isNotEmpty()
                pendingOutboundReselect.clear()
                selectedOutboundReselectFileId = null
                stoppedForTrustChange = true
                pausedByUser = false
                val peerFingerprint = trustedPeerFingerprint?.copyOf()
                rememberedEndpoint = null
                trustedPeerFingerprint = null
                resumeToken = null
                lastSessionId = null
                lastPeerName = null
                val oldReconnector = reconnector
                reconnector = null
                reconnectOwnerToken = null
                val previous = retireActiveStateLocked()
                TrustStop(
                    oldReconnector,
                    previous,
                    peerFingerprint,
                    transferCheckpointStore,
                    clearedReselectState,
                )
            }
            try {
                synchronized(peerLinkStorageLock) {
                    peerLinkLifecycle.invalidate()
                }
            } catch (_: Exception) {
                // Trust is already gone, so a stale descriptor remains unable
                // to pass restoreTrusted(). Surface the storage failure without
                // putting routing data or fingerprints in diagnostics.
                recordPeerLinkFailure("trust/peer-link-invalidate-failed", "invalidate-link")
                log("trusted peer link could not be removed from local recovery storage")
            }
            HyphenNotificationListenerRuntime.clearNotificationStateAfterTrustChange()
            emit(ActivityEvent.ConnectionChanged(ConnectionState.SUSPENDED, null))
            log("session stopped after trust change")
            captured.peerFingerprint?.let { captured.checkpointStore?.invalidatePeer(it) }
            captured
        }
        // Stop potentially blocking transport owners after publishing the
        // generation change. Their callbacks re-enter through the ownership
        // gate and are rejected, while no transport monitor is held here.
        stop.reconnector?.stop()
        recycleRetiredState(stop.previous)
        stop.previous.session?.stop()
        if (stop.clearedReselectState) {
            onOutboundReselectStateChanged(false)
            listeners.forEach { it.onOutboundReselectRequiredChanged(false) }
        }
    }

    /**
     * Stops live transport ownership without deleting trust or the durable
     * peer-link descriptor. This is the user-facing FGS disconnect control;
     * [stopAfterTrustChange] remains the destructive revoke/reset path.
     */
    fun pauseConnection() {
        teardownUserPause(retireForUserPause())
    }

    /**
     * Activity lifecycle variant: return immediately, then settle any pairing
     * retirement before publishing the final paused generation and tearing down
     * potentially blocking transport/socket ownership on a process worker.
     */
    fun pauseConnectionForLifecycle(beforePause: () -> Unit = {}): Boolean =
        launchProcessWorker("hyphen-connection-lifecycle-pause") {
            // Pairing cancellation may be waiting for a durable route commit.
            // Finish it first so this pause becomes the final generation write.
            beforePause()
            pauseConnection()
        }

    private fun retireForUserPause(): UserPause =
        synchronized(ownershipTransitionLock) {
            val captured = synchronized(stateLock) {
                peerLinkGeneration += 1
                if (recentTransferEvents.isNotEmpty()) {
                    recentTransferEventsGeneration = peerLinkGeneration
                }
                pausedByUser = true
                val oldReconnector = reconnector
                reconnector = null
                reconnectOwnerToken = null
                val previous = retireActiveStateLocked()
                UserPause(oldReconnector, previous)
            }
            HyphenNotificationListenerRuntime.clearNotificationOutbox()
            emit(ActivityEvent.ConnectionChanged(ConnectionState.SUSPENDED, null))
            log("companion connection paused by user")
            captured
        }

    private fun teardownUserPause(paused: UserPause) {
        paused.reconnector?.stop()
        recycleRetiredState(paused.previous)
        paused.previous.session?.stop()
    }

    /** Re-enters reconnect ownership after an explicit user-visible action. */
    fun resumeConnection(): Boolean {
        val hasInMemoryRoute = synchronized(ownershipTransitionLock) {
            synchronized(stateLock) {
                if (stoppedForTrustChange) {
                    false
                } else if (activeSession != null || reconnector != null) {
                    pausedByUser = false
                    true
                } else {
                    pausedByUser = false
                    rememberedEndpoint != null && trustedPeerFingerprint != null
                }
            }
        }
        if (hasInMemoryRoute) {
            val alreadyOwned = synchronized(stateLock) {
                activeSession != null || reconnector != null
            }
            return alreadyOwned || startReconnector()
        }
        return restorePersistedLink()
    }

    /**
     * User-visible recovery action. A live but degraded session is closed so
     * the same trusted-peer generation enters reconnect ownership; a pending
     * backoff is superseded without permitting stale attempts to publish.
     */
    fun retryConnectionNow(): ConnectionRetryResult {
        val wasPaused = synchronized(stateLock) { pausedByUser && !stoppedForTrustChange }
        if (wasPaused) {
            return if (resumeConnection()) ConnectionRetryResult.STARTED else ConnectionRetryResult.UNAVAILABLE
        }

        val liveSession = synchronized(stateLock) {
            if (stoppedForTrustChange || pausedByUser) null else activeSession
        }
        liveSession?.stop()

        val ownerRetry = synchronized(ownershipTransitionLock) {
            val ownerAndGeneration = synchronized(stateLock) {
                if (stoppedForTrustChange || pausedByUser) {
                    null
                } else {
                    reconnector?.let { it to peerLinkGeneration }
                }
            } ?: return@synchronized null
            val (owner, generation) = ownerAndGeneration
            val outcome = runCatching { owner.retryNow() }
                .getOrDefault(ReconnectRetryOutcome.FAILED)
            val stillOwned = synchronized(stateLock) {
                !stoppedForTrustChange &&
                    !pausedByUser &&
                    peerLinkGeneration == generation &&
                    reconnector === owner
            }
            if (!stillOwned) {
                null
            } else {
                when (outcome) {
                    ReconnectRetryOutcome.SCHEDULED -> {
                        beforeRetryConnectionPublished()
                        emitConnectionForGeneration(
                            ActivityEvent.ConnectionChanged(ConnectionState.RECONNECTING, null),
                            generation,
                        )
                        log("connection retry requested by user")
                        ConnectionRetryResult.STARTED
                    }

                    ReconnectRetryOutcome.ALREADY_IN_PROGRESS -> {
                        log("connection retry already in progress")
                        ConnectionRetryResult.ALREADY_IN_PROGRESS
                    }

                    ReconnectRetryOutcome.FAILED -> {
                        log("connection retry could not be scheduled")
                        ConnectionRetryResult.UNAVAILABLE
                    }
                }
            }
        }
        if (ownerRetry != null) return ownerRetry

        val hasRoute = synchronized(stateLock) {
            !stoppedForTrustChange &&
                !pausedByUser &&
                rememberedEndpoint != null &&
                trustedPeerFingerprint != null
        }
        if (hasRoute) {
            return if (startReconnector()) {
                ConnectionRetryResult.STARTED
            } else {
                ConnectionRetryResult.UNAVAILABLE
            }
        }
        return if (restorePersistedLink()) {
            ConnectionRetryResult.STARTED
        } else {
            ConnectionRetryResult.UNAVAILABLE
        }
    }

    private fun bindReconnectedSession(
        session: ProtocolSession,
        handshake: SessionHandshake.Result,
        peerName: String,
        generation: Long,
        ownerToken: Any,
    ): Boolean = synchronized(ownershipTransitionLock) {
        val installed = synchronized(stateLock) {
            if (!ownsReconnectLocked(generation, ownerToken)) {
                false
            } else {
                resumeToken = handshake.resumeToken
                lastSessionId = handshake.sessionId
                activeSession = session
                protocolEpochCounter += 1
                activeProtocolEpoch = ProtocolSessionEpoch.of(protocolEpochCounter)
                activePeerGeneration = generation
                activeSessionReconnectToken = ownerToken
                activeCapabilities = handshake.negotiatedCapabilities
                activeTransferSender = TransferSender(
                    ProtocolSessionTransferOutbox(session),
                    handshake.negotiatedCapabilities,
                    checkpointStore = transferCheckpointStore,
                    onProgress = { progress -> handleOutgoingTransferProgress(progress, restored = false) },
                    onRestored = { progress -> handleOutgoingTransferProgress(progress, restored = true) },
                )
                lastTransferProgress = null
                lastPeerName = peerName
                true
            }
        }
        if (!installed) {
            false
        } else {
            bindNotificationOutbox(handshake.negotiatedCapabilities, session)
            val ownsAfterBinding = synchronized(stateLock) {
                ownsReconnectLocked(generation, ownerToken) && activeSession === session
            }
            if (!ownsAfterBinding) {
                false
            } else {
                emit(ActivityEvent.PeerChanged(isPaired = true, peerName = peerName))
                val ownsAfterPeer = synchronized(stateLock) {
                    ownsReconnectLocked(generation, ownerToken) && activeSession === session
                }
                if (!ownsAfterPeer) {
                    false
                } else {
                    emit(ActivityEvent.ConnectionChanged(ConnectionState.CONNECTED, null))
                    log("session reconnected to $peerName")
                    synchronized(stateLock) {
                        ownsReconnectLocked(generation, ownerToken) && activeSession === session
                    }
                }
            }
        }
    }

    private fun bindPairedSession(
        session: ProtocolSession,
        handshake: SessionHandshake.Result,
        peerName: String,
        generation: Long,
    ): Boolean = synchronized(ownershipTransitionLock) {
        val installed = synchronized(stateLock) {
            if (
                stoppedForTrustChange ||
                pausedByUser ||
                generation != peerLinkGeneration ||
                rememberedEndpoint == null ||
                trustedPeerFingerprint == null
            ) {
                false
            } else {
                activeSession = session
                protocolEpochCounter += 1
                activeProtocolEpoch = ProtocolSessionEpoch.of(protocolEpochCounter)
                activePeerGeneration = generation
                activeCapabilities = handshake.negotiatedCapabilities
                activeTransferSender = TransferSender(
                    ProtocolSessionTransferOutbox(session),
                    handshake.negotiatedCapabilities,
                    checkpointStore = transferCheckpointStore,
                    onProgress = { progress -> handleOutgoingTransferProgress(progress, restored = false) },
                    onRestored = { progress -> handleOutgoingTransferProgress(progress, restored = true) },
                )
                resumeToken = handshake.resumeToken
                lastSessionId = handshake.sessionId
                lastTransferProgress = null
                lastPeerName = peerName
                true
            }
        }
        if (!installed) {
            false
        } else {
            bindNotificationOutbox(handshake.negotiatedCapabilities, session)
            val ownsAfterBinding = synchronized(stateLock) {
                !stoppedForTrustChange &&
                    !pausedByUser &&
                    generation == peerLinkGeneration &&
                    activeSession === session
            }
            if (!ownsAfterBinding) {
                false
            } else {
                emit(ActivityEvent.PeerChanged(isPaired = true, peerName = peerName))
                val ownsAfterPeer = synchronized(stateLock) {
                    !stoppedForTrustChange &&
                        !pausedByUser &&
                        generation == peerLinkGeneration &&
                        activeSession === session
                }
                if (!ownsAfterPeer) {
                    false
                } else {
                    emit(ActivityEvent.ConnectionChanged(ConnectionState.CONNECTED, null))
                    log("session connected to $peerName")
                    synchronized(stateLock) {
                        !stoppedForTrustChange &&
                            !pausedByUser &&
                            generation == peerLinkGeneration &&
                            activeSession === session
                    }
                }
            }
        }
    }

    private fun buildSession(
        socket: SSLSocket,
        sessionId: String,
        generation: Long,
    ): ProtocolSession {
        lateinit var session: ProtocolSession
        val listener = object : ProtocolSession.Listener {
            override fun onEnvelope(envelope: Envelope) {
                val ownerSession = pairedProtocolOwnerSession(session, generation) ?: return
                protocolOwner.handleEnvelope(ownerSession.epoch, envelope)
            }

            override fun onAck(messageId: String) {
                val ownerSession = pairedProtocolOwnerSession(session, generation) ?: return
                runCatching { ownerSession.transferSender?.handleAck(messageId) }
                protocolOwner.handleAck(ownerSession.epoch, messageId)
                HyphenNotificationListenerRuntime.onTransportAcknowledged(messageId)
                HyphenNotificationListenerRuntime.onTransportCapacityAvailable()
            }

            override fun onAckTimeout(messageId: String) {
                val ownerSession = pairedProtocolOwnerSession(session, generation) ?: return
                runCatching { ownerSession.transferSender?.handleAckTimeout(messageId) }
                protocolOwner.handleAckTimeout(ownerSession.epoch, messageId)
                HyphenNotificationListenerRuntime.onTransportAckTimeout(messageId)
                HyphenNotificationListenerRuntime.onTransportCapacityAvailable()
            }

            override fun onLiveness(state: HeartbeatMonitor.State) {
                withPairedSessionOwnership(session, generation) {
                    log("session liveness: $state")
                    emit(
                        ActivityEvent.ConnectionChanged(
                            if (state == HeartbeatMonitor.State.DEGRADED) {
                                ConnectionState.DEGRADED
                            } else {
                                ConnectionState.CONNECTED
                            },
                            null,
                        ),
                    )
                }
            }

            override fun onProtocolError(code: String, detail: String) {
                withPairedSessionOwnership(session, generation) {
                    log("session protocol error: $code $detail")
                }
            }

            override fun onClosed() {
                handleSessionClosed(session)
            }
        }
        session = ProtocolSession(
            socket = socket,
            sessionId = sessionId,
            config = ProtocolSession.Config(startingSeq = 1),
            listener = DiagnosticProtocolSessionListener(diagnosticLogs, listener),
        )
        return session
    }

    private fun handleSessionClosed(session: ProtocolSession) {
        val closed = synchronized(ownershipTransitionLock) {
            val closeAccepted = synchronized(stateLock) {
                if (activeSession !== session) {
                    null
                } else {
                    retireActiveStateLocked() to (!stoppedForTrustChange && !pausedByUser)
                }
            }
            if (closeAccepted == null) {
                null
            } else {
                HyphenNotificationListenerRuntime.clearNotificationOutbox()
                log("Mac session closed")
                emit(ActivityEvent.ConnectionChanged(ConnectionState.SUSPENDED, null))
                closeAccepted
            }
        }
        closed?.first?.let(::recycleRetiredState)
        if (closed?.second == true) {
            startReconnector()
        }
    }

    private fun startReconnector(expectedGeneration: Long? = null): Boolean {
        val endpoint: ParsedEndpoint
        val peerFp: ByteArray
        val seedResumeToken: String?
        val seedSessionId: String?
        val generation: Long
        val oldReconnector: TrustedPeerReconnectOwner?
        synchronized(stateLock) {
            if (stoppedForTrustChange || pausedByUser) return false
            if (expectedGeneration != null && expectedGeneration != peerLinkGeneration) return false
            endpoint = rememberedEndpoint ?: return false
            peerFp = trustedPeerFingerprint ?: return false
            seedResumeToken = resumeToken
            seedSessionId = lastSessionId
            generation = peerLinkGeneration
            oldReconnector = reconnector
            reconnector = null
            reconnectOwnerToken = null
        }
        runCatching { oldReconnector?.stop() }
        val ownerToken = Any()
        val reconnect = runCatching {
            reconnectFactory.create(
                TrustedPeerReconnectRequest(
                    endpoint = endpoint,
                    peerFingerprint = peerFp.copyOf(),
                    initialResumeToken = seedResumeToken,
                    initialPreviousSessionId = seedSessionId,
                    listener = object : SessionReconnector.Listener {
                    override fun onSession(session: ProtocolSession, handshake: SessionHandshake.Result) {
                        val activated = runCatching {
                            beforeReconnectSessionActivation()
                            val peerName = handshake.peerDevice?.deviceName ?: "Mac"
                            bindReconnectedSession(session, handshake, peerName, generation, ownerToken)
                        }.getOrDefault(false)
                        val ownerActivated = activated && activateProtocolOwner(session, generation)
                        if (!ownerActivated) {
                            session.stop()
                        }
                    }

                    override fun onEnvelope(session: ProtocolSession, envelope: Envelope) {
                        val ownerSession = reconnectProtocolOwnerSession(generation, ownerToken, session) ?: return
                        protocolOwner.handleEnvelope(ownerSession.epoch, envelope)
                    }

                    override fun onAck(session: ProtocolSession, messageId: String) {
                        val ownerSession = reconnectProtocolOwnerSession(generation, ownerToken, session) ?: return
                        runCatching { ownerSession.transferSender?.handleAck(messageId) }
                        protocolOwner.handleAck(ownerSession.epoch, messageId)
                        HyphenNotificationListenerRuntime.onTransportAcknowledged(messageId)
                        HyphenNotificationListenerRuntime.onTransportCapacityAvailable()
                    }

                    override fun onAckTimeout(session: ProtocolSession, messageId: String) {
                        val ownerSession = reconnectProtocolOwnerSession(generation, ownerToken, session) ?: return
                        runCatching { ownerSession.transferSender?.handleAckTimeout(messageId) }
                        protocolOwner.handleAckTimeout(ownerSession.epoch, messageId)
                        HyphenNotificationListenerRuntime.onTransportAckTimeout(messageId)
                        HyphenNotificationListenerRuntime.onTransportCapacityAvailable()
                    }

                    override fun onLiveness(session: ProtocolSession, state: HeartbeatMonitor.State) {
                        withReconnectSessionOwnership(generation, ownerToken, session) {
                            log("session liveness: $state")
                            emit(
                                ActivityEvent.ConnectionChanged(
                                    if (state == HeartbeatMonitor.State.DEGRADED) {
                                        ConnectionState.DEGRADED
                                    } else {
                                        ConnectionState.CONNECTED
                                    },
                                    null,
                                ),
                            )
                        }
                    }

                    override fun onProtocolError(session: ProtocolSession, code: String, detail: String) {
                        withReconnectSessionOwnership(generation, ownerToken, session) {
                            log("session protocol error: $code $detail")
                        }
                    }

                    override fun onRetryScheduled(attempt: Int, delaySeconds: Long) {
                        withReconnectOwnership(generation, ownerToken) {
                            log("reconnect scheduled: attempt=$attempt delay=${delaySeconds}s")
                        }
                    }

                    override fun onAttemptFailed(reason: String?) {
                        withReconnectOwnership(generation, ownerToken) {
                            log("reconnect attempt failed: ${reason ?: "unknown"}")
                        }
                    }

                    override fun onSessionLost(session: ProtocolSession) {
                        handleReconnectSessionLost(generation, ownerToken, session)
                    }
                    },
                ),
            )
        }.getOrElse {
            log("reconnect owner could not be created")
            return false
        }
        val started = synchronized(ownershipTransitionLock) {
            val proceed = synchronized(stateLock) {
                if (
                    stoppedForTrustChange ||
                    pausedByUser ||
                    generation != peerLinkGeneration ||
                    reconnector != null ||
                    rememberedEndpoint != endpoint ||
                    trustedPeerFingerprint?.contentEquals(peerFp) != true
                ) {
                    false
                } else {
                    reconnector = reconnect
                    reconnectOwnerToken = ownerToken
                    true
                }
            }
            if (!proceed) {
                false
            } else {
                val didStart = runCatching {
                    reconnect.start()
                    true
                }.getOrDefault(false)
                if (didStart) {
                    log("reconnect owner started for trusted peer")
                    emitConnectionForGeneration(
                        ActivityEvent.ConnectionChanged(ConnectionState.RECONNECTING, null),
                        generation,
                    )
                } else {
                    synchronized(stateLock) {
                        if (reconnectOwnerToken === ownerToken) {
                            reconnector = null
                            reconnectOwnerToken = null
                        }
                    }
                }
                didStart
            }
        }
        if (!started) {
            runCatching { reconnect.stop() }
        }
        return started
    }

    private fun ownsReconnectLocked(generation: Long, ownerToken: Any): Boolean =
        !stoppedForTrustChange &&
            !pausedByUser &&
            generation == peerLinkGeneration &&
            reconnectOwnerToken === ownerToken &&
            reconnector != null

    private fun ownsPeerLinkLocked(
        generation: Long,
        endpoint: ParsedEndpoint,
        peerFingerprint: ByteArray,
    ): Boolean =
        !stoppedForTrustChange &&
            !pausedByUser &&
            generation == peerLinkGeneration &&
            rememberedEndpoint == endpoint &&
            trustedPeerFingerprint?.contentEquals(peerFingerprint) == true

    private inline fun withReconnectOwnership(
        generation: Long,
        ownerToken: Any,
        action: () -> Unit,
    ) {
        synchronized(ownershipTransitionLock) {
            val owns = synchronized(stateLock) {
                ownsReconnectLocked(generation, ownerToken)
            }
            if (owns) {
                action()
            }
        }
    }

    private inline fun withReconnectSessionOwnership(
        generation: Long,
        ownerToken: Any,
        session: ProtocolSession,
        action: () -> Unit,
    ) {
        synchronized(ownershipTransitionLock) {
            val owns = synchronized(stateLock) {
                ownsReconnectLocked(generation, ownerToken) && activeSession === session
            }
            if (owns) action()
        }
    }

    private inline fun withPairedSessionOwnership(
        session: ProtocolSession,
        generation: Long,
        action: () -> Unit,
    ) {
        synchronized(ownershipTransitionLock) {
            val owns = synchronized(stateLock) {
                !stoppedForTrustChange &&
                    !pausedByUser &&
                    generation == peerLinkGeneration &&
                    activeSession === session &&
                    activeSessionReconnectToken == null
            }
            if (owns) {
                action()
            }
        }
    }

    private fun pairedProtocolOwnerSession(
        session: ProtocolSession,
        generation: Long,
    ): ProtocolOwnerSession? = protocolOwnerSessionSnapshot {
        !stoppedForTrustChange &&
            !pausedByUser &&
            generation == peerLinkGeneration &&
            activeSession === session &&
            activeSessionReconnectToken == null
    }

    private fun reconnectProtocolOwnerSession(
        generation: Long,
        ownerToken: Any,
        session: ProtocolSession,
    ): ProtocolOwnerSession? = protocolOwnerSessionSnapshot {
        ownsReconnectLocked(generation, ownerToken) &&
            activeSessionReconnectToken === ownerToken &&
            activeSession === session
    }

    private fun protocolOwnerSessionSnapshot(owns: () -> Boolean): ProtocolOwnerSession? =
        synchronized(ownershipTransitionLock) {
            synchronized(stateLock) {
                if (!owns()) return@synchronized null
                val session = activeSession ?: return@synchronized null
                val epoch = activeProtocolEpoch ?: return@synchronized null
                val fingerprint = trustedPeerFingerprint?.copyOf() ?: return@synchronized null
                val sessionId = lastSessionId ?: return@synchronized null
                ProtocolOwnerSession(
                    epoch = epoch,
                    capabilities = activeCapabilities,
                    transferSender = activeTransferSender,
                    peerFingerprint = fingerprint,
                    sessionId = sessionId,
                    send = { type, capability, requiresAck, payload ->
                        session.send(type, payload, requiresAck, capability)
                    },
                )
            }
        }

    private fun activateProtocolOwner(session: ProtocolSession, generation: Long): Boolean {
        val ownerSession = pairedProtocolOwnerSession(session, generation)
            ?: protocolOwnerSessionSnapshot {
                generation == peerLinkGeneration && activeSession === session
            }
            ?: return false
        return runCatching {
            protocolOwner.activate(ownerSession)
            true
        }.onFailure {
            log("protocol owner activation failed")
        }.getOrDefault(false)
    }

    private fun handleReconnectSessionLost(
        generation: Long,
        ownerToken: Any,
        session: ProtocolSession,
    ) {
        val retired = synchronized(ownershipTransitionLock) {
            val accepted = synchronized(stateLock) {
                if (
                    !ownsReconnectLocked(generation, ownerToken) ||
                    activeSessionReconnectToken !== ownerToken ||
                    activeSession !== session
                ) {
                    null
                } else {
                    retireActiveStateLocked()
                }
            }
            if (accepted != null) {
                HyphenNotificationListenerRuntime.clearNotificationOutbox()
                emit(ActivityEvent.ConnectionChanged(ConnectionState.SUSPENDED, null))
            }
            accepted
        }
        retired?.let(::recycleRetiredState)
    }

    private fun bindNotificationOutbox(
        capabilities: SessionHandshake.NegotiatedCapabilities,
        session: ProtocolSession,
    ) {
        if (NotificationCapabilityGate.shouldBindOutbox(capabilities)) {
            HyphenNotificationListenerRuntime.bindNotificationOutbox(
                outbox = ProtocolSessionNotificationOutbox(session),
                allowReplyActions = NotificationCapabilityGate.allowReplyActions(capabilities),
                requireRemotePrivacyPolicy = capabilities.notificationPrivacyPolicyEnabled(),
            )
        } else {
            HyphenNotificationListenerRuntime.clearNotificationOutbox()
        }
    }

    private fun retireActiveStateLocked(): RetiredActiveState {
        val retired = RetiredActiveState(
            activeSession,
            activeTransferSender,
            activeProtocolEpoch ?: ProtocolSessionEpoch.of(protocolEpochCounter),
            activePeerGeneration,
            trustedPeerFingerprint?.copyOf(),
        )
        activeSession = null
        activeSessionReconnectToken = null
        activeCapabilities = null
        activeTransferSender = null
        activeProtocolEpoch = null
        activePeerGeneration = null
        lastTransferProgress = null
        return retired
    }

    private fun recycleRetiredState(retired: RetiredActiveState) {
        protocolOwner.retire(retired.epoch)
        val retirements = runCatching { retired.transferSender?.recycleSession().orEmpty() }
            .onFailure { log("outbound transfer session cleanup failed") }
            .getOrDefault(emptyList())
        acceptOutboundRetirements(retirements, retired.peerGeneration, retired.peerFingerprint)
    }

    private fun acceptOutboundRetirements(
        retirements: List<OutboundTransferRetirement>,
        peerGeneration: Long?,
        peerFingerprint: ByteArray?,
    ) {
        val retryRequired = retirements.filter(OutboundTransferRetirement::requiresSourceReselection)
        if (retryRequired.isEmpty()) return
        val accepted = synchronized(stateLock) {
            val stillSamePeer = peerGeneration == peerLinkGeneration &&
                peerFingerprint != null &&
                trustedPeerFingerprint?.contentEquals(peerFingerprint) == true
            if (!stillSamePeer) return@synchronized emptyList()
            retryRequired.forEach { pendingOutboundReselect[it.fileId] = it }
            retryRequired
        }
        if (accepted.isEmpty()) return
        val now = System.currentTimeMillis()
        accepted.forEach { item ->
            emit(
                ActivityEvent.TransferSourceReselectionRequired(
                    item.fileId,
                    item.filename,
                    item.sizeBytes,
                    now,
                ),
            )
        }
        onOutboundReselectStateChanged(true)
        listeners.forEach { it.onOutboundReselectRequiredChanged(true) }
        log("outbound transfer source must be reselected")
    }

    private fun log(line: String) {
        listeners.forEach { it.onLog(line) }
    }

    private fun handleOutgoingTransferProgress(progress: TransferProgress, restored: Boolean) {
        updateLastTransferProgress(progress)
        val now = System.currentTimeMillis()
        val event = if (restored) {
            ActivityEvent.TransferResuming(
                progress.fileId, progress.filename, dev.hyphen.android.ui.TransferDirection.OUTGOING,
                progress.completedBytes, progress.totalBytes, progress.completedChunks,
                progress.totalChunks, now,
            )
        } else {
            ActivityEvent.TransferProgressed(
                progress.fileId, progress.filename, dev.hyphen.android.ui.TransferDirection.OUTGOING,
                progress.completedBytes, progress.totalBytes, progress.completedChunks,
                progress.totalChunks, progress.isComplete, now,
            )
        }
        recordTransferEvent(event)
        if (restored) {
            listeners.forEach { it.onTransferRestored(progress, outgoing = true) }
        } else {
            listeners.forEach { it.onTransferProgress(progress, outgoing = true) }
        }
    }

    private fun emit(event: ActivityEvent) {
        recordTransferEvent(event)
        if (event is ActivityEvent.ConnectionChanged) {
            val generation = synchronized(stateLock) { peerLinkGeneration }
            emitConnectionForGeneration(event, generation)
        } else {
            listeners.forEach { it.onActivityEvent(event) }
        }
    }

    /** Rejects a delayed connection result once another peer generation owns UI state. */
    private fun emitConnectionForGeneration(
        event: ActivityEvent.ConnectionChanged,
        expectedGeneration: Long,
    ): Boolean {
        var deliveries = emptyList<Pair<ConnectionDeliverySlot, ConnectionDelivery>>()
        val accepted = synchronized(ownershipTransitionLock) {
            val isCurrent = synchronized(stateLock) {
                expectedGeneration == peerLinkGeneration
            }
            if (!isCurrent) {
                false
            } else {
                synchronized(connectionDeliveryLock) {
                    val sequence = ++connectionEventSequence
                    latestConnectionEvent = ConnectionEventSnapshot(expectedGeneration, sequence, event)
                    val delivery = ConnectionDelivery(expectedGeneration, sequence, event)
                    deliveries = listeners.mapNotNull { listener ->
                        connectionDeliverySlots[listener]?.let { it to delivery }
                    }
                }
                true
            }
        }
        if (accepted) {
            deliveries.forEach { (slot, delivery) ->
                enqueueConnectionDelivery(slot, delivery)
            }
        }
        return accepted
    }

    /**
     * Drains one observer outside the connection-delivery lock. If a callback
     * re-enters a transition while another thread publishes, the publisher
     * only replaces [ConnectionDeliverySlot.pending] and never waits for
     * callback completion.
     */
    private fun enqueueConnectionDelivery(
        slot: ConnectionDeliverySlot,
        delivery: ConnectionDelivery,
    ) {
        val shouldDrain = synchronized(slot) {
            if (!slot.active) return@synchronized false
            val newestKnown = maxOf(
                slot.lastDeliveredSequence,
                slot.inFlightSequence,
                slot.pending?.sequence ?: 0L,
            )
            if (delivery.sequence <= newestKnown) {
                false
            } else {
                slot.pending = delivery
                if (slot.delivering) {
                    false
                } else {
                    slot.delivering = true
                    true
                }
            }
        }
        if (!shouldDrain) return

        while (true) {
            val next = synchronized(slot) {
                if (!slot.active) {
                    slot.pending = null
                    slot.inFlightSequence = 0L
                    slot.delivering = false
                    return@synchronized null
                }
                val pending = slot.pending
                if (pending == null) {
                    slot.delivering = false
                    null
                } else {
                    slot.pending = null
                    slot.inFlightSequence = pending.sequence
                    pending
                }
            } ?: return

            val mayDeliver = synchronized(slot) {
                slot.active && slot.inFlightSequence == next.sequence
            }
            val connectionHandled = if (!mayDeliver) {
                false
            } else if (!isCurrentPeerGeneration(next.peerGeneration)) {
                // Generations only increase, so a stale delivery can never
                // become current again and is safe to retire permanently.
                true
            } else {
                if (next.includesPeerReplay) {
                    runCatching {
                        slot.listener.onActivityEvent(
                            ActivityEvent.PeerChanged(
                                isPaired = true,
                                peerName = next.replayPeerName ?: "Mac",
                            ),
                        )
                    }
                }
                runCatching { slot.listener.onActivityEvent(next.event) }.isSuccess
            }
            val continueDraining = synchronized(slot) {
                if (slot.inFlightSequence == next.sequence) {
                    slot.inFlightSequence = 0L
                    if (connectionHandled) {
                        slot.lastDeliveredSequence = maxOf(
                            slot.lastDeliveredSequence,
                            next.sequence,
                        )
                    }
                }
                when {
                    !slot.active -> {
                        slot.pending = null
                        slot.delivering = false
                        false
                    }

                    !connectionHandled && slot.pending == null -> {
                        // Do not spin on a faulty observer. A later explicit
                        // replay may retry this same sequence.
                        slot.delivering = false
                        false
                    }

                    else -> true
                }
            }
            if (!continueDraining) return
        }
    }

    private fun isCurrentPeerGeneration(expectedGeneration: Long): Boolean =
        synchronized(stateLock) { expectedGeneration == peerLinkGeneration }

    private fun isReplayDeliveryCurrent(
        slot: ConnectionDeliverySlot,
        expectedGeneration: Long,
    ): Boolean =
        synchronized(slot) { slot.active } && isCurrentPeerGeneration(expectedGeneration)

    private fun recordTransferEvent(event: ActivityEvent) {
        transferEventFileId(event)?.let { fileId ->
            synchronized(stateLock) {
                if (trustedPeerFingerprint == null) return@synchronized
                if (recentTransferEventsGeneration != peerLinkGeneration) {
                    recentTransferEvents.clear()
                    recentTransferEventsGeneration = peerLinkGeneration
                }
                recentTransferEvents.remove(fileId)
                recentTransferEvents[fileId] = event
                while (recentTransferEvents.size > MAX_RECENT_TRANSFER_EVENTS) {
                    recentTransferEvents.remove(recentTransferEvents.keys.first())
                }
            }
        }
    }

    private fun transferEventFileId(event: ActivityEvent): String? = when (event) {
        is ActivityEvent.TransferProgressed -> event.fileId
        is ActivityEvent.TransferResuming -> event.fileId
        is ActivityEvent.TransferCompleted -> event.fileId
        is ActivityEvent.TransferCancelled -> event.fileId
        is ActivityEvent.TransferDeliveryFailed -> event.fileId
        is ActivityEvent.TransferSourceReselectionRequired -> event.fileId
        else -> null
    }

    private fun recordPeerLinkFailure(code: String, operation: String) {
        runCatching {
            diagnosticLogs.recordFailure(
                code = code,
                component = "connection-supervisor",
                operation = operation,
            )
        }
    }

    companion object {
        @Volatile
        private var instance: ConnectionSupervisor? = null

        fun getInstance(context: Context): ConnectionSupervisor =
            instance ?: synchronized(this) {
                instance ?: run {
                    val app = context.applicationContext
                    initializeNotificationPrivacyFloor(
                        load = { NotificationPrivacyPreferences.localFloor(app) },
                        apply = HyphenNotificationListenerRuntime::setNotificationPrivacyMode,
                    )
                    val checkpoints = TransferCheckpointStore(TransferCheckpointStore.defaultRoot(app))
                    ConnectionSupervisor(
                        diagnosticLogs = DiagnosticsRuntime.forSessionSupervisor(),
                        peerLinkLifecycle = AndroidPeerLinkStores.openDefaultLifecycle(app),
                        onOutboundReselectStateChanged = { required ->
                            if (required) {
                                CompanionConnectionServiceController.requireTransferSourceReselection(app)
                            } else {
                                CompanionConnectionPreferences.setTransferRetryRequired(app, false)
                            }
                        },
                        protocolOwnerFactory = { events ->
                            createAndroidProtocolEnvelopeOwner(app, checkpoints, events)
                        },
                    ).also {
                        it.configureTransferCheckpoints(checkpoints)
                        instance = it
                    }
                }
            }

        private const val MAX_RECENT_TRANSFER_EVENTS = 200
    }
}

internal fun initializeNotificationPrivacyFloor(
    load: () -> dev.hyphen.android.notifications.NotificationPrivacyMode,
    apply: (dev.hyphen.android.notifications.NotificationPrivacyMode) -> Unit,
) {
    apply(load())
}
