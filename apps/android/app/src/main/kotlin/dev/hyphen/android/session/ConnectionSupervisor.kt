package dev.hyphen.android.session

import android.content.Context
import android.os.Build
import dev.hyphen.android.diagnostics.DiagnosticProtocolSessionListener
import dev.hyphen.android.diagnostics.DiagnosticsRuntime
import dev.hyphen.android.diagnostics.LocalStructuredLogStore
import dev.hyphen.android.notifications.HyphenNotificationListenerRuntime
import dev.hyphen.android.notifications.NotificationCapabilityGate
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
import dev.hyphen.android.transfer.TransferReceiver
import dev.hyphen.android.transfer.TransferSender
import dev.hyphen.android.trust.TrustedPeer
import dev.hyphen.android.ui.ActivityEvent
import dev.hyphen.android.ui.ConnectionState
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
    )

    interface Listener {
        fun onLog(line: String) {}
        fun onActivityEvent(event: ActivityEvent) {}
        fun onEnvelope(envelope: Envelope, state: ActiveState) {}
        fun onTransferProgress(progress: TransferProgress, outgoing: Boolean) {}
    }

    private val stateLock = Any()
    private val ownershipTransitionLock = Any()
    private val peerLinkStorageLock = Any()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private var activeSession: ProtocolSession? = null
    private var activeCapabilities: SessionHandshake.NegotiatedCapabilities? = null
    private var activeTransferSender: TransferSender? = null
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

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    /**
     * Replays the current connection state to a freshly attached listener so a
     * recreated Activity reflects a live session instead of showing disconnected.
     */
    fun replayStateTo(listener: Listener) {
        val peerName: String?
        val state: ConnectionState
        synchronized(stateLock) {
            state = when {
                activeSession != null -> ConnectionState.CONNECTED
                reconnector != null && rememberedEndpoint != null -> ConnectionState.RECONNECTING
                pausedByUser && rememberedEndpoint != null -> ConnectionState.SUSPENDED
                else -> return
            }
            peerName = lastPeerName
        }
        listener.onActivityEvent(ActivityEvent.PeerChanged(isPaired = true, peerName = peerName ?: "Mac"))
        listener.onActivityEvent(ActivityEvent.ConnectionChanged(state, null))
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

    fun bindTransferPersistence(receiver: TransferReceiver): List<dev.hyphen.android.transfer.TransferCompleted> {
        val peerFingerprint: ByteArray
        val sessionId: String
        val sender: TransferSender?
        synchronized(stateLock) {
            peerFingerprint = trustedPeerFingerprint ?: return emptyList()
            sessionId = lastSessionId ?: return emptyList()
            sender = activeTransferSender
        }
        val pendingDeliveries = receiver.bindSession(peerFingerprint, sessionId)
        sender?.bindSession(peerFingerprint, sessionId)
        return pendingDeliveries
    }

    fun invalidateTransferCheckpoints(
        peerFingerprint: ByteArray,
        receiver: TransferReceiver,
    ) {
        receiver.invalidatePeerCheckpoints(peerFingerprint)
        val (sender, store) = synchronized(stateLock) {
            activeTransferSender to transferCheckpointStore
        }
        sender?.invalidatePeerCheckpoints(peerFingerprint)
        store?.invalidatePeer(peerFingerprint)
    }

    fun invalidateAllTransferCheckpoints(receiver: TransferReceiver) {
        receiver.invalidateAllCheckpoints()
        val store = synchronized(stateLock) { transferCheckpointStore }
        store?.invalidateAll()
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
        )
        var persistenceFailure: Throwable? = null
        val switched = synchronized(ownershipTransitionLock) {
            val transition = synchronized(stateLock) {
                peerLinkGeneration += 1
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
                PeerSwitch(peerLinkGeneration, previousReconnector, previous)
            }
            HyphenNotificationListenerRuntime.clearNotificationOutbox()
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
        recycleTransferSender(switched.previous.transferSender)
        switched.previous.session?.stop()
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
        recycleTransferSender(ownership.previous.transferSender)
        ownership.previous.session?.stop()
        val session = buildSession(socket, handshake.sessionId, ownership.generation)
        val activated = runCatching {
            beforePairedSessionActivation()
            bindPairedSession(session, handshake, peerName, ownership.generation)
        }.getOrDefault(false)
        if (!activated) {
            session.stop()
        }
        return activated
    }

    fun stopAfterTrustChange() {
        data class TrustStop(
            val reconnector: TrustedPeerReconnectOwner?,
            val previous: RetiredActiveState,
            val peerFingerprint: ByteArray?,
            val checkpointStore: TransferCheckpointStore?,
        )
        val stop = synchronized(ownershipTransitionLock) {
            val captured = synchronized(stateLock) {
                peerLinkGeneration += 1
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
                TrustStop(oldReconnector, previous, peerFingerprint, transferCheckpointStore)
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
            HyphenNotificationListenerRuntime.clearNotificationOutbox()
            emit(ActivityEvent.ConnectionChanged(ConnectionState.SUSPENDED, null))
            log("session stopped after trust change")
            captured.peerFingerprint?.let { captured.checkpointStore?.invalidatePeer(it) }
            captured
        }
        // Stop potentially blocking transport owners after publishing the
        // generation change. Their callbacks re-enter through the ownership
        // gate and are rejected, while no transport monitor is held here.
        stop.reconnector?.stop()
        recycleTransferSender(stop.previous.transferSender)
        stop.previous.session?.stop()
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
        recycleTransferSender(paused.previous.transferSender)
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
                activeSessionReconnectToken = ownerToken
                activeCapabilities = handshake.negotiatedCapabilities
                activeTransferSender = TransferSender(
                    ProtocolSessionTransferOutbox(session),
                    handshake.negotiatedCapabilities,
                    checkpointStore = transferCheckpointStore,
                    onProgress = { progress ->
                        updateLastTransferProgress(progress)
                        listeners.forEach { it.onTransferProgress(progress, outgoing = true) }
                    },
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
                activeCapabilities = handshake.negotiatedCapabilities
                activeTransferSender = TransferSender(
                    ProtocolSessionTransferOutbox(session),
                    handshake.negotiatedCapabilities,
                    checkpointStore = transferCheckpointStore,
                    onProgress = { progress ->
                        updateLastTransferProgress(progress)
                        listeners.forEach { it.onTransferProgress(progress, outgoing = true) }
                    },
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
            session.start()
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
                withPairedSessionOwnership(session, generation) {
                    dispatchEnvelope(envelope)
                }
            }

            override fun onAck(messageId: String) {
                withPairedSessionOwnership(session, generation) {
                    runCatching { activeTransferSender?.handleAck(messageId) }
                }
            }

            override fun onAckTimeout(messageId: String) {
                withPairedSessionOwnership(session, generation) {
                    runCatching { activeTransferSender?.handleAckTimeout(messageId) }
                }
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
        recycleTransferSender(closed?.first?.transferSender)
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
                        if (!activated) {
                            session.stop()
                        }
                    }

                    override fun onEnvelope(envelope: Envelope) {
                        withReconnectOwnership(generation, ownerToken) {
                            dispatchEnvelope(envelope)
                        }
                    }

                    override fun onAck(messageId: String) {
                        withReconnectOwnership(generation, ownerToken) {
                            runCatching { activeTransferSender?.handleAck(messageId) }
                        }
                    }

                    override fun onAckTimeout(messageId: String) {
                        withReconnectOwnership(generation, ownerToken) {
                            runCatching { activeTransferSender?.handleAckTimeout(messageId) }
                        }
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
        recycleTransferSender(retired?.transferSender)
    }

    private fun dispatchEnvelope(envelope: Envelope) {
        val state = activeState()
        listeners.forEach { it.onEnvelope(envelope, state) }
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
        val retired = RetiredActiveState(activeSession, activeTransferSender)
        activeSession = null
        activeSessionReconnectToken = null
        activeCapabilities = null
        activeTransferSender = null
        lastTransferProgress = null
        return retired
    }

    private fun recycleTransferSender(sender: TransferSender?) {
        sender ?: return
        runCatching { sender.recycleSession() }
            .onFailure { log("outbound transfer session cleanup failed") }
    }

    private fun log(line: String) {
        listeners.forEach { it.onLog(line) }
    }

    private fun emit(event: ActivityEvent) {
        listeners.forEach { it.onActivityEvent(event) }
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
                instance ?: ConnectionSupervisor(
                    diagnosticLogs = DiagnosticsRuntime.forSessionSupervisor(),
                    peerLinkLifecycle = AndroidPeerLinkStores.openDefaultLifecycle(context.applicationContext),
                ).also { instance = it }
            }
    }
}
