package dev.hyphen.android.session

import dev.hyphen.android.diagnostics.LocalStructuredLogStore
import dev.hyphen.android.pairing.ParsedEndpoint
import dev.hyphen.android.transport.ProtocolSession
import dev.hyphen.android.transport.Json
import dev.hyphen.android.transport.ReconnectRetryOutcome
import dev.hyphen.android.transport.SessionHandshake
import dev.hyphen.android.transport.SessionReconnector
import dev.hyphen.android.transfer.StreamTransferByteSource
import dev.hyphen.android.transfer.TransferOutbox
import dev.hyphen.android.transfer.TransferManifest
import dev.hyphen.android.transfer.TransferReceiver
import dev.hyphen.android.transfer.FileTransferStorage
import dev.hyphen.android.notifications.NotificationPrivacyMode
import dev.hyphen.android.transport.Envelope
import dev.hyphen.android.transport.HeartbeatMonitor
import dev.hyphen.android.transfer.TransferSender
import dev.hyphen.android.transfer.OutboundTransferRetirement
import dev.hyphen.android.text.TextLinkConfirmationRequest
import dev.hyphen.android.text.TextLinkMessage
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
    var retries = 0

    override fun start() {
        starts++
    }

    override fun stop() {
        stops++
    }

    override fun retryNow(): ReconnectRetryOutcome {
        retries++
        return ReconnectRetryOutcome.SCHEDULED
    }
}

private class FailingStartReconnectOwner : TrustedPeerReconnectOwner {
    var stops = 0

    override fun start() {
        error("scheduler unavailable")
    }

    override fun stop() {
        stops++
    }
}

private class BlockingStopReconnectOwner : TrustedPeerReconnectOwner {
    val stopEntered = CountDownLatch(1)
    val releaseStop = CountDownLatch(1)
    var starts = 0

    override fun start() {
        starts++
    }

    override fun stop() {
        stopEntered.countDown()
        check(releaseStop.await(5, TimeUnit.SECONDS))
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
    fun `activity lifecycle pause returns before blocking transport teardown`() {
        val lifecycle = FakePeerLinkLifecycle(restored = restored)
        val owner = BlockingStopReconnectOwner()
        val supervisor = ConnectionSupervisor(
            diagnosticLogs = LocalStructuredLogStore(),
            peerLinkLifecycle = lifecycle,
            reconnectFactory = TrustedPeerReconnectFactory { owner },
        )
        assertTrue(supervisor.restorePersistedLink())
        val returned = AtomicBoolean(false)
        val lifecycleCaller = thread(name = "activity-on-stop") {
            returned.set(supervisor.pauseConnectionForLifecycle())
        }
        lifecycleCaller.join(1_000)
        val returnedBeforeTeardown = !lifecycleCaller.isAlive
        assertTrue(owner.stopEntered.await(5, TimeUnit.SECONDS))

        owner.releaseStop.countDown()
        lifecycleCaller.join(5_000)

        assertTrue(returnedBeforeTeardown)
        assertTrue(returned.get())
        assertFalse(lifecycleCaller.isAlive)
        assertTrue(fingerprint.contentEquals(supervisor.currentPeerFingerprint()))
    }

    @Test
    fun `lifecycle pause waits off-main for a durable pairing commit then wins the generation`() {
        val lifecycle = BlockingRememberPeerLinkLifecycle()
        val events = mutableListOf<ActivityEvent>()
        val suspended = CountDownLatch(1)
        val supervisor = ConnectionSupervisor(
            diagnosticLogs = LocalStructuredLogStore(),
            peerLinkLifecycle = lifecycle,
        )
        supervisor.addListener(object : ConnectionSupervisor.Listener {
            override fun onActivityEvent(event: ActivityEvent) {
                synchronized(events) { events += event }
                if (event == ActivityEvent.ConnectionChanged(ConnectionState.SUSPENDED, null)) {
                    suspended.countDown()
                }
            }
        })
        val nextFingerprint = ByteArray(32) { 0x45 }
        val committed = AtomicBoolean(false)
        val pairingCommit = thread(name = "durable-pairing-commit") {
            committed.set(
                supervisor.rememberEndpoint(
                    ParsedEndpoint.Manual("192.168.1.45", 45200),
                    nextFingerprint,
                ),
            )
        }
        assertTrue(lifecycle.rememberEntered.await(5, TimeUnit.SECONDS))

        assertTrue(supervisor.pauseConnectionForLifecycle())
        assertFalse("pause must not publish ahead of the durable commit", suspended.await(200, TimeUnit.MILLISECONDS))

        lifecycle.releaseRemember.countDown()
        pairingCommit.join(5_000)
        assertTrue(suspended.await(5, TimeUnit.SECONDS))

        assertFalse(pairingCommit.isAlive)
        assertTrue(committed.get())
        assertTrue(nextFingerprint.contentEquals(supervisor.currentPeerFingerprint()))
        val connectionEvents = synchronized(events) {
            events.filterIsInstance<ActivityEvent.ConnectionChanged>()
        }
        assertEquals(ConnectionState.SUSPENDED, connectionEvents.last().state)
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
    fun `explicit recovery retries the current generation owner immediately`() {
        val lifecycle = FakePeerLinkLifecycle(restored = restored)
        val owners = mutableListOf<FakeTrustedPeerReconnectOwner>()
        val supervisor = ConnectionSupervisor(
            diagnosticLogs = LocalStructuredLogStore(),
            peerLinkLifecycle = lifecycle,
            reconnectFactory = TrustedPeerReconnectFactory {
                FakeTrustedPeerReconnectOwner().also(owners::add)
            },
        )

        assertTrue(supervisor.restorePersistedLink())
        assertEquals(ConnectionRetryResult.STARTED, supervisor.retryConnectionNow())

        assertEquals(1, owners.single().starts)
        assertEquals(1, owners.single().retries)
        assertEquals(0, owners.single().stops)
    }

    @Test
    fun `explicit recovery resumes a user-paused trusted route`() {
        val lifecycle = FakePeerLinkLifecycle(restored = restored)
        val owners = mutableListOf<FakeTrustedPeerReconnectOwner>()
        val supervisor = ConnectionSupervisor(
            diagnosticLogs = LocalStructuredLogStore(),
            peerLinkLifecycle = lifecycle,
            reconnectFactory = TrustedPeerReconnectFactory {
                FakeTrustedPeerReconnectOwner().also(owners::add)
            },
        )

        assertTrue(supervisor.restorePersistedLink())
        supervisor.pauseConnection()

        assertEquals(ConnectionRetryResult.STARTED, supervisor.retryConnectionNow())
        assertEquals(2, owners.size)
        assertEquals(1, owners.last().starts)
    }

    @Test
    fun `cold restore reports failure when its reconnect owner cannot start`() {
        val lifecycle = FakePeerLinkLifecycle(restored = restored)
        val owner = FailingStartReconnectOwner()
        val events = mutableListOf<ActivityEvent>()
        val supervisor = ConnectionSupervisor(
            diagnosticLogs = LocalStructuredLogStore(),
            peerLinkLifecycle = lifecycle,
            reconnectFactory = TrustedPeerReconnectFactory { owner },
        )
        supervisor.addListener(object : ConnectionSupervisor.Listener {
            override fun onActivityEvent(event: ActivityEvent) {
                events += event
            }
        })

        assertFalse(supervisor.restorePersistedLink())

        assertTrue(fingerprint.contentEquals(supervisor.currentPeerFingerprint()))
        assertEquals(1, owner.stops)
        assertEquals(ActivityEvent.ConnectionChanged(ConnectionState.SUSPENDED, null), events.last())
    }

    @Test
    fun `explicit recovery reports unavailable when replacement owner cannot start`() {
        val lifecycle = FakePeerLinkLifecycle(restored = restored)
        val initialOwner = FakeTrustedPeerReconnectOwner()
        val failedOwner = FailingStartReconnectOwner()
        val factoryCalls = AtomicInteger()
        val supervisor = ConnectionSupervisor(
            diagnosticLogs = LocalStructuredLogStore(),
            peerLinkLifecycle = lifecycle,
            reconnectFactory = TrustedPeerReconnectFactory {
                if (factoryCalls.getAndIncrement() == 0) initialOwner else failedOwner
            },
        )
        assertTrue(supervisor.restorePersistedLink())
        supervisor.pauseConnection()

        assertEquals(ConnectionRetryResult.UNAVAILABLE, supervisor.retryConnectionNow())

        assertEquals(1, failedOwner.stops)
        assertTrue(fingerprint.contentEquals(supervisor.currentPeerFingerprint()))
    }

    @Test
    fun `explicit recovery reports an existing in flight handshake honestly`() {
        val lifecycle = FakePeerLinkLifecycle(restored = restored)
        val owner = object : TrustedPeerReconnectOwner {
            override fun start() = Unit
            override fun stop() = Unit
            override fun retryNow(): ReconnectRetryOutcome = ReconnectRetryOutcome.ALREADY_IN_PROGRESS
        }
        val supervisor = ConnectionSupervisor(
            diagnosticLogs = LocalStructuredLogStore(),
            peerLinkLifecycle = lifecycle,
            reconnectFactory = TrustedPeerReconnectFactory { owner },
        )

        assertTrue(supervisor.restorePersistedLink())
        assertEquals(
            ConnectionRetryResult.ALREADY_IN_PROGRESS,
            supervisor.retryConnectionNow(),
        )
    }

    @Test
    fun `explicit recovery reports scheduler rejection as unavailable`() {
        val lifecycle = FakePeerLinkLifecycle(restored = restored)
        val owner = object : TrustedPeerReconnectOwner {
            override fun start() = Unit
            override fun stop() = Unit
            override fun retryNow(): ReconnectRetryOutcome = ReconnectRetryOutcome.FAILED
        }
        val supervisor = ConnectionSupervisor(
            diagnosticLogs = LocalStructuredLogStore(),
            peerLinkLifecycle = lifecycle,
            reconnectFactory = TrustedPeerReconnectFactory { owner },
        )

        assertTrue(supervisor.restorePersistedLink())
        assertEquals(ConnectionRetryResult.UNAVAILABLE, supervisor.retryConnectionNow())
    }

    @Test
    fun `failed restore start cannot suspend a replacement peer generation`() {
        val lifecycle = FakePeerLinkLifecycle(restored = restored)
        val failedOwner = FailingStartReconnectOwner()
        val failureReadyToPublish = CountDownLatch(1)
        val releaseFailure = CountDownLatch(1)
        val events = mutableListOf<ActivityEvent>()
        val supervisor = ConnectionSupervisor(
            diagnosticLogs = LocalStructuredLogStore(),
            peerLinkLifecycle = lifecycle,
            reconnectFactory = TrustedPeerReconnectFactory { failedOwner },
            beforeReconnectStartFailurePublished = {
                failureReadyToPublish.countDown()
                assertTrue(releaseFailure.await(5, TimeUnit.SECONDS))
            },
        )
        supervisor.addListener(object : ConnectionSupervisor.Listener {
            override fun onActivityEvent(event: ActivityEvent) {
                synchronized(events) { events += event }
            }
        })
        val restoreResult = AtomicBoolean(true)
        val restore = thread(name = "failed-restore-generation-a") {
            restoreResult.set(supervisor.restorePersistedLink())
        }
        assertTrue(failureReadyToPublish.await(5, TimeUnit.SECONDS))
        val nextEndpoint = ParsedEndpoint.Manual("192.168.1.52", 45200)
        val nextFingerprint = ByteArray(32) { 0x52 }

        assertTrue(supervisor.rememberEndpoint(nextEndpoint, nextFingerprint))
        releaseFailure.countDown()
        restore.join(5_000)

        assertFalse(restore.isAlive)
        assertFalse(restoreResult.get())
        assertTrue(nextFingerprint.contentEquals(supervisor.currentPeerFingerprint()))
        val connectionEvents = synchronized(events) {
            events.filterIsInstance<ActivityEvent.ConnectionChanged>()
        }
        assertEquals(ConnectionState.RECONNECTING, connectionEvents.last().state)
        assertFalse(
            "generation A must not publish suspended after generation B",
            connectionEvents.dropWhile { it.state != ConnectionState.RECONNECTING }
                .drop(1)
                .any { it.state == ConnectionState.SUSPENDED },
        )
    }

    @Test
    fun `retry publication cannot cross a peer generation pause`() {
        val lifecycle = FakePeerLinkLifecycle(restored = restored)
        val owner = FakeTrustedPeerReconnectOwner()
        val retryReadyToPublish = CountDownLatch(1)
        val releaseRetryPublish = CountDownLatch(1)
        val pauseFinished = CountDownLatch(1)
        val events = mutableListOf<ActivityEvent>()
        val supervisor = ConnectionSupervisor(
            diagnosticLogs = LocalStructuredLogStore(),
            peerLinkLifecycle = lifecycle,
            reconnectFactory = TrustedPeerReconnectFactory { owner },
            beforeRetryConnectionPublished = {
                retryReadyToPublish.countDown()
                assertTrue(releaseRetryPublish.await(5, TimeUnit.SECONDS))
            },
        )
        supervisor.addListener(object : ConnectionSupervisor.Listener {
            override fun onActivityEvent(event: ActivityEvent) {
                synchronized(events) { events += event }
            }
        })
        assertTrue(supervisor.restorePersistedLink())
        val retryResult = AtomicReference<ConnectionRetryResult?>()
        val retry = thread(name = "retry-current-peer") {
            retryResult.set(supervisor.retryConnectionNow())
        }
        assertTrue(retryReadyToPublish.await(5, TimeUnit.SECONDS))
        val pause = thread(name = "pause-next-generation") {
            supervisor.pauseConnection()
            pauseFinished.countDown()
        }
        val pauseOvertookRetry = pauseFinished.await(200, TimeUnit.MILLISECONDS)

        releaseRetryPublish.countDown()
        retry.join(5_000)
        pause.join(5_000)

        assertFalse(pauseOvertookRetry)
        assertFalse(retry.isAlive)
        assertFalse(pause.isAlive)
        assertEquals(ConnectionRetryResult.STARTED, retryResult.get())
        val connectionEvents = synchronized(events) {
            events.filterIsInstance<ActivityEvent.ConnectionChanged>()
        }
        assertEquals(ConnectionState.SUSPENDED, connectionEvents.last().state)
    }

    @Test
    fun `replay callback can pause while retry publishes without lock inversion`() {
        val lifecycle = FakePeerLinkLifecycle(restored = restored)
        val owner = FakeTrustedPeerReconnectOwner()
        val retryOwnsTransition = CountDownLatch(1)
        val releaseRetry = CountDownLatch(1)
        val callbackEntered = CountDownLatch(1)
        val pausedOnce = AtomicBoolean(false)
        val supervisor = ConnectionSupervisor(
            diagnosticLogs = LocalStructuredLogStore(),
            peerLinkLifecycle = lifecycle,
            reconnectFactory = TrustedPeerReconnectFactory { owner },
            beforeRetryConnectionPublished = {
                retryOwnsTransition.countDown()
                assertTrue(releaseRetry.await(5, TimeUnit.SECONDS))
            },
        )
        assertTrue(supervisor.restorePersistedLink())
        val retryResult = AtomicReference<ConnectionRetryResult?>()
        val retry = thread(name = "retry-holds-transition") {
            retryResult.set(supervisor.retryConnectionNow())
        }
        assertTrue(retryOwnsTransition.await(5, TimeUnit.SECONDS))

        val replay = thread(name = "replay-reenters-pause") {
            supervisor.addListenerAndReplay(object : ConnectionSupervisor.Listener {
                override fun onActivityEvent(event: ActivityEvent) {
                    if (
                        event is ActivityEvent.ConnectionChanged &&
                        pausedOnce.compareAndSet(false, true)
                    ) {
                        callbackEntered.countDown()
                        supervisor.pauseConnection()
                    }
                }
            })
        }
        assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))

        releaseRetry.countDown()
        retry.join(5_000)
        replay.join(5_000)

        assertFalse("retry and replay must not deadlock", retry.isAlive)
        assertFalse("reentrant pause must finish", replay.isAlive)
        assertEquals(ConnectionRetryResult.STARTED, retryResult.get())
        assertTrue(pausedOnce.get())
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
    fun `stale reconnect session liveness cannot overwrite the current session state`() {
        val lifecycle = FakePeerLinkLifecycle(restored = restored)
        lateinit var reconnectListener: SessionReconnector.Listener
        val events = mutableListOf<ActivityEvent>()
        val supervisor = ConnectionSupervisor(
            diagnosticLogs = LocalStructuredLogStore(),
            peerLinkLifecycle = lifecycle,
            reconnectFactory = TrustedPeerReconnectFactory { request ->
                reconnectListener = request.listener
                FakeTrustedPeerReconnectOwner()
            },
        )
        supervisor.addListener(object : ConnectionSupervisor.Listener {
            override fun onActivityEvent(event: ActivityEvent) {
                events += event
            }
        })
        assertTrue(supervisor.restorePersistedLink())

        fun session(id: String): ProtocolSession = ProtocolSession(
            socket = SSLSocketFactory.getDefault().createSocket() as javax.net.ssl.SSLSocket,
            sessionId = id,
            listener = object : ProtocolSession.Listener {},
        )
        fun handshake(id: String) = SessionHandshake.Result(
            sessionId = id,
            resumeToken = null,
            resumed = false,
            peerDevice = SessionHandshake.DeviceInfo("macos", "0.0.1", "Mac"),
            negotiatedCapabilities = SessionHandshake.NegotiatedCapabilities.empty(),
        )

        val oldSession = session("s_old")
        reconnectListener.onSession(oldSession, handshake("s_old"))
        reconnectListener.onSessionLost(oldSession)
        val currentSession = session("s_current")
        reconnectListener.onSession(currentSession, handshake("s_current"))
        events.clear()

        reconnectListener.onLiveness(oldSession, HeartbeatMonitor.State.DEGRADED)
        assertTrue(events.isEmpty())

        reconnectListener.onLiveness(currentSession, HeartbeatMonitor.State.DEGRADED)
        assertEquals(
            listOf(ActivityEvent.ConnectionChanged(ConnectionState.DEGRADED, null)),
            events,
        )

        oldSession.stop()
        supervisor.stopAfterTrustChange()
    }

    @Test
    fun `degraded reconnect liveness replays to a recreated activity`() {
        val lifecycle = FakePeerLinkLifecycle(restored = restored)
        lateinit var reconnectListener: SessionReconnector.Listener
        val supervisor = ConnectionSupervisor(
            diagnosticLogs = LocalStructuredLogStore(),
            peerLinkLifecycle = lifecycle,
            reconnectFactory = TrustedPeerReconnectFactory { request ->
                reconnectListener = request.listener
                FakeTrustedPeerReconnectOwner()
            },
        )
        assertTrue(supervisor.restorePersistedLink())
        val session = ProtocolSession(
            socket = SSLSocketFactory.getDefault().createSocket() as javax.net.ssl.SSLSocket,
            sessionId = "s_degraded_replay",
            listener = object : ProtocolSession.Listener {},
        )
        reconnectListener.onSession(
            session,
            SessionHandshake.Result(
                sessionId = "s_degraded_replay",
                resumeToken = null,
                resumed = false,
                peerDevice = SessionHandshake.DeviceInfo("macos", "0.0.1", "Mac"),
                negotiatedCapabilities = SessionHandshake.NegotiatedCapabilities.empty(),
            ),
        )
        reconnectListener.onLiveness(session, HeartbeatMonitor.State.DEGRADED)
        val replayed = mutableListOf<ActivityEvent>()

        supervisor.replayStateTo(object : ConnectionSupervisor.Listener {
            override fun onActivityEvent(event: ActivityEvent) {
                replayed += event
            }
        })

        assertTrue(
            replayed.contains(ActivityEvent.ConnectionChanged(ConnectionState.DEGRADED, null)),
        )
        assertFalse(
            replayed.contains(ActivityEvent.ConnectionChanged(ConnectionState.CONNECTED, null)),
        )
        session.stop()
        supervisor.stopAfterTrustChange()
    }

    @Test
    fun `atomic listener replay cannot overwrite a newer healthy callback`() {
        val lifecycle = FakePeerLinkLifecycle(restored = restored)
        lateinit var reconnectListener: SessionReconnector.Listener
        val replaySnapshotTaken = CountDownLatch(1)
        val releaseReplay = CountDownLatch(1)
        val supervisor = ConnectionSupervisor(
            diagnosticLogs = LocalStructuredLogStore(),
            peerLinkLifecycle = lifecycle,
            reconnectFactory = TrustedPeerReconnectFactory { request ->
                reconnectListener = request.listener
                FakeTrustedPeerReconnectOwner()
            },
            beforeStateReplayDelivery = {
                replaySnapshotTaken.countDown()
                assertTrue(releaseReplay.await(5, TimeUnit.SECONDS))
            },
        )
        assertTrue(supervisor.restorePersistedLink())
        val session = ProtocolSession(
            socket = SSLSocketFactory.getDefault().createSocket() as javax.net.ssl.SSLSocket,
            sessionId = "s_atomic_replay",
            listener = object : ProtocolSession.Listener {},
        )
        val handshake = SessionHandshake.Result(
            sessionId = "s_atomic_replay",
            resumeToken = null,
            resumed = false,
            peerDevice = SessionHandshake.DeviceInfo("macos", "0.0.1", "Mac"),
            negotiatedCapabilities = SessionHandshake.NegotiatedCapabilities.empty(),
        )
        reconnectListener.onSession(session, handshake)
        reconnectListener.onLiveness(session, HeartbeatMonitor.State.DEGRADED)
        val replayed = mutableListOf<ActivityEvent>()
        val attach = thread(name = "atomic-listener-replay") {
            supervisor.addListenerAndReplay(object : ConnectionSupervisor.Listener {
                override fun onActivityEvent(event: ActivityEvent) {
                    synchronized(replayed) { replayed += event }
                }
            })
        }
        assertTrue(replaySnapshotTaken.await(5, TimeUnit.SECONDS))
        val recovery = thread(name = "liveness-during-replay") {
            reconnectListener.onLiveness(session, HeartbeatMonitor.State.HEALTHY)
        }
        releaseReplay.countDown()
        attach.join(5_000)
        recovery.join(5_000)

        assertFalse(attach.isAlive)
        assertFalse(recovery.isAlive)
        val connectionEvents = synchronized(replayed) {
            replayed.filterIsInstance<ActivityEvent.ConnectionChanged>()
        }
        assertEquals(ConnectionState.CONNECTED, connectionEvents.last().state)
        session.stop()
        supervisor.stopAfterTrustChange()
    }

    @Test
    fun `peer replacement never replays the previous generations connection state`() {
        val lifecycle = FakePeerLinkLifecycle(restored = restored)
        lateinit var reconnectListener: SessionReconnector.Listener
        val newPeerPublished = CountDownLatch(1)
        val releaseNewPeerConnection = CountDownLatch(1)
        val supervisor = ConnectionSupervisor(
            diagnosticLogs = LocalStructuredLogStore(),
            peerLinkLifecycle = lifecycle,
            reconnectFactory = TrustedPeerReconnectFactory { request ->
                reconnectListener = request.listener
                FakeTrustedPeerReconnectOwner()
            },
            beforePeerLinkConnectionPublished = {
                newPeerPublished.countDown()
                assertTrue(releaseNewPeerConnection.await(5, TimeUnit.SECONDS))
            },
        )
        assertTrue(supervisor.restorePersistedLink())
        val session = ProtocolSession(
            socket = SSLSocketFactory.getDefault().createSocket() as javax.net.ssl.SSLSocket,
            sessionId = "s_old_generation",
            listener = object : ProtocolSession.Listener {},
        )
        reconnectListener.onSession(
            session,
            SessionHandshake.Result(
                sessionId = "s_old_generation",
                resumeToken = null,
                resumed = false,
                peerDevice = SessionHandshake.DeviceInfo("macos", "0.0.1", "Old Mac"),
                negotiatedCapabilities = SessionHandshake.NegotiatedCapabilities.empty(),
            ),
        )
        reconnectListener.onLiveness(session, HeartbeatMonitor.State.DEGRADED)

        val replaced = AtomicBoolean(false)
        val replacement = thread(name = "publish-new-peer-generation") {
            replaced.set(
                supervisor.rememberEndpoint(
                    ParsedEndpoint.Manual("192.168.1.52", 45200),
                    ByteArray(32) { 0x52 },
                ),
            )
        }
        assertTrue(newPeerPublished.await(5, TimeUnit.SECONDS))
        val replayed = mutableListOf<ActivityEvent>()

        supervisor.replayStateTo(object : ConnectionSupervisor.Listener {
            override fun onActivityEvent(event: ActivityEvent) {
                replayed += event
            }
        })

        assertTrue(replayed.filterIsInstance<ActivityEvent.ConnectionChanged>().isEmpty())
        releaseNewPeerConnection.countDown()
        replacement.join(5_000)
        assertFalse(replacement.isAlive)
        assertTrue(replaced.get())
        session.stop()
        supervisor.stopAfterTrustChange()
    }

    @Test
    fun `peer replacement invalidates a replay snapshot waiting for delivery`() {
        val lifecycle = FakePeerLinkLifecycle(restored = restored)
        lateinit var reconnectListener: SessionReconnector.Listener
        val replaySnapshotReady = CountDownLatch(1)
        val releaseReplay = CountDownLatch(1)
        val replacementStatePublished = CountDownLatch(1)
        val releaseReplacementConnection = CountDownLatch(1)
        val blockReplacement = AtomicBoolean(false)
        val supervisor = ConnectionSupervisor(
            diagnosticLogs = LocalStructuredLogStore(),
            peerLinkLifecycle = lifecycle,
            reconnectFactory = TrustedPeerReconnectFactory { request ->
                reconnectListener = request.listener
                FakeTrustedPeerReconnectOwner()
            },
            beforeStateReplayDelivery = {
                replaySnapshotReady.countDown()
                assertTrue(releaseReplay.await(5, TimeUnit.SECONDS))
            },
            beforePeerLinkConnectionPublished = {
                if (blockReplacement.get()) {
                    replacementStatePublished.countDown()
                    assertTrue(releaseReplacementConnection.await(5, TimeUnit.SECONDS))
                }
            },
        )
        assertTrue(supervisor.restorePersistedLink())
        val session = ProtocolSession(
            socket = SSLSocketFactory.getDefault().createSocket() as javax.net.ssl.SSLSocket,
            sessionId = "s_replay_before_replacement",
            listener = object : ProtocolSession.Listener {},
        )
        reconnectListener.onSession(
            session,
            SessionHandshake.Result(
                sessionId = "s_replay_before_replacement",
                resumeToken = null,
                resumed = false,
                peerDevice = SessionHandshake.DeviceInfo("macos", "0.0.1", "Old Mac"),
                negotiatedCapabilities = SessionHandshake.NegotiatedCapabilities.empty(),
            ),
        )
        reconnectListener.onLiveness(session, HeartbeatMonitor.State.DEGRADED)
        val replayed = mutableListOf<ActivityEvent>()
        val replay = thread(name = "snapshot-old-peer-before-replacement") {
            supervisor.addListenerAndReplay(object : ConnectionSupervisor.Listener {
                override fun onActivityEvent(event: ActivityEvent) {
                    synchronized(replayed) { replayed += event }
                }
            })
        }
        assertTrue(replaySnapshotReady.await(5, TimeUnit.SECONDS))

        blockReplacement.set(true)
        val replacement = thread(name = "replace-peer-before-old-replay-delivery") {
            supervisor.rememberEndpoint(
                ParsedEndpoint.Manual("192.168.1.52", 45200),
                ByteArray(32) { 0x52 },
            )
        }
        assertTrue(replacementStatePublished.await(5, TimeUnit.SECONDS))

        releaseReplay.countDown()
        replay.join(5_000)
        assertFalse(replay.isAlive)
        assertTrue(
            "generation A connection state must be rejected after generation B owns state",
            synchronized(replayed) {
                replayed.filterIsInstance<ActivityEvent.ConnectionChanged>().isEmpty()
            },
        )

        releaseReplacementConnection.countDown()
        replacement.join(5_000)
        assertFalse(replacement.isAlive)
        val connectionEvents = synchronized(replayed) {
            replayed.filterIsInstance<ActivityEvent.ConnectionChanged>()
        }
        assertFalse(connectionEvents.any { it.state == ConnectionState.DEGRADED })
        assertEquals(ConnectionState.RECONNECTING, connectionEvents.last().state)
        session.stop()
        supervisor.stopAfterTrustChange()
    }

    @Test
    fun `peer replacement invalidates every old generation replay payload`() {
        val lifecycle = FakePeerLinkLifecycle(restored = restored)
        lateinit var ownerEvents: ProtocolOwnerEvents
        val oldConfirmation = TextLinkConfirmationRequest(
            messageId = "m_old_peer_confirmation",
            message = TextLinkMessage.text("old peer private value"),
        )
        val replaySnapshotReady = CountDownLatch(1)
        val releaseReplay = CountDownLatch(1)
        val replacementStatePublished = CountDownLatch(1)
        val releaseReplacementConnection = CountDownLatch(1)
        val supervisor = ConnectionSupervisor(
            diagnosticLogs = LocalStructuredLogStore(),
            peerLinkLifecycle = lifecycle,
            reconnectFactory = TrustedPeerReconnectFactory { FakeTrustedPeerReconnectOwner() },
            beforeStateReplayDelivery = {
                replaySnapshotReady.countDown()
                assertTrue(releaseReplay.await(5, TimeUnit.SECONDS))
            },
            beforePeerLinkConnectionPublished = {
                replacementStatePublished.countDown()
                assertTrue(releaseReplacementConnection.await(5, TimeUnit.SECONDS))
            },
            protocolOwnerFactory = { events ->
                ownerEvents = events
                object : ProtocolEnvelopeOwner {
                    override fun pendingTextConfirmations(): List<TextLinkConfirmationRequest> =
                        listOf(oldConfirmation)
                }
            },
        )
        assertTrue(supervisor.restorePersistedLink())
        ownerEvents.activity(
            ActivityEvent.TransferCompleted(
                fileId = "f_old_peer",
                filename = "old-peer-secret.bin",
                sizeBytes = 12,
                direction = dev.hyphen.android.ui.TransferDirection.INCOMING,
                verified = true,
                atMillis = 1,
            ),
        )
        val reselectField = ConnectionSupervisor::class.java
            .getDeclaredField("pendingOutboundReselect")
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val pendingReselect = reselectField.get(supervisor) as MutableMap<String, OutboundTransferRetirement>
        pendingReselect["f_old_reselect"] = OutboundTransferRetirement(
            fileId = "f_old_reselect",
            filename = "old-source.bin",
            sizeBytes = 21,
            mimeType = "application/octet-stream",
            sha256 = "0".repeat(64),
            chunkSizeBytes = 1024,
            requiresSourceReselection = true,
        )
        val replayedEvents = mutableListOf<ActivityEvent>()
        val replayedReselect = mutableListOf<Boolean>()
        val replayedConfirmations = mutableListOf<TextLinkConfirmationRequest>()
        val replay = thread(name = "snapshot-all-old-peer-payloads") {
            supervisor.addListenerAndReplay(object : ConnectionSupervisor.Listener {
                override fun onActivityEvent(event: ActivityEvent) {
                    synchronized(replayedEvents) { replayedEvents += event }
                }

                override fun onOutboundReselectRequiredChanged(required: Boolean) {
                    synchronized(replayedReselect) { replayedReselect += required }
                }

                override fun onTextConfirmation(request: TextLinkConfirmationRequest) {
                    synchronized(replayedConfirmations) { replayedConfirmations += request }
                }
            })
        }
        assertTrue(replaySnapshotReady.await(5, TimeUnit.SECONDS))

        val replacement = thread(name = "replace-peer-before-all-replay-delivery") {
            supervisor.rememberEndpoint(
                ParsedEndpoint.Manual("192.168.1.53", 45300),
                ByteArray(32) { 0x53 },
            )
        }
        assertTrue(replacementStatePublished.await(5, TimeUnit.SECONDS))

        releaseReplay.countDown()
        replay.join(5_000)
        assertFalse(replay.isAlive)
        assertTrue(
            synchronized(replayedEvents) {
                replayedEvents.none {
                    it is ActivityEvent.TransferCompleted && it.fileId == "f_old_peer"
                }
            },
        )
        assertTrue(synchronized(replayedReselect) { replayedReselect.isEmpty() })
        assertTrue(synchronized(replayedConfirmations) { replayedConfirmations.isEmpty() })

        releaseReplacementConnection.countDown()
        replacement.join(5_000)
        assertFalse(replacement.isAlive)
        supervisor.stopAfterTrustChange()
    }

    @Test
    fun `listener removal cancels a connection delivery already snapshotted behind another observer`() {
        val lifecycle = FakePeerLinkLifecycle(restored = restored)
        val supervisor = ConnectionSupervisor(
            diagnosticLogs = LocalStructuredLogStore(),
            peerLinkLifecycle = lifecycle,
            reconnectFactory = TrustedPeerReconnectFactory { FakeTrustedPeerReconnectOwner() },
        )
        assertTrue(supervisor.restorePersistedLink())
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val first = object : ConnectionSupervisor.Listener {
            override fun onActivityEvent(event: ActivityEvent) {
                if (event is ActivityEvent.ConnectionChanged) {
                    firstEntered.countDown()
                    assertTrue(releaseFirst.await(5, TimeUnit.SECONDS))
                }
            }
        }
        val secondCallbacks = AtomicInteger()
        val second = object : ConnectionSupervisor.Listener {
            override fun onActivityEvent(event: ActivityEvent) {
                if (event is ActivityEvent.ConnectionChanged) secondCallbacks.incrementAndGet()
            }
        }
        supervisor.addListener(first)
        supervisor.addListener(second)

        val pause = thread(name = "publish-behind-blocking-observer") {
            supervisor.pauseConnection()
        }
        assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
        supervisor.removeListener(second)
        releaseFirst.countDown()
        pause.join(5_000)

        assertFalse(pause.isAlive)
        assertEquals(0, secondCallbacks.get())
        supervisor.removeListener(first)
    }

    @Test
    fun `listener removal cancels every payload in an in flight state replay`() {
        val lifecycle = FakePeerLinkLifecycle(restored = restored)
        lateinit var ownerEvents: ProtocolOwnerEvents
        val replaySnapshotReady = CountDownLatch(1)
        val releaseReplay = CountDownLatch(1)
        val supervisor = ConnectionSupervisor(
            diagnosticLogs = LocalStructuredLogStore(),
            peerLinkLifecycle = lifecycle,
            reconnectFactory = TrustedPeerReconnectFactory { FakeTrustedPeerReconnectOwner() },
            beforeStateReplayDelivery = {
                replaySnapshotReady.countDown()
                assertTrue(releaseReplay.await(5, TimeUnit.SECONDS))
            },
            protocolOwnerFactory = { events ->
                ownerEvents = events
                NoOpProtocolEnvelopeOwner
            },
        )
        assertTrue(supervisor.restorePersistedLink())
        ownerEvents.activity(
            ActivityEvent.TransferCompleted(
                fileId = "f_removed_replay",
                filename = "removed.bin",
                sizeBytes = 3,
                direction = dev.hyphen.android.ui.TransferDirection.INCOMING,
                verified = true,
                atMillis = 1,
            ),
        )
        val callbacks = AtomicInteger()
        val listener = object : ConnectionSupervisor.Listener {
            override fun onActivityEvent(event: ActivityEvent) {
                callbacks.incrementAndGet()
            }

            override fun onOutboundReselectRequiredChanged(required: Boolean) {
                callbacks.incrementAndGet()
            }
        }
        supervisor.addListener(listener)
        val replay = thread(name = "remove-listener-during-replay-hook") {
            supervisor.replayStateTo(listener)
        }
        assertTrue(replaySnapshotReady.await(5, TimeUnit.SECONDS))

        supervisor.removeListener(listener)
        releaseReplay.countDown()
        replay.join(5_000)

        assertFalse(replay.isAlive)
        assertEquals(0, callbacks.get())
        supervisor.stopAfterTrustChange()
    }

    @Test
    fun `trust retirement cannot replay an old pending text confirmation`() {
        val lifecycle = FakePeerLinkLifecycle(restored = restored)
        val owner = BlockingStopReconnectOwner()
        val oldConfirmation = TextLinkConfirmationRequest(
            messageId = "m_retired_confirmation",
            message = TextLinkMessage.text("retired peer private value"),
        )
        val supervisor = ConnectionSupervisor(
            diagnosticLogs = LocalStructuredLogStore(),
            peerLinkLifecycle = lifecycle,
            reconnectFactory = TrustedPeerReconnectFactory { owner },
            protocolOwnerFactory = {
                object : ProtocolEnvelopeOwner {
                    override fun pendingTextConfirmations(): List<TextLinkConfirmationRequest> =
                        listOf(oldConfirmation)
                }
            },
        )
        assertTrue(supervisor.restorePersistedLink())
        val stop = thread(name = "retire-trust-before-protocol-owner") {
            supervisor.stopAfterTrustChange()
        }
        assertTrue(owner.stopEntered.await(5, TimeUnit.SECONDS))
        val replayed = mutableListOf<TextLinkConfirmationRequest>()

        supervisor.replayStateTo(object : ConnectionSupervisor.Listener {
            override fun onTextConfirmation(request: TextLinkConfirmationRequest) {
                replayed += request
            }
        })

        assertTrue(replayed.isEmpty())
        owner.releaseStop.countDown()
        stop.join(5_000)
        assertFalse(stop.isAlive)
    }

    @Test
    fun `throwing peer replay callback cannot suppress connection state`() {
        val lifecycle = FakePeerLinkLifecycle(restored = restored)
        val supervisor = ConnectionSupervisor(
            diagnosticLogs = LocalStructuredLogStore(),
            peerLinkLifecycle = lifecycle,
            reconnectFactory = TrustedPeerReconnectFactory { FakeTrustedPeerReconnectOwner() },
        )
        assertTrue(supervisor.restorePersistedLink())
        val states = mutableListOf<ConnectionState>()
        val listener = object : ConnectionSupervisor.Listener {
            override fun onActivityEvent(event: ActivityEvent) {
                when (event) {
                    is ActivityEvent.PeerChanged -> error("peer observer failed")
                    is ActivityEvent.ConnectionChanged -> states += event.state
                    else -> Unit
                }
            }
        }

        supervisor.replayStateTo(listener)

        assertEquals(listOf(ConnectionState.RECONNECTING), states)
        supervisor.stopAfterTrustChange()
    }

    @Test
    fun `failed connection replay can retry the same sequence`() {
        val lifecycle = FakePeerLinkLifecycle(restored = restored)
        val supervisor = ConnectionSupervisor(
            diagnosticLogs = LocalStructuredLogStore(),
            peerLinkLifecycle = lifecycle,
            reconnectFactory = TrustedPeerReconnectFactory { FakeTrustedPeerReconnectOwner() },
        )
        assertTrue(supervisor.restorePersistedLink())
        val failOnce = AtomicBoolean(true)
        val states = mutableListOf<ConnectionState>()
        val listener = object : ConnectionSupervisor.Listener {
            override fun onActivityEvent(event: ActivityEvent) {
                if (event is ActivityEvent.ConnectionChanged) {
                    if (failOnce.compareAndSet(true, false)) error("transient observer failure")
                    states += event.state
                }
            }
        }
        supervisor.addListener(listener)

        supervisor.replayStateTo(listener)
        supervisor.replayStateTo(listener)

        assertEquals(listOf(ConnectionState.RECONNECTING), states)
        supervisor.removeListener(listener)
        supervisor.stopAfterTrustChange()
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
