package dev.hyphen.android.transport

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Integration: two ProtocolSessions over real mutual-TLS loopback
 * (HYP-M2-012). Intervals are shrunk (80 ms) so degraded transitions are
 * observable in test time; thresholds and semantics match protocol §4.
 */
class ProtocolSessionTest {

    private lateinit var serverIdentity: TlsIdentity
    private lateinit var clientIdentity: TlsIdentity
    private var server: TlsServer? = null
    private val sessions = mutableListOf<ProtocolSession>()

    private val fast = ProtocolSession.Config(
        heartbeatIntervalMs = 80,
        ackTimeoutMs = 300,
    )

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
        sessions.forEach { it.stop() }
        sessions.clear()
        server?.stop()
        server = null
    }

    private class RecordingListener : ProtocolSession.Listener {
        val envelopes = mutableListOf<Envelope>()
        val degraded = CountDownLatch(1)
        val recovered = CountDownLatch(1)
        val ackTimeout = AtomicReference<String?>(null)
        val ackTimeoutLatch = CountDownLatch(1)
        val protocolError = AtomicReference<String?>(null)
        val protocolErrorLatch = CountDownLatch(1)
        val envelopeLatch = CountDownLatch(1)

        override fun onEnvelope(envelope: Envelope) {
            synchronized(envelopes) { envelopes.add(envelope) }
            envelopeLatch.countDown()
        }

        override fun onLiveness(state: HeartbeatMonitor.State) {
            when (state) {
                HeartbeatMonitor.State.DEGRADED -> degraded.countDown()
                HeartbeatMonitor.State.HEALTHY -> recovered.countDown()
            }
        }

        override fun onAckTimeout(messageId: String) {
            ackTimeout.set(messageId)
            ackTimeoutLatch.countDown()
        }

        override fun onProtocolError(code: String, detail: String) {
            protocolError.set(code)
            protocolErrorLatch.countDown()
        }
    }

    private var serverPort = 0

    /** Builds a connected session pair; configs per side. */
    private fun connectedPair(
        serverConfig: ProtocolSession.Config,
        clientConfig: ProtocolSession.Config,
        serverListener: RecordingListener,
        clientListener: RecordingListener,
    ): Pair<ProtocolSession, ProtocolSession> {
        val serverSessionRef = AtomicReference<ProtocolSession?>(null)
        val serverReady = CountDownLatch(1)
        val tlsServer = TlsServer(serverIdentity, isTrusted = { it.contentEquals(clientIdentity.spkiFingerprint) })
        server = tlsServer
        val port = tlsServer.start { socket ->
            val session = ProtocolSession(socket, "s_test1", serverConfig, serverListener)
            serverSessionRef.set(session)
            session.start()
            serverReady.countDown()
        }
        val clientSocket: SSLSocket = TlsClient.connect(
            host = "127.0.0.1",
            port = port,
            identity = clientIdentity,
            isTrusted = { it.contentEquals(serverIdentity.spkiFingerprint) },
        )
        val clientSession = ProtocolSession(clientSocket, "s_test1", clientConfig, clientListener)
        clientSession.start()
        assertTrue("server session never started", serverReady.await(5, TimeUnit.SECONDS))
        serverPort = port
        val serverSession = serverSessionRef.get()!!
        sessions += listOf(serverSession, clientSession)
        return serverSession to clientSession
    }

    @Test
    fun `heartbeats keep both sides healthy`() {
        val serverListener = RecordingListener()
        val clientListener = RecordingListener()
        connectedPair(fast, fast, serverListener, clientListener)
        // 6+ intervals of wall time: any missing heartbeat flow would trip
        // the watchdog (>2 intervals of silence) on that side.
        assertTrue(!serverListener.degraded.await(500, TimeUnit.MILLISECONDS))
        assertTrue(!clientListener.degraded.await(1, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `silent peer transitions to degraded and recovers on traffic`() {
        val serverListener = RecordingListener()
        val clientListener = RecordingListener()
        // Server heartbeats only every 60s: silent from the client's view.
        val silent = fast.copy(heartbeatIntervalMs = 60_000)
        val pair = connectedPair(silent, fast, serverListener, clientListener)

        assertTrue(
            "client must degrade after >2 intervals of silence",
            clientListener.degraded.await(3, TimeUnit.SECONDS),
        )
        // Any envelope from the server recovers the client.
        pair.first.send(Envelope.TYPE_HEARTBEAT)
        assertTrue(
            "client must recover when traffic resumes",
            clientListener.recovered.await(3, TimeUnit.SECONDS),
        )
    }

    @Test
    fun `requiresAck envelope is delivered and acked`() {
        val serverListener = RecordingListener()
        val clientListener = RecordingListener()
        val pair = connectedPair(fast, fast, serverListener, clientListener)

        pair.second.send("text.send", Json.obj("kind" to Json.Str("text")), requiresAck = true, capability = "text.v1")
        assertTrue("server must receive the envelope", serverListener.envelopeLatch.await(3, TimeUnit.SECONDS))
        assertEquals("text.send", serverListener.envelopes.first().type)
        // Ack must arrive well within 4x the timeout — no timeout event.
        assertTrue(!clientListener.ackTimeoutLatch.await(2L * fast.ackTimeoutMs, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `missing ack surfaces as ack timeout`() {
        val serverListener = RecordingListener()
        val clientListener = RecordingListener()
        val pair = connectedPair(fast.copy(autoAck = false), fast, serverListener, clientListener)

        val messageId = pair.second.send("text.send", requiresAck = true, capability = "text.v1")
        assertTrue(
            "ack timeout must fire against a silent-acker",
            clientListener.ackTimeoutLatch.await(3, TimeUnit.SECONDS),
        )
        assertEquals(messageId, clientListener.ackTimeout.get())
    }

    @Test
    fun `oversized frame surfaces frame-too-large`() {
        val serverListener = RecordingListener()
        val clientListener = RecordingListener()
        connectedPair(fast, fast, serverListener, clientListener)

        // Bypass the session on a second connection: write a hostile 5 MiB
        // length header raw. The accept loop builds a server session for
        // it (shared listener), whose reader must flag the frame.
        val socket = TlsClient.connect(
            host = "127.0.0.1",
            port = serverPort,
            identity = clientIdentity,
            isTrusted = { it.contentEquals(serverIdentity.spkiFingerprint) },
        )
        val fiveMiB = 5 * 1024 * 1024
        socket.outputStream.write(
            byteArrayOf(
                (fiveMiB ushr 24).toByte(), (fiveMiB ushr 16).toByte(),
                (fiveMiB ushr 8).toByte(), fiveMiB.toByte(),
            )
        )
        socket.outputStream.flush()
        assertTrue(
            "server must flag the oversized frame",
            serverListener.protocolErrorLatch.await(3, TimeUnit.SECONDS),
        )
        assertEquals("transport/frame-too-large", serverListener.protocolError.get())
        runCatching { socket.close() }
    }
}
