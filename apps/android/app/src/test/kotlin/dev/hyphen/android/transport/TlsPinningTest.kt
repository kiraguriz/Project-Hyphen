package dev.hyphen.android.transport

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Real mutual-TLS handshakes over loopback (JVM JSSE): both sides present
 * minted identities and verify each other purely by SPKI pin — the same
 * stack Android uses, with software keys instead of Keystore ones.
 */
class TlsPinningTest {

    private lateinit var serverIdentity: TlsIdentity
    private lateinit var clientIdentity: TlsIdentity
    private var server: TlsServer? = null

    @Before
    fun setUp() {
        serverIdentity = softwareKeyPair().let {
            TlsIdentity(it.private, SelfSignedCertificateMinter.mint(it, "Hyphen Test Android"))
        }
        clientIdentity = softwareKeyPair().let {
            TlsIdentity(it.private, SelfSignedCertificateMinter.mint(it, "Hyphen Test Mac"))
        }
    }

    @After
    fun tearDown() {
        server?.stop()
        server = null
    }

    @Test
    fun `handshake and echo with matching pins on TLS 1_3`() {
        val tlsServer = TlsServer(serverIdentity, isTrusted = { it.contentEquals(clientIdentity.spkiFingerprint) })
        server = tlsServer
        val port = tlsServer.start { socket ->
            val byte = socket.inputStream.read()
            socket.outputStream.write(byte)
            socket.outputStream.flush()
        }

        val socket: SSLSocket = TlsClient.connect(
            host = "127.0.0.1",
            port = port,
            identity = clientIdentity,
            isTrusted = { it.contentEquals(serverIdentity.spkiFingerprint) },
        )
        socket.use {
            assertEquals("TLSv1.3", it.session.protocol)
            it.outputStream.write('h'.code)
            it.outputStream.flush()
            assertEquals('h'.code, it.inputStream.read())
        }
    }

    @Test
    fun `client rejects wrong server pin`() {
        val tlsServer = TlsServer(serverIdentity, isTrusted = { true })
        server = tlsServer
        val port = tlsServer.start { }

        assertThrows(SSLException::class.java) {
            TlsClient.connect(
                host = "127.0.0.1",
                port = port,
                identity = clientIdentity,
                isTrusted = { it.contentEquals(ByteArray(32) { 0xEE.toByte() }) },
            )
        }
    }

    @Test
    fun `server never surfaces an unpinned client`() {
        val accepted = CountDownLatch(1)
        val tlsServer = TlsServer(serverIdentity, isTrusted = { it.contentEquals(ByteArray(32) { 0xEE.toByte() }) })
        server = tlsServer
        val port = tlsServer.start { accepted.countDown() }

        // TLS 1.3 lets the client finish before the server judges its
        // certificate, so the failure may surface at connect OR first read.
        try {
            val socket = TlsClient.connect(
                host = "127.0.0.1",
                port = port,
                identity = clientIdentity,
                isTrusted = { it.contentEquals(serverIdentity.spkiFingerprint) },
            )
            socket.use { it.inputStream.read() }
        } catch (_: Exception) {
            // expected: alert from the server kills the connection
        }

        assertTrue(
            "server must not hand an unpinned client to the app",
            !accepted.await(2, TimeUnit.SECONDS),
        )
    }

    @Test
    fun `server keeps accepting after a rejected client`() {
        val tlsServer = TlsServer(serverIdentity, isTrusted = { it.contentEquals(clientIdentity.spkiFingerprint) })
        server = tlsServer
        val port = tlsServer.start { socket ->
            socket.outputStream.write('k'.code)
            socket.outputStream.flush()
        }

        val strangerIdentity = softwareKeyPair().let {
            TlsIdentity(it.private, SelfSignedCertificateMinter.mint(it, "Stranger"))
        }
        try {
            TlsClient.connect("127.0.0.1", port, strangerIdentity, isTrusted = { true })
                .use { it.inputStream.read() }
        } catch (_: Exception) {
            // stranger rejected — the accept loop must survive this
        }

        TlsClient.connect(
            host = "127.0.0.1",
            port = port,
            identity = clientIdentity,
            isTrusted = { it.contentEquals(serverIdentity.spkiFingerprint) },
        ).use {
            assertEquals('k'.code, it.inputStream.read())
        }
    }

    @Test
    fun `trust manager rejects directly`() {
        val manager = PinnedTrustManager { it.contentEquals(clientIdentity.spkiFingerprint) }
        // Accept path: no throw.
        manager.checkClientTrusted(arrayOf(clientIdentity.certificate), "EC")
        // Reject paths: wrong peer and empty chain.
        assertThrows(java.security.cert.CertificateException::class.java) {
            manager.checkClientTrusted(arrayOf(serverIdentity.certificate), "EC")
        }
        assertThrows(java.security.cert.CertificateException::class.java) {
            manager.checkServerTrusted(emptyArray(), "EC")
        }
        assertEquals(0, manager.acceptedIssuers.size)
    }
}
