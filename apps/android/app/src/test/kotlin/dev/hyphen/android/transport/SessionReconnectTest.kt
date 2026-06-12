package dev.hyphen.android.transport

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.HandshakeCompletedListener
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
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

    @Test
    fun `handshake failure closes the dialed socket`() {
        val socket = TrackingSocket(input = ByteArrayInputStream(ByteArray(0)))
        val scheduler = OneShotScheduler()

        reconnector = SessionReconnector(
            dial = { socket },
            device = device,
            scheduler = scheduler,
            listener = object : SessionReconnector.Listener {
                override fun onSession(session: ProtocolSession, handshake: SessionHandshake.Result) = Unit
            },
        ).also { it.start() }

        assertTrue("handshake failure must close the socket", socket.closed.get())
    }

    @Test
    fun `stop racing successful handshake closes socket before session assignment`() {
        val releaseReply = CountDownLatch(1)
        val dialed = CountDownLatch(1)
        val socket = TrackingSocket(input = ReleasableInputStream(helloReplyFrame(), releaseReply))
        val onSession = CountDownLatch(1)

        reconnector = SessionReconnector(
            dial = {
                dialed.countDown()
                socket
            },
            device = device,
            scheduler = ImmediateScheduler(),
            listener = object : SessionReconnector.Listener {
                override fun onSession(session: ProtocolSession, handshake: SessionHandshake.Result) {
                    onSession.countDown()
                }
            },
        ).also { it.start() }

        assertTrue(dialed.await(5, TimeUnit.SECONDS))
        reconnector?.stop()
        releaseReply.countDown()

        assertFalse("stopped reconnector must not publish a session", onSession.await(500, TimeUnit.MILLISECONDS))
        assertTrue("socket must be closed after stop wins the handshake race", socket.closed.get())
    }

    @Test
    fun `hello rejects bad device kind and invalid transfer max chunk bytes`() {
        val badKind = runCatching {
            SessionHandshake.initiate(
                TrackingSocket(input = ByteArrayInputStream(helloReplyFrame(deviceKind = "ios"))),
                device,
                resumeToken = null,
                previousSessionId = null,
            )
        }.exceptionOrNull()
        assertTrue(badKind is SessionHandshake.HandshakeException)

        val badChunk = runCatching {
            SessionHandshake.initiate(
                TrackingSocket(
                    input = ByteArrayInputStream(
                        helloReplyFrame(
                            capabilities = Json.obj(
                                "transfer.v1" to Json.obj(
                                    "resume" to Json.Bool(true),
                                    "maxChunkBytes" to Json.Num("512"),
                                ),
                            ),
                        ),
                    ),
                ),
                device,
                resumeToken = null,
                previousSessionId = null,
            )
        }.exceptionOrNull()
        assertTrue(badChunk is SessionHandshake.HandshakeException)
    }

    @Test
    fun `hello negotiates transfer max chunk bytes as the local and peer minimum`() {
        val result = SessionHandshake.initiate(
            TrackingSocket(
                input = ByteArrayInputStream(
                    helloReplyFrame(
                        capabilities = Json.obj(
                            "transfer.v1" to Json.obj(
                                "resume" to Json.Bool(true),
                                "maxChunkBytes" to Json.Num("2048"),
                            ),
                        ),
                    ),
                ),
            ),
            device,
            resumeToken = null,
            previousSessionId = null,
        )

        assertEquals(2048, result.negotiatedCapabilities.transferMaxChunkBytes())
    }

    private class OneShotScheduler : SessionReconnector.RetryScheduler {
        private var used = false

        override fun schedule(delayMs: Long, action: () -> Unit) {
            if (used) return
            used = true
            action()
        }
    }

    private class ReleasableInputStream(
        private val bytes: ByteArray,
        private val release: CountDownLatch,
    ) : InputStream() {
        private var index = 0

        override fun read(): Int {
            release.await(5, TimeUnit.SECONDS)
            if (index >= bytes.size) return -1
            return bytes[index++].toInt() and 0xff
        }
    }

    private class TrackingSocket(
        private val input: InputStream,
        private val output: OutputStream = ByteArrayOutputStream(),
    ) : SSLSocket() {
        val closed = AtomicBoolean(false)
        private var soTimeoutValue = 0

        override fun getInputStream(): InputStream = input
        override fun getOutputStream(): OutputStream = output
        override fun close() {
            closed.set(true)
        }

        override fun setSoTimeout(timeout: Int) {
            soTimeoutValue = timeout
        }

        override fun getSoTimeout(): Int = soTimeoutValue
        override fun getSupportedCipherSuites(): Array<String> = emptyArray()
        override fun getEnabledCipherSuites(): Array<String> = emptyArray()
        override fun setEnabledCipherSuites(suites: Array<out String>?) = Unit
        override fun getSupportedProtocols(): Array<String> = emptyArray()
        override fun getEnabledProtocols(): Array<String> = emptyArray()
        override fun setEnabledProtocols(protocols: Array<out String>?) = Unit
        override fun getSession(): SSLSession = SSLContext.getDefault().createSSLEngine().session
        override fun addHandshakeCompletedListener(listener: HandshakeCompletedListener?) = Unit
        override fun removeHandshakeCompletedListener(listener: HandshakeCompletedListener?) = Unit
        override fun startHandshake() = Unit
        override fun setUseClientMode(mode: Boolean) = Unit
        override fun getUseClientMode(): Boolean = true
        override fun setNeedClientAuth(need: Boolean) = Unit
        override fun getNeedClientAuth(): Boolean = false
        override fun setWantClientAuth(want: Boolean) = Unit
        override fun getWantClientAuth(): Boolean = false
        override fun setEnableSessionCreation(flag: Boolean) = Unit
        override fun getEnableSessionCreation(): Boolean = true
        override fun connect(endpoint: SocketAddress?) = Unit
        override fun connect(endpoint: SocketAddress?, timeout: Int) = Unit
        override fun bind(bindpoint: SocketAddress?) = Unit
        override fun getInetAddress(): InetAddress? = null
        override fun getLocalAddress(): InetAddress? = null
        override fun getPort(): Int = 0
        override fun getLocalPort(): Int = 0
        override fun getRemoteSocketAddress(): SocketAddress? = null
        override fun getLocalSocketAddress(): SocketAddress? = null
    }

    private fun helloReplyFrame(
        deviceKind: String = "macos",
        appVersion: String = "0.0.1",
        capabilities: Json.Obj = Json.obj(
            "notifications.v1" to Json.obj("reply" to Json.Str("beta"), "dismiss" to Json.Bool(true)),
            "transfer.v1" to Json.obj("resume" to Json.Bool(true), "maxChunkBytes" to Json.Num("1048576")),
            "text.v1" to Json.obj("direction" to Json.Str("bidirectional")),
            "diagnostics.v1" to Json.obj("redactedExport" to Json.Bool(true)),
        ),
    ): ByteArray {
        val reply = Envelope(
            messageId = Ulid.generate(),
            sessionId = "s_test1",
            type = Envelope.TYPE_HELLO,
            seq = 1,
            sentAtUnixMs = System.currentTimeMillis(),
            requiresAck = false,
            payload = Json.obj(
                "device" to Json.obj(
                    "kind" to Json.Str(deviceKind),
                    "appVersion" to Json.Str(appVersion),
                    "deviceName" to Json.Str("Test Mac"),
                ),
                "resumeToken" to Json.Str("resume-token-test"),
                "capabilities" to capabilities,
            ),
        ).encode()
        return ByteBuffer.allocate(4 + reply.size)
            .putInt(reply.size)
            .put(reply)
            .array()
    }
}
