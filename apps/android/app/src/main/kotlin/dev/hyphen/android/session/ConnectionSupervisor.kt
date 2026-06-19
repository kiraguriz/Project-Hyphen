package dev.hyphen.android.session

import android.content.Context
import android.os.Build
import dev.hyphen.android.diagnostics.DiagnosticProtocolSessionListener
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
import dev.hyphen.android.transfer.TransferProgress
import dev.hyphen.android.transfer.TransferSender
import dev.hyphen.android.ui.ActivityEvent
import dev.hyphen.android.ui.ConnectionState
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLSocket

/**
 * Process-level owner for the paired transport session (HYP-M2-013 product
 * wiring). Activity destroy/recreate does not stop an active session; trust
 * revoke/reset calls [stopAfterTrustChange].
 */
class ConnectionSupervisor private constructor(
    private val appContext: Context,
    private val diagnosticLogs: LocalStructuredLogStore,
) {
    data class ActiveState(
        val session: ProtocolSession?,
        val capabilities: SessionHandshake.NegotiatedCapabilities?,
        val transferSender: TransferSender?,
        val lastTransferProgress: TransferProgress?,
    )

    interface Listener {
        fun onLog(line: String) {}
        fun onActivityEvent(event: ActivityEvent) {}
        fun onEnvelope(envelope: Envelope, state: ActiveState) {}
        fun onTransferProgress(progress: TransferProgress, outgoing: Boolean) {}
    }

    private val stateLock = Any()
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
    private var reconnector: SessionReconnector? = null
    private var stoppedForTrustChange = false
    private var sessionOwnedByReconnector = false

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    /**
     * Replays the current connection state to a freshly attached listener so a
     * recreated Activity reflects a live session instead of showing disconnected.
     */
    fun replayStateTo(listener: Listener) {
        val peerName: String?
        synchronized(stateLock) {
            if (activeSession == null) return
            peerName = lastPeerName
        }
        listener.onActivityEvent(ActivityEvent.PeerChanged(isPaired = true, peerName = peerName ?: "Mac"))
        listener.onActivityEvent(ActivityEvent.ConnectionChanged(ConnectionState.CONNECTED, null))
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

    fun updateLastTransferProgress(progress: TransferProgress?) {
        synchronized(stateLock) { lastTransferProgress = progress }
    }

    fun rememberEndpoint(endpoint: ParsedEndpoint, peerFingerprint: ByteArray) {
        synchronized(stateLock) {
            rememberedEndpoint = endpoint
            trustedPeerFingerprint = peerFingerprint.copyOf()
            stoppedForTrustChange = false
        }
    }

    fun adoptPairedSession(
        socket: SSLSocket,
        handshake: SessionHandshake.Result,
        peerName: String,
    ) {
        val oldReconnector: SessionReconnector?
        val previous: ProtocolSession?
        synchronized(stateLock) {
            resumeToken = handshake.resumeToken
            lastSessionId = handshake.sessionId
            sessionOwnedByReconnector = false
            oldReconnector = reconnector
            reconnector = null
            previous = clearActiveStateLocked()
        }
        oldReconnector?.stop()
        previous?.stop()
        val session = buildSession(socket, handshake.sessionId, ownedByReconnector = false)
        bindSession(session, handshake, peerName)
        session.start()
    }

    fun stopAfterTrustChange() {
        val oldReconnector: SessionReconnector?
        val current: ProtocolSession?
        synchronized(stateLock) {
            stoppedForTrustChange = true
            rememberedEndpoint = null
            trustedPeerFingerprint = null
            resumeToken = null
            lastSessionId = null
            lastPeerName = null
            sessionOwnedByReconnector = false
            oldReconnector = reconnector
            reconnector = null
            current = clearActiveStateLocked()
        }
        oldReconnector?.stop()
        current?.stop()
        HyphenNotificationListenerRuntime.clearNotificationOutbox()
        emit(ActivityEvent.ConnectionChanged(ConnectionState.SUSPENDED, null))
        log("session stopped after trust change")
    }

    private fun bindReconnectedSession(
        session: ProtocolSession,
        handshake: SessionHandshake.Result,
        peerName: String,
    ) {
        synchronized(stateLock) {
            resumeToken = handshake.resumeToken
            lastSessionId = handshake.sessionId
            activeSession = session
            activeCapabilities = handshake.negotiatedCapabilities
            activeTransferSender = TransferSender(
                ProtocolSessionTransferOutbox(session),
                handshake.negotiatedCapabilities,
                onProgress = { progress ->
                    updateLastTransferProgress(progress)
                    listeners.forEach { it.onTransferProgress(progress, outgoing = true) }
                },
            )
            lastTransferProgress = null
            lastPeerName = peerName
        }
        bindNotificationOutbox(handshake.negotiatedCapabilities, session)
        emit(ActivityEvent.PeerChanged(isPaired = true, peerName = peerName))
        emit(ActivityEvent.ConnectionChanged(ConnectionState.CONNECTED, null))
        log("session reconnected to $peerName")
    }

    private fun bindSession(
        session: ProtocolSession,
        handshake: SessionHandshake.Result,
        peerName: String,
    ) {
        synchronized(stateLock) {
            resumeToken = handshake.resumeToken
            lastSessionId = handshake.sessionId
        }
        val sender = TransferSender(
            ProtocolSessionTransferOutbox(session),
            handshake.negotiatedCapabilities,
            onProgress = { progress ->
                updateLastTransferProgress(progress)
                listeners.forEach { it.onTransferProgress(progress, outgoing = true) }
            },
        )
        synchronized(stateLock) {
            activeSession = session
            activeCapabilities = handshake.negotiatedCapabilities
            activeTransferSender = sender
            lastTransferProgress = null
            lastPeerName = peerName
        }
        bindNotificationOutbox(handshake.negotiatedCapabilities, session)
        emit(ActivityEvent.PeerChanged(isPaired = true, peerName = peerName))
        emit(ActivityEvent.ConnectionChanged(ConnectionState.CONNECTED, null))
        log("session connected to $peerName")
    }

    private fun buildSession(
        socket: SSLSocket,
        sessionId: String,
        ownedByReconnector: Boolean,
    ): ProtocolSession {
        lateinit var session: ProtocolSession
        val listener = object : ProtocolSession.Listener {
            override fun onEnvelope(envelope: Envelope) = dispatchEnvelope(envelope)

            override fun onAck(messageId: String) {
                runCatching { activeState().transferSender?.handleAck(messageId) }
            }

            override fun onLiveness(state: HeartbeatMonitor.State) {
                log("session liveness: $state")
            }

            override fun onProtocolError(code: String, detail: String) {
                log("session protocol error: $code $detail")
            }

            override fun onClosed() {
                handleSessionClosed(session, ownedByReconnector)
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

    private fun handleSessionClosed(session: ProtocolSession, ownedByReconnector: Boolean) {
        synchronized(stateLock) {
            if (activeSession !== session) return
            clearActiveStateLocked()
        }
        HyphenNotificationListenerRuntime.clearNotificationOutbox()
        log("Mac session closed")
        emit(ActivityEvent.ConnectionChanged(ConnectionState.SUSPENDED, null))
        if (!ownedByReconnector && !stoppedForTrustChange) {
            startReconnector()
        }
    }

    private fun startReconnector() {
        val endpoint: ParsedEndpoint
        val peerFp: ByteArray
        val seedResumeToken: String?
        val seedSessionId: String?
        val oldReconnector: SessionReconnector?
        synchronized(stateLock) {
            if (stoppedForTrustChange) return
            endpoint = rememberedEndpoint ?: return
            peerFp = trustedPeerFingerprint ?: return
            seedResumeToken = resumeToken
            seedSessionId = lastSessionId
            oldReconnector = reconnector
            reconnector = null
        }
        oldReconnector?.stop()
        val device = SessionHandshake.DeviceInfo("android", "0.0.1", Build.MODEL)
        val reconnect = SessionReconnector(
            dial = {
                val identity = AndroidKeystoreTlsIdentity.getOrCreate()
                TlsClient.connect(
                    host = endpoint.host,
                    port = endpoint.port,
                    identity = identity,
                    isTrusted = { it.contentEquals(peerFp) },
                )
            },
            device = device,
            initialResumeToken = seedResumeToken,
            initialPreviousSessionId = seedSessionId,
            listener = object : SessionReconnector.Listener {
                override fun onSession(session: ProtocolSession, handshake: SessionHandshake.Result) {
                    synchronized(stateLock) { sessionOwnedByReconnector = true }
                    val peerName = handshake.peerDevice?.deviceName ?: "Mac"
                    bindReconnectedSession(session, handshake, peerName)
                }

                override fun onEnvelope(envelope: Envelope) = dispatchEnvelope(envelope)

                override fun onAck(messageId: String) {
                    runCatching { activeState().transferSender?.handleAck(messageId) }
                }

                override fun onRetryScheduled(attempt: Int, delaySeconds: Long) {
                    log("reconnect scheduled: attempt=$attempt delay=${delaySeconds}s")
                }

                override fun onAttemptFailed(reason: String?) {
                    log("reconnect attempt failed: ${reason ?: "unknown"}")
                }

                override fun onSessionLost() {
                    synchronized(stateLock) {
                        if (sessionOwnedByReconnector) {
                            clearActiveStateLocked()
                            sessionOwnedByReconnector = false
                        }
                    }
                    HyphenNotificationListenerRuntime.clearNotificationOutbox()
                    emit(ActivityEvent.ConnectionChanged(ConnectionState.SUSPENDED, null))
                }
            },
        )
        val proceed = synchronized(stateLock) {
            if (stoppedForTrustChange || reconnector != null) {
                false
            } else {
                reconnector = reconnect
                true
            }
        }
        if (!proceed) {
            reconnect.stop()
            return
        }
        reconnect.start()
        log("reconnect owner started for ${endpoint.host}:${endpoint.port}")
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
            )
        } else {
            HyphenNotificationListenerRuntime.clearNotificationOutbox()
        }
    }

    private fun clearActiveStateLocked(): ProtocolSession? {
        val current = activeSession
        activeSession = null
        activeCapabilities = null
        activeTransferSender = null
        lastTransferProgress = null
        return current
    }

    private fun log(line: String) {
        listeners.forEach { it.onLog(line) }
    }

    private fun emit(event: ActivityEvent) {
        listeners.forEach { it.onActivityEvent(event) }
    }

    companion object {
        @Volatile
        private var instance: ConnectionSupervisor? = null

        fun getInstance(context: Context): ConnectionSupervisor =
            instance ?: synchronized(this) {
                instance ?: ConnectionSupervisor(
                    appContext = context.applicationContext,
                    diagnosticLogs = LocalStructuredLogStore(),
                ).also { instance = it }
            }
    }
}
