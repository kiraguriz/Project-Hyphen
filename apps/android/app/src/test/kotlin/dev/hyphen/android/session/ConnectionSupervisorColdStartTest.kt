package dev.hyphen.android.session

import dev.hyphen.android.diagnostics.LocalStructuredLogStore
import dev.hyphen.android.pairing.ParsedEndpoint
import dev.hyphen.android.transport.ProtocolSession
import dev.hyphen.android.transport.SessionHandshake
import dev.hyphen.android.transport.SessionReconnector
import dev.hyphen.android.trust.TrustedPeer
import dev.hyphen.android.ui.ActivityEvent
import dev.hyphen.android.ui.ConnectionState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakePeerLinkLifecycle(
    var restored: RestoredPeerLink? = null,
    var restoreFailure: RuntimeException? = null,
) : PeerLinkLifecycle {
    var invalidations = 0
    val remembered = mutableListOf<Pair<ParsedEndpoint, ByteArray>>()

    override fun remember(endpoint: ParsedEndpoint, peerFingerprint: ByteArray) {
        remembered += endpoint to peerFingerprint.copyOf()
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

    override fun restoreTrusted(): RestoredPeerLink? = null

    override fun invalidate() {
        stored.set(false)
        invalidations.incrementAndGet()
    }
}

class ConnectionSupervisorColdStartTest {
    private val fingerprint = ByteArray(32) { 0x31 }
    private val restored = RestoredPeerLink(
        endpoint = ParsedEndpoint.Manual("192.168.1.31", 43123),
        peer = TrustedPeer(fingerprint, "Studio Mac", 123L),
    )

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
}
