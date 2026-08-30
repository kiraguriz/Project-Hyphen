package dev.hyphen.android.session

import dev.hyphen.android.diagnostics.LocalStructuredLogStore
import dev.hyphen.android.pairing.ParsedEndpoint
import dev.hyphen.android.transport.ProtocolSession
import dev.hyphen.android.transport.Json
import dev.hyphen.android.transport.SessionHandshake
import dev.hyphen.android.transport.SessionReconnector
import dev.hyphen.android.transfer.StreamTransferByteSource
import dev.hyphen.android.transfer.TransferOutbox
import dev.hyphen.android.transfer.TransferManifest
import dev.hyphen.android.transfer.TransferReceiver
import dev.hyphen.android.transfer.FileTransferStorage
import dev.hyphen.android.notifications.NotificationPrivacyMode
import dev.hyphen.android.transport.Envelope
import dev.hyphen.android.transfer.TransferSender
import dev.hyphen.android.transfer.OutboundTransferRetirement
import dev.hyphen.android.trust.TrustedPeer
import dev.hyphen.android.ui.ActivityEvent
import dev.hyphen.android.ui.ConnectionState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

private class FakePeerLinkLifecycle(
    var restored: RestoredPeerLink? = null,
    var restoreFailure: RuntimeException? = null,
) : PeerLinkLifecycle {
    var invalidations = 0
    val remembered = mutableListOf<Pair<ParsedEndpoint, ByteArray>>()
    val pairingCommits = mutableListOf<Pair<ParsedEndpoint, TrustedPeer>>()
    var pairingCommitFailure: RuntimeException? = null

    override fun remember(endpoint: ParsedEndpoint, peerFingerprint: ByteArray) {
        remembered += endpoint to peerFingerprint.copyOf()
    }

    override fun commitPairing(endpoint: ParsedEndpoint, peer: TrustedPeer) {
        pairingCommitFailure?.let { throw it }
        pairingCommits += endpoint to peer
    }

    override fun restoreTrusted(): RestoredPeerLink? {
        restoreFailure?.let { throw it }
        return restored
    }

    override fun invalidate() {
        invalidations++
    }
}

private class FakeTrustedPeerReconnectOwner : TrustedPeerReconnectOwner {
    var starts = 0
    var stops = 0

    override fun start() {
        starts++
    }

    override fun stop() {
        stops++
    }
}

private class BlockingRememberPeerLinkLifecycle : PeerLinkLifecycle {
    val rememberEntered = CountDownLatch(1)
    val releaseRemember = CountDownLatch(1)
    val stored = AtomicBoolean(false)
    val invalidations = AtomicInteger()

    override fun remember(endpoint: ParsedEndpoint, peerFingerprint: ByteArray) {
        rememberEntered.countDown()
        check(releaseRemember.await(5, TimeUnit.SECONDS))
        stored.set(true)
    }

    override fun commitPairing(endpoint: ParsedEndpoint, peer: TrustedPeer) {
        remember(endpoint, peer.spkiFingerprint)
    }

    override fun restoreTrusted(): RestoredPeerLink? = null

    override fun invalidate() {
        stored.set(false)
        invalidations.incrementAndGet()
    }
}

class ConnectionSupervisorColdStartTest {
    @get:Rule
    val temp = TemporaryFolder()
    private val fingerprint = ByteArray(32) { 0x31 }
    private val restored = RestoredPeerLink(
        endpoint = ParsedEndpoint.Manual("192.168.1.31", 43123),
        peer = TrustedPeer(fingerprint, "Studio Mac", 123L),
    )

    @Test
    fun `raw envelope has one process owner and ordinary observers receive no raw callback`() {
        val handled = AtomicInteger()
        val epoch = ProtocolSessionEpoch.of(1)
        val supervisor = ConnectionSupervisor(
            diagnosticLogs = LocalStructuredLogStore(),
            peerLinkLifecycle = FakePeerLinkLifecycle(),
            protocolOwnerFactory = {
                object : ProtocolEnvelopeOwner {
                    override fun handleEnvelope(epoch: ProtocolSessionEpoch, envelope: Envelope) {
                        handled.incrementAndGet()
                    }
                }
            },
        )
        repeat(2) { supervisor.addListener(object : ConnectionSupervisor.Listener {}) }

        supervisor.dispatchProtocolEnvelopeForTest(
            epoch,
            Envelope(
                messageId = "m_one", sessionId = "s_one", type = "plugin.test",
                capability = "text.v1", seq = 2, sentAtUnixMs = 1,
                requiresAck = true, payload = Json.Obj.EMPTY,
            ),
        )

        assertEquals(1, handled.get())
    }

    @Test
    fun `transfer completed without Activity replays when observer attaches`() {
        lateinit var owner: DefaultProtocolEnvelopeOwner
        val supervisor = ConnectionSupervisor(
            diagnosticLogs = LocalStructuredLogStore(),
            peerLinkLifecycle = FakePeerLinkLifecycle(),
            protocolOwnerFactory = { events ->
                owner = DefaultProtocolEnvelopeOwner(
                    TransferReceiver(FileTransferStorage(temp.newFolder("owner-replay"))),
                    deliverIncoming = { it.file },
                    notificationRuntime = object : NotificationRuntimeFacade {
                        override fun setPrivacyPolicy(policy: dev.hyphen.android.notifications.NotificationPrivacyPolicy) = Unit
                        override fun localPrivacyFloor() = NotificationPrivacyMode.SHOW_FULL
                        override fun canceller() = dev.hyphen.android.notifications.NotificationCanceller { false }
                        override fun isActive(sbnKey: String) = false
                        override fun replier() = dev.hyphen.android.notifications.NotificationReplier { _, _, _, _ ->
                            dev.hyphen.android.notifications.NotificationReplyAttempt.Failed("unavailable")
                        }
                    },
                    events = events,
                )
                owner
            },
        )
        val epoch = ProtocolSessionEpoch.of(22)
        assertTrue(supervisor.rememberEndpoint(restored.endpoint, fingerprint))
        owner.activate(
            ProtocolOwnerSession(
                epoch,
                SessionHandshake.NegotiatedCapabilities.advertised(),
                null,
                ByteArray(32) { 2 },
                "s_replay",
            ) { _, _, _, _ -> "sent" },
        )
        val emptySha = java.security.MessageDigest.getInstance("SHA-256")
            .digest(ByteArray(0)).joinToString("") { "%02x".format(it) }
        val manifest = TransferManifest(
            "f_replay_done",
            "done.bin",
            0,
            "application/octet-stream",
            emptySha,
            1024,
            0,
        )
        owner.handleEnvelope(
            epoch,
            Envelope(
                messageId = "manifest-replay",
                sessionId = "s_replay",
                type = dev.hyphen.android.transfer.TransferProtocol.TYPE_MANIFEST,
                capability = dev.hyphen.android.transfer.TransferProtocol.CAPABILITY,
                seq = 2,
                sentAtUnixMs = 1,
                requiresAck = true,
                payload = manifest.toJson(),
            ),
        )
        val replayed = mutableListOf<ActivityEvent>()

        supervisor.replayStateTo(object : ConnectionSupervisor.Listener {
            override fun onActivityEvent(event: ActivityEvent) { replayed += event }
        })

        assertTrue(replayed.any { it is ActivityEvent.TransferCompleted && it.fileId == manifest.fileId })

        supervisor.stopAfterTrustChange()
        assertTrue(supervisor.rememberEndpoint(ParsedEndpoint.Manual("192.168.1.99", 49999), ByteArray(32) { 9 }))
        val afterPeerChange = mutableListOf<ActivityEvent>()
        supervisor.replayStateTo(object : ConnectionSupervisor.Listener {
            override fun onActivityEvent(event: ActivityEvent) { afterPeerChange += event }
        })
        assertFalse(afterPeerChange.any { it is ActivityEvent.TransferCompleted })
    }

    @Test
    fun `cold start applies persisted notification floor before any Activity`() {
        var applied = NotificationPrivacyMode.SHOW_FULL

        initializeNotificationPrivacyFloor(
            load = { NotificationPrivacyMode.EXISTS_ONLY },
            apply = { applied = it },
        )

        assertEquals(NotificationPrivacyMode.EXISTS_ONLY, applied)
    }

    @Test
    fun `non durable retirement replays retry and successful reselection clears foreground marker`() {
        val states = mutableListOf<Boolean>()
        val supervisor = ConnectionSupervisor(
            diagnosticLogs = LocalStructuredLogStore(),
            peerLinkLifecycle = FakePeerLinkLifecycle(),
            onOutboundReselectStateChanged = states::add,
        )
        assertTrue(supervisor.rememberEndpoint(restored.endpoint, fingerprint))
        val generation = supervisor.currentPeerGenerationForTest()
        val interruptedBytes = ByteArray(2048) { (it % 251).toByte() }
        val interrupted = OutboundTransferRetirement(
            fileId = "f_picker_retry",
            filename = "picker.bin",
            sizeBytes = interruptedBytes.size.toLong(),
            mimeType = "application/octet-stream",
            sha256 = java.security.MessageDigest.getInstance("SHA-256")
                .digest(interruptedBytes).joinToString("") { "%02x".format(it) },
            chunkSizeBytes = 1024,
            requiresSourceReselection = true,
        )
        supervisor.acceptOutboundRetirementsForTest(
            listOf(interrupted),
            generation,
            fingerprint,
        )
        val replayed = mutableListOf<ActivityEvent>()
        var retryRequired = false
        supervisor.replayStateTo(object : ConnectionSupervisor.Listener {
            override fun onActivityEvent(event: ActivityEvent) { replayed += event }
            override fun onOutboundReselectRequiredChanged(required: Boolean) { retryRequired = required }
        })

        assertTrue(retryRequired)
        assertTrue(replayed.any {
            it is ActivityEvent.TransferSourceReselectionRequired && it.fileId == "f_picker_retry"
        })
        assertTrue(supervisor.selectOutboundSourceReselection("f_picker_retry"))
        val sender = TransferSender(
            outbox = object : TransferOutbox {
                override fun send(type: String, capability: String, requiresAck: Boolean, payload: Json.Obj) = "sent"
            },
        )
        sender.sendReselectedSource(
            retirement = interrupted,
            source = StreamTransferByteSource(interruptedBytes.size.toLong()) {
                java.io.ByteArrayInputStream(interruptedBytes)
            },
        )
        ConnectionSupervisor::class.java.getDeclaredField("activeTransferSender").apply {
            isAccessible = true
            set(supervisor, sender)
        }

        assertTrue(supervisor.completeOutboundSourceReselection("f_picker_retry", sender))
        assertEquals(listOf(true, false), states)
        assertFalse(supervisor.isOutboundSourceReselectionRequired("f_picker_retry"))
    }

    @Test
    fun `stale peer generation cannot publish source reselection into replacement trust`() {
        val states = mutableListOf<Boolean>()
        val supervisor = ConnectionSupervisor(
            diagnosticLogs = LocalStructuredLogStore(),
            peerLinkLifecycle = FakePeerLinkLifecycle(),
            onOutboundReselectStateChanged = states::add,
        )
        assertTrue(supervisor.rememberEndpoint(restored.endpoint, fingerprint))
        val staleGeneration = supervisor.currentPeerGenerationForTest()
        val replacement = ByteArray(32) { 0x44 }
        assertTrue(supervisor.rememberEndpoint(ParsedEndpoint.Manual("192.168.1.44", 44444), replacement))

        supervisor.acceptOutboundRetirementsForTest(
            listOf(
                OutboundTransferRetirement(
                    "f_stale_picker",
                    "stale.bin",
                    10,
                    "application/octet-stream",
                    "0".repeat(64),
                    1024,
                    true,
                ),
            ),
            staleGeneration,
            fingerprint,
        )

        assertFalse(supervisor.isOutboundSourceReselectionRequired("f_stale_picker"))
        assertTrue(states.isEmpty())
    }

    @Test
    fun `foreground registration loss preserves remote partial without wire cancel`() {
        val states = mutableListOf<Boolean>()
        val supervisor = ConnectionSupervisor(
            diagnosticLogs = LocalStructuredLogStore(),
            peerLinkLifecycle = FakePeerLinkLifecycle(),
            onOutboundReselectStateChanged = states::add,
        )
        assertTrue(supervisor.rememberEndpoint(restored.endpoint, fingerprint))
        val generation = supervisor.currentPeerGenerationForTest()
        val sentTypes = mutableListOf<String>()
        val sender = TransferSender(
            outbox = object : TransferOutbox {
                override fun send(type: String, capability: String, requiresAck: Boolean, payload: Json.Obj): String {
                    sentTypes += type
                    return "sent-${sentTypes.size}"
                }
            },
        )
        sender.sendSource(
            filename = "picker.bin",
            mimeType = "application/octet-stream",
            source = StreamTransferByteSource(2048) {
                java.io.ByteArrayInputStream(ByteArray(2048) { (it % 251).toByte() })
            },
            chunkSizeBytes = 1024,
            fileId = "f_fgs_registration_loss",
        )
        ConnectionSupervisor::class.java.getDeclaredField("activeTransferSender").apply {
            isAccessible = true
            set(supervisor, sender)
        }
        ConnectionSupervisor::class.java.getDeclaredField("activePeerGeneration").apply {
            isAccessible = true
            set(supervisor, generation)
        }

        supervisor.handleForegroundRegistrationLoss("f_fgs_registration_loss", sender)

        assertEquals(listOf(dev.hyphen.android.transfer.TransferProtocol.TYPE_MANIFEST), sentTypes)
        assertTrue(supervisor.isOutboundSourceReselectionRequired("f_fgs_registration_loss"))
        assertEquals(listOf(true), states)
    }

    @Test
    fun `foreground cancel winning race clears per-file resume but keeps fresh selection actionable`() {
        val states = mutableListOf<Boolean>()
        val supervisor = ConnectionSupervisor(
            diagnosticLogs = LocalStructuredLogStore(),
            peerLinkLifecycle = FakePeerLinkLifecycle(),
            onOutboundReselectStateChanged = states::add,
        )
        assertTrue(supervisor.rememberEndpoint(restored.endpoint, fingerprint))
        val generation = supervisor.currentPeerGenerationForTest()
        val bytes = ByteArray(2048) { (it % 251).toByte() }
        val retirement = OutboundTransferRetirement(
            "f_fgs_cancel_wins",
            "picker.bin",
            bytes.size.toLong(),
            "application/octet-stream",
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(bytes).joinToString("") { "%02x".format(it) },
            1024,
            true,
        )
        supervisor.acceptOutboundRetirementsForTest(listOf(retirement), generation, fingerprint)
        val sentTypes = mutableListOf<String>()
        val sender = TransferSender(
            outbox = object : TransferOutbox {
                override fun send(type: String, capability: String, requiresAck: Boolean, payload: Json.Obj): String {
                    sentTypes += type
                    return "sent-${sentTypes.size}"
                }
            },
        )
        sender.sendReselectedSource(
            retirement,
            StreamTransferByteSource(bytes.size.toLong()) { java.io.ByteArrayInputStream(bytes) },
        )
        ConnectionSupervisor::class.java.getDeclaredField("activeTransferSender").apply {
            isAccessible = true
            set(supervisor, sender)
        }
        ConnectionSupervisor::class.java.getDeclaredField("activePeerGeneration").apply {
            isAccessible = true
            set(supervisor, generation)
        }

        assertEquals(listOf("f_fgs_cancel_wins"), supervisor.cancelOutboundTransfersForForegroundStop())
        supervisor.handleForegroundRegistrationLoss("f_fgs_cancel_wins", sender)

        assertEquals(
            listOf(
                dev.hyphen.android.transfer.TransferProtocol.TYPE_MANIFEST,
                dev.hyphen.android.transfer.TransferProtocol.TYPE_CANCEL,
            ),
            sentTypes,
        )
        assertFalse(supervisor.isOutboundSourceReselectionRequired("f_fgs_cancel_wins"))
        assertEquals(listOf(true, false, true), states)
    }

    @Test
    fun `cold restore publishes paired reconnecting state and starts exactly once`() {
        val lifecycle = FakePeerLinkLifecycle(restored = restored)
        val reconnectStarts = AtomicInteger()
        val events = mutableListOf<ActivityEvent>()
        val supervisor = ConnectionSupervisor(
            diagnosticLogs = LocalStructuredLogStore(),
            peerLinkLifecycle = lifecycle,
            reconnectAfterRestore = { reconnectStarts.incrementAndGet() },
        )
        supervisor.addListener(object : ConnectionSupervisor.Listener {
            override fun onActivityEvent(event: ActivityEvent) {
                events += event
            }
        })

        assertTrue(supervisor.restorePersistedLink())
        assertFalse(supervisor.restorePersistedLink())

        assertEquals(1, reconnectStarts.get())
        assertTrue(fingerprint.contentEquals(supervisor.currentPeerFingerprint()))
        assertEquals(
            listOf(
                ActivityEvent.PeerChanged(isPaired = true, peerName = "Studio Mac"),
                ActivityEvent.ConnectionChanged(ConnectionState.RECONNECTING, null),
            ),
            events,
        )
    }

    @Test
    fun `restore failure is fail closed and diagnostic contains no routing metadata`() {
        val lifecycle = FakePeerLinkLifecycle(restoreFailure = IllegalStateException("disk failed"))
        val logs = LocalStructuredLogStore(clock = { 7L })
        val reconnectStarts = AtomicInteger()
        val supervisor = ConnectionSupervisor(
            diagnosticLogs = logs,
            peerLinkLifecycle = lifecycle,
            reconnectAfterRestore = { reconnectStarts.incrementAndGet() },
        )

        assertFalse(supervisor.restorePersistedLink())

        assertNull(supervisor.currentPeerFingerprint())
        assertEquals(0, reconnectStarts.get())
        assertEquals("trust/peer-link-restore-failed", logs.snapshot().single().code)
        assertEquals(
            mapOf("component" to "connection-supervisor", "operation" to "restore-link"),
            logs.snapshot().single().attributes,
        )
    }

    @Test
    fun `trust change invalidates durable routing after clearing live state`() {
        val lifecycle = FakePeerLinkLifecycle(restored = restored)
        val supervisor = ConnectionSupervisor(
            diagnosticLogs = LocalStructuredLogStore(),
            peerLinkLifecycle = lifecycle,
            reconnectAfterRestore = {},
        )
        assertTrue(supervisor.restorePersistedLink())

        supervisor.stopAfterTrustChange()

        assertNull(supervisor.currentPeerFingerprint())
        assertEquals(1, lifecycle.invalidations)
    }

    @Test
    fun `re-pair retires the previous reconnect owner and replaces its durable route`() {
        val lifecycle = FakePeerLinkLifecycle(restored = restored)
        val owner = FakeTrustedPeerReconnectOwner()
        val supervisor = ConnectionSupervisor(
            diagnosticLogs = LocalStructuredLogStore(),
            peerLinkLifecycle = lifecycle,
            reconnectFactory = TrustedPeerReconnectFactory { owner },
        )
        assertTrue(supervisor.restorePersistedLink())
        assertEquals(1, owner.starts)
        val nextFingerprint = ByteArray(32) { 0x52 }
        val nextEndpoint = ParsedEndpoint.Manual("192.168.1.52", 45200)

        supervisor.rememberEndpoint(nextEndpoint, nextFingerprint)

        assertEquals(1, owner.stops)
        assertEquals(1, lifecycle.invalidations)
        assertEquals(nextEndpoint, lifecycle.remembered.single().first)
        assertTrue(nextFingerprint.contentEquals(lifecycle.remembered.single().second))
        assertTrue(nextFingerprint.contentEquals(supervisor.currentPeerFingerprint()))
    }

    @Test
    fun `failed pairing route commit publishes neither peer nor reconnect state`() {
        val lifecycle = FakePeerLinkLifecycle().apply {
            pairingCommitFailure = IllegalStateException("route disk full")
        }
        val logs = LocalStructuredLogStore()
        val events = mutableListOf<ActivityEvent>()
        val supervisor = ConnectionSupervisor(
            diagnosticLogs = logs,
            peerLinkLifecycle = lifecycle,
            reconnectAfterRestore = {},
        )
        supervisor.addListener(object : ConnectionSupervisor.Listener {
            override fun onActivityEvent(event: ActivityEvent) {
                events += event
            }
        })

        val committed = supervisor.commitPairingEndpoint(restored.endpoint, restored.peer)

        assertFalse(committed)
        assertNull(supervisor.currentPeerFingerprint())
        assertTrue(events.none { it == ActivityEvent.ConnectionChanged(ConnectionState.RECONNECTING, null) })
        assertEquals("trust/peer-link-persist-failed", logs.snapshot().single().code)
    }

    @Test
    fun `user pause retires transport but keeps durable trust route for explicit resume`() {
        val lifecycle = FakePeerLinkLifecycle(restored = restored)
        val owners = mutableListOf<FakeTrustedPeerReconnectOwner>()
        val events = mutableListOf<ActivityEvent>()
        val supervisor = ConnectionSupervisor(
            diagnosticLogs = LocalStructuredLogStore(),
            peerLinkLifecycle = lifecycle,
            reconnectFactory = TrustedPeerReconnectFactory {
                FakeTrustedPeerReconnectOwner().also(owners::add)
            },
        )
        supervisor.addListener(object : ConnectionSupervisor.Listener {
            override fun onActivityEvent(event: ActivityEvent) {
                events += event
            }
        })
        assertTrue(supervisor.restorePersistedLink())
        assertEquals(1, owners.single().starts)

        supervisor.pauseConnection()

        assertEquals(1, owners.single().stops)
        assertTrue(fingerprint.contentEquals(supervisor.currentPeerFingerprint()))
        assertEquals(0, lifecycle.invalidations)
        assertEquals(ActivityEvent.ConnectionChanged(ConnectionState.SUSPENDED, null), events.last())

        assertTrue(supervisor.resumeConnection())
        assertEquals(2, owners.size)
        assertEquals(1, owners.last().starts)
        assertEquals(ActivityEvent.ConnectionChanged(ConnectionState.RECONNECTING, null), events.last())
    }

    @Test
    fun `trust revoke while paused still clears durable route and blocks resume`() {
        val lifecycle = FakePeerLinkLifecycle(restored = restored)
        val supervisor = ConnectionSupervisor(
            diagnosticLogs = LocalStructuredLogStore(),
            peerLinkLifecycle = lifecycle,
            reconnectAfterRestore = {},
        )
        assertTrue(supervisor.restorePersistedLink())
        supervisor.pauseConnection()

        supervisor.stopAfterTrustChange()

        assertFalse(supervisor.resumeConnection())
        assertNull(supervisor.currentPeerFingerprint())
        assertEquals(1, lifecycle.invalidations)
    }

    @Test
    fun `trust revoke wins while reconnect callback is entering activation`() {
        val lifecycle = FakePeerLinkLifecycle(restored = restored)
        val owner = FakeTrustedPeerReconnectOwner()
        lateinit var reconnectListener: SessionReconnector.Listener
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val events = mutableListOf<ActivityEvent>()
        val supervisor = ConnectionSupervisor(
            diagnosticLogs = LocalStructuredLogStore(),
            peerLinkLifecycle = lifecycle,
            reconnectFactory = TrustedPeerReconnectFactory { request ->
                reconnectListener = request.listener
                owner
            },
            beforeReconnectSessionActivation = {
                callbackEntered.countDown()
                assertTrue(releaseCallback.await(5, TimeUnit.SECONDS))
            },
        )
        supervisor.addListener(object : ConnectionSupervisor.Listener {
            override fun onActivityEvent(event: ActivityEvent) {
                synchronized(events) { events += event }
            }
        })
        assertTrue(supervisor.restorePersistedLink())
        assertEquals(1, owner.starts)

        val socket = SSLSocketFactory.getDefault().createSocket()
        val session = ProtocolSession(
            socket = socket as javax.net.ssl.SSLSocket,
            sessionId = "s_stale",
            listener = object : ProtocolSession.Listener {},
        )
        val callback = thread(start = true, name = "reconnect-activation-race") {
            reconnectListener.onSession(
                session,
                SessionHandshake.Result(
                    sessionId = "s_stale",
                    resumeToken = "must-not-survive",
                    resumed = false,
                    peerDevice = SessionHandshake.DeviceInfo("macos", "0.0.1", "Revoked Mac"),
                    negotiatedCapabilities = SessionHandshake.NegotiatedCapabilities.empty(),
                ),
            )
        }
        assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))

        supervisor.stopAfterTrustChange()
        releaseCallback.countDown()
        callback.join(5_000)

        assertFalse(callback.isAlive)
        assertEquals(1, owner.stops)
        assertNull(supervisor.activeState().session)
        assertNull(supervisor.currentSessionId())
        assertTrue(socket.isClosed)
        assertFalse(
            synchronized(events) {
                events.any {
                    it == ActivityEvent.ConnectionChanged(ConnectionState.CONNECTED, null)
                }
            },
        )
    }

    @Test
    fun `trust revoke wins while freshly paired session is entering activation`() {
        val lifecycle = FakePeerLinkLifecycle()
        val activationEntered = CountDownLatch(1)
        val releaseActivation = CountDownLatch(1)
        val events = mutableListOf<ActivityEvent>()
        val supervisor = ConnectionSupervisor(
            diagnosticLogs = LocalStructuredLogStore(),
            peerLinkLifecycle = lifecycle,
            reconnectAfterRestore = {},
            beforePairedSessionActivation = {
                activationEntered.countDown()
                assertTrue(releaseActivation.await(5, TimeUnit.SECONDS))
            },
        )
        supervisor.addListener(object : ConnectionSupervisor.Listener {
            override fun onActivityEvent(event: ActivityEvent) {
                synchronized(events) { events += event }
            }
        })
        supervisor.rememberEndpoint(restored.endpoint, fingerprint)

        val socket = SSLSocketFactory.getDefault().createSocket()
        var adopted: Boolean? = null
        val callback = thread(start = true, name = "paired-session-activation-race") {
            adopted = supervisor.adoptPairedSession(
                socket = socket as javax.net.ssl.SSLSocket,
                handshake = SessionHandshake.Result(
                    sessionId = "s_new_pair",
                    resumeToken = "must-not-survive",
                    resumed = false,
                    peerDevice = SessionHandshake.DeviceInfo("macos", "0.0.1", "Revoked Mac"),
                    negotiatedCapabilities = SessionHandshake.NegotiatedCapabilities.empty(),
                ),
                peerName = "Revoked Mac",
            )
        }
        assertTrue(activationEntered.await(5, TimeUnit.SECONDS))

        supervisor.stopAfterTrustChange()
        releaseActivation.countDown()
        callback.join(5_000)

        assertFalse(callback.isAlive)
        assertEquals(false, adopted)
        assertNull(supervisor.activeState().session)
        assertNull(supervisor.currentSessionId())
        assertTrue(socket.isClosed)
        assertFalse(
            synchronized(events) {
                events.any {
                    it == ActivityEvent.ConnectionChanged(ConnectionState.CONNECTED, null)
                }
            },
        )
    }

    @Test
    fun `trust revoke cannot leave a descriptor written by in flight remember`() {
        val lifecycle = BlockingRememberPeerLinkLifecycle()
        val supervisor = ConnectionSupervisor(
            diagnosticLogs = LocalStructuredLogStore(),
            peerLinkLifecycle = lifecycle,
            reconnectAfterRestore = {},
        )
        val remember = thread(start = true, name = "blocked-peer-link-remember") {
            supervisor.rememberEndpoint(restored.endpoint, fingerprint)
        }
        assertTrue(lifecycle.rememberEntered.await(5, TimeUnit.SECONDS))

        val revoke = thread(start = true, name = "peer-link-revoke") {
            supervisor.stopAfterTrustChange()
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (supervisor.currentPeerFingerprint() != null && System.nanoTime() < deadline) {
            Thread.yield()
        }
        assertNull(supervisor.currentPeerFingerprint())

        lifecycle.releaseRemember.countDown()
        remember.join(5_000)
        revoke.join(5_000)

        assertFalse(remember.isAlive)
        assertFalse(revoke.isAlive)
        assertFalse(lifecycle.stored.get())
        assertTrue(lifecycle.invalidations.get() >= 1)
    }

    @Test
    fun `foreground inspection never holds supervisor state lock while waiting for sender`() {
        val senderSnapshotTaken = CountDownLatch(1)
        val allowSenderInspection = CountDownLatch(1)
        val callbackEntered = CountDownLatch(1)
        val allowCallbackStateWrite = CountDownLatch(1)
        lateinit var supervisor: ConnectionSupervisor
        supervisor = ConnectionSupervisor(
            diagnosticLogs = LocalStructuredLogStore(),
            peerLinkLifecycle = FakePeerLinkLifecycle(),
            beforeTransferSenderInspection = {
                senderSnapshotTaken.countDown()
                assertTrue(allowSenderInspection.await(5, TimeUnit.SECONDS))
            },
        )
        var sent = 0
        val sender = TransferSender(
            outbox = object : TransferOutbox {
                override fun send(
                    type: String,
                    capability: String,
                    requiresAck: Boolean,
                    payload: Json.Obj,
                ): String = "sent-${sent++}"
            },
            outstandingWindow = 1,
            onProgress = { progress ->
                if (progress.isComplete) {
                    callbackEntered.countDown()
                    assertTrue(allowCallbackStateWrite.await(5, TimeUnit.SECONDS))
                }
                supervisor.updateLastTransferProgress(progress)
            },
        )
        val senderField = ConnectionSupervisor::class.java.getDeclaredField("activeTransferSender")
        senderField.isAccessible = true
        senderField.set(supervisor, sender)
        sender.sendSource(
            filename = "lock.bin",
            mimeType = "application/octet-stream",
            source = StreamTransferByteSource(1) { java.io.ByteArrayInputStream(byteArrayOf(1)) },
            chunkSizeBytes = 1024,
            fileId = "f_lock_order",
        )
        sender.handleAck("sent-0")

        val ackThread = thread(name = "sender-progress-callback") {
            sender.handleAck("sent-1")
        }
        assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))
        val inspectionResult = AtomicReference<Boolean?>()
        val inspectionThread = thread(name = "foreground-sender-inspection") {
            inspectionResult.set(supervisor.hasInFlightOutboundTransfers())
        }
        assertTrue(senderSnapshotTaken.await(5, TimeUnit.SECONDS))

        allowCallbackStateWrite.countDown()
        allowSenderInspection.countDown()
        ackThread.join(5_000)
        inspectionThread.join(5_000)

        assertFalse(ackThread.isAlive)
        assertFalse(inspectionThread.isAlive)
        assertEquals(false, inspectionResult.get())
    }
}
