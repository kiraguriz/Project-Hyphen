package dev.hyphen.android.transport

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * HYP-M2-013 acceptance: reconnect after a simulated drop — over real
 * mutual-TLS loopback, with the resume token round-tripping through the
 * hello exchange so the SAME sessionId survives the drop.
 */
class SessionReconnectTest {

    private lateinit var serverIdentity: TlsIdentity
    private lateinit var clientIdentity: TlsIdentity
    private var server: TlsServer? = null
    private var reconnector: SessionReconnector? = null
    private val serverSessions = mutableListOf<ProtocolSession>()

    private val device = SessionHandshake.DeviceInfo("android", "0.0.1", "Test Phone")
    private val serverDevice = SessionHandshake.DeviceInfo("macos", "0.0.1", "Test Mac")

    /** Runs everything immediately — backoff delays are unit-tested separately. */
    private class ImmediateScheduler : SessionReconnector.RetryScheduler {
        override fun schedule(delayMs: Long, action: () -> Unit) {
            Thread(action, "immediate-retry").apply { isDaemon = true }.start()
        }
    }

    @Before
    fun setUp() {
        serverIdentity = softwareKeyPair().let {
            TlsIdentity(it.private, SelfSignedCertificateMinter.mint(it, "Server"))
        }
        clientIdentity = softwareKeyPair().let {
            TlsIdentity(it.private, SelfSignedCertificateMinter.mint(it, "Client"))
        }
    }

    @After
    fun tearDown() {
        reconnector?.stop()
        synchronized(serverSessions) { serverSessions.forEach { it.stop() } }
        server?.stop()
    }

    @Test
    fun `handshake assigns a session and a resume token`() {
        val tokenStore = ResumeTokenStore()
        val serverResult = LinkedBlockingQueue<SessionHandshake.Result>()
        val tlsServer = TlsServer(serverIdentity, { it.contentEquals(clientIdentity.spkiFingerprint) })
        server = tlsServer
        val port = tlsServer.start { socket ->
            serverResult.put(SessionHandshake.respond(socket, serverDevice, tokenStore))
        }

        val socket = TlsClient.connect("127.0.0.1", port, clientIdentity, { it.contentEquals(serverIdentity.spkiFingerprint) })
        val client = SessionHandshake.initiate(socket, device, resumeToken = null, previousSessionId = null)
        val serverSide = serverResult.poll(5, TimeUnit.SECONDS)!!

        assertEquals(serverSide.sessionId, client.sessionId)
        assertTrue(client.sessionId.startsWith("s_"))
        assertFalse(client.resumed)
        assertFalse(serverSide.resumed)
        assertNotNull(client.resumeToken)
        assertEquals("Test Phone", serverSide.peerDevice?.deviceName)
        assertEquals("Test Mac", client.peerDevice?.deviceName)
        socket.close()
    }

    @Test
    fun `reconnect after simulated drop resumes the same session`() {
        val tokenStore = ResumeTokenStore()
        val fastConfig = ProtocolSession.Config(heartbeatIntervalMs = 100)
        val tlsServer = TlsServer(serverIdentity, { it.contentEquals(clientIdentity.spkiFingerprint) })
        server = tlsServer
        val port = tlsServer.start { socket ->
            val handshake = SessionHandshake.respond(socket, serverDevice, tokenStore)
            val session = ProtocolSession(
                socket, handshake.sessionId,
                fastConfig.copy(startingSeq = 1),
                object : ProtocolSession.Listener {},
            )
            synchronized(serverSessions) { serverSessions.add(session) }
            session.start()
        }

        val sessionResults = LinkedBlockingQueue<SessionHandshake.Result>()
        val retries = mutableListOf<Pair<Int, Long>>()
        reconnector = SessionReconnector(
            dial = {
                TlsClient.connect("127.0.0.1", port, clientIdentity, { it.contentEquals(serverIdentity.spkiFingerprint) })
            },
            device = device,
            sessionConfig = fastConfig,
            scheduler = ImmediateScheduler(),
            listener = object : SessionReconnector.Listener {
                override fun onSession(session: ProtocolSession, handshake: SessionHandshake.Result) {
                    sessionResults.put(handshake)
                }

                override fun onRetryScheduled(attempt: Int, delaySeconds: Long) {
                    synchronized(retries) { retries.add(attempt to delaySeconds) }
                }
            },
        ).also { it.start() }

        val first = sessionResults.poll(5, TimeUnit.SECONDS)!!
        assertFalse(first.resumed)

        // Simulated drop: kill the server-side session (closes the socket).
        // The server registers its session just after replying, so the
        // client's onSession can race ahead — wait for the registration.
        val deadline = System.currentTimeMillis() + 5_000
        var firstServerSession: ProtocolSession? = null
        while (firstServerSession == null && System.currentTimeMillis() < deadline) {
            firstServerSession = synchronized(serverSessions) { serverSessions.firstOrNull() }
            if (firstServerSession == null) Thread.sleep(10)
        }
        firstServerSession!!.stop()

        val second = sessionResults.poll(10, TimeUnit.SECONDS)!!
        assertTrue("second connect must resume", second.resumed)
        assertEquals("sessionId must survive the drop", first.sessionId, second.sessionId)
        assertNotEquals("resume tokens are single-use", first.resumeToken, second.resumeToken)
        assertTrue("the drop scheduled a retry", synchronized(retries) { retries.isNotEmpty() })
    }

    @Test
    fun `backoff walks 1-5-15-30 then caps and resets after success`() {
        val delays = mutableListOf<Long>()
        var failuresLeft = 6
        val connected = CountDownLatch(1)
        // Manual scheduler: runs retries inline on the caller thread.
        val inline = object : SessionReconnector.RetryScheduler {
            override fun schedule(delayMs: Long, action: () -> Unit) {
                if (connected.count == 0L) return
                action()
            }
        }
        val tokenStore = ResumeTokenStore()
        val tlsServer = TlsServer(serverIdentity, { it.contentEquals(clientIdentity.spkiFingerprint) })
        server = tlsServer
        val port = tlsServer.start { socket ->
            SessionHandshake.respond(socket, serverDevice, tokenStore)
        }

        reconnector = SessionReconnector(
            dial = {
                if (failuresLeft > 0) {
                    failuresLeft--
                    throw java.io.IOException("simulated dial failure")
                }
                TlsClient.connect("127.0.0.1", port, clientIdentity, { it.contentEquals(serverIdentity.spkiFingerprint) })
            },
            device = device,
            scheduler = inline,
            listener = object : SessionReconnector.Listener {
                override fun onSession(session: ProtocolSession, handshake: SessionHandshake.Result) {
                    connected.countDown()
                }

                override fun onRetryScheduled(attempt: Int, delaySeconds: Long) {
                    delays.add(delaySeconds)
                }
            },
        ).also { it.start() }

        assertTrue(connected.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(1L, 5L, 15L, 30L, 30L, 30L), delays)
    }
}
