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
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLSocket

internal interface TrustedPeerReconnectOwner {
    fun start()
    fun stop()
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

    private val stateLock = Any()
    private val ownershipTransitionLock = Any()
    private val peerLinkStorageLock = Any()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private var activeSession: ProtocolSession? = null
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
        listeners.add(listener)
    }

    /**
     * Replays the current connection state to a freshly attached listener so a
     * recreated Activity reflects a live session instead of showing disconnected.
     */
    fun replayStateTo(listener: Listener) {
        val peerName: String?
        val state: ConnectionState?
        val transferEvents: List<ActivityEvent>
        val reselectRequired: Boolean
        synchronized(stateLock) {
            state = when {
                activeSession != null -> ConnectionState.CONNECTED
                reconnector != null && rememberedEndpoint != null -> ConnectionState.RECONNECTING
                pausedByUser && rememberedEndpoint != null -> ConnectionState.SUSPENDED
                else -> null
            }
            peerName = lastPeerName
            transferEvents = if (
                trustedPeerFingerprint != null && recentTransferEventsGeneration == peerLinkGeneration
            ) {
                recentTransferEvents.values.toList()
            } else {
                emptyList()
            }
            reselectRequired = pendingOutboundReselect.isNotEmpty()
        }
        if (state != null) {
            listener.onActivityEvent(ActivityEvent.PeerChanged(isPaired = true, peerName = peerName ?: "Mac"))
            listener.onActivityEvent(ActivityEvent.ConnectionChanged(state, null))
        }
        transferEvents.forEach(listener::onActivityEvent)
        listener.onOutboundReselectRequiredChanged(reselectRequired)
        protocolOwner.pendingTextConfirmations().forEach(listener::onTextConfirmation)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
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

        val accepted = synchronized(ownershipTransitionLock) {
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
                false
            } else {
                emit(ActivityEvent.PeerChanged(isPaired = true, peerName = restored.peer.displayName.ifBlank { "Mac" }))
                val ownsAfterPeer = synchronized(stateLock) {
                    ownsPeerLinkLocked(adoptedGeneration, restored.endpoint, restored.peer.spkiFingerprint)
                }
                if (!ownsAfterPeer) {
                    false
                } else {
                    emit(ActivityEvent.ConnectionChanged(ConnectionState.RECONNECTING, null))
                    synchronized(stateLock) {
                        ownsPeerLinkLocked(adoptedGeneration, restored.endpoint, restored.peer.spkiFingerprint)
                    }
                }
            }
        }
        if (!accepted) return false

        (reconnectAfterRestore ?: ::startReconnector).invoke()
        return true
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
        data class UserPause(
            val reconnector: TrustedPeerReconnectOwner?,
            val previous: RetiredActiveState,
        )
        val paused = synchronized(ownershipTransitionLock) {
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
            if (!alreadyOwned) {
                emit(ActivityEvent.ConnectionChanged(ConnectionState.RECONNECTING, null))
                startReconnector()
            }
            return true
        }
        return restorePersistedLink()
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

    private fun startReconnector() {
        val endpoint: ParsedEndpoint
        val peerFp: ByteArray
        val seedResumeToken: String?
        val seedSessionId: String?
        val generation: Long
        val oldReconnector: TrustedPeerReconnectOwner?
        synchronized(stateLock) {
            if (stoppedForTrustChange || pausedByUser) return
            endpoint = rememberedEndpoint ?: return
            peerFp = trustedPeerFingerprint ?: return
            seedResumeToken = resumeToken
            seedSessionId = lastSessionId
            generation = peerLinkGeneration
            oldReconnector = reconnector
            reconnector = null
            reconnectOwnerToken = null
        }
        oldReconnector?.stop()
        val ownerToken = Any()
        val reconnect = reconnectFactory.create(
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

                    override fun onEnvelope(envelope: Envelope) {
                        val ownerSession = reconnectProtocolOwnerSession(generation, ownerToken) ?: return
                        protocolOwner.handleEnvelope(ownerSession.epoch, envelope)
                    }

                    override fun onAck(messageId: String) {
                        val ownerSession = reconnectProtocolOwnerSession(generation, ownerToken) ?: return
                        runCatching { ownerSession.transferSender?.handleAck(messageId) }
                        protocolOwner.handleAck(ownerSession.epoch, messageId)
                        HyphenNotificationListenerRuntime.onTransportAcknowledged(messageId)
                        HyphenNotificationListenerRuntime.onTransportCapacityAvailable()
                    }

                    override fun onAckTimeout(messageId: String) {
                        val ownerSession = reconnectProtocolOwnerSession(generation, ownerToken) ?: return
                        runCatching { ownerSession.transferSender?.handleAckTimeout(messageId) }
                        protocolOwner.handleAckTimeout(ownerSession.epoch, messageId)
                        HyphenNotificationListenerRuntime.onTransportAckTimeout(messageId)
                        HyphenNotificationListenerRuntime.onTransportCapacityAvailable()
                    }

                    override fun onLiveness(state: HeartbeatMonitor.State) {
                        withReconnectOwnership(generation, ownerToken) {
                            log("session liveness: $state")
                        }
                    }

                    override fun onProtocolError(code: String, detail: String) {
                        withReconnectOwnership(generation, ownerToken) {
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

                    override fun onSessionLost() {
                        handleReconnectSessionLost(generation, ownerToken)
                    }
                },
            ),
        )
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
                    emit(ActivityEvent.ConnectionChanged(ConnectionState.RECONNECTING, null))
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
            reconnect.stop()
        }
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
    ): ProtocolOwnerSession? = protocolOwnerSessionSnapshot {
        ownsReconnectLocked(generation, ownerToken) && activeSessionReconnectToken === ownerToken
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

    private fun handleReconnectSessionLost(generation: Long, ownerToken: Any) {
        val retired = synchronized(ownershipTransitionLock) {
            val accepted = synchronized(stateLock) {
                if (
                    !ownsReconnectLocked(generation, ownerToken) ||
                    activeSessionReconnectToken !== ownerToken
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
        listeners.forEach { it.onActivityEvent(event) }
    }

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
