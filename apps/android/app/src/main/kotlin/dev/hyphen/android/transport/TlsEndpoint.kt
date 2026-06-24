package dev.hyphen.android.transport

import java.io.IOException
import java.net.InetSocketAddress
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * Mutual-TLS endpoints with SPKI pinning (HYP-M2-008; twin of macOS
 * `TLSEndpointListener`/`TLSConnector`). TLS 1.3 only, per protocol v0
 * §2 — on Android this needs API 29+. ADR-0008 resolved the floor by
 * raising `minSdk` to 29 (route A); API 26–28, where
 * `SSLContext.getInstance("TLSv1.3")` throws, are no longer installable,
 * so this layer never silently downgrades to TLS 1.2. Framing and
 * session logic land with HYP-M2-012; this layer only produces
 * authenticated sockets.
 */
object HyphenTls {

    const val PROTOCOL = "TLSv1.3"

    fun context(identity: TlsIdentity, isTrusted: (ByteArray) -> Boolean): SSLContext =
        SSLContext.getInstance(PROTOCOL).apply {
            init(
                arrayOf(SingleIdentityKeyManager(identity)),
                arrayOf(PinnedTrustManager(isTrusted)),
                SecureRandom(),
            )
        }
}

/**
 * Accepts pinned-peer TLS connections. Sockets surface only after the
 * handshake — including the client-certificate pin check — completes.
 */
class TlsServer(
    private val identity: TlsIdentity,
    private val isTrusted: (ByteArray) -> Boolean,
    private val handshakeTimeoutMillis: Int = 5_000,
) {
    private var serverSocket: SSLServerSocket? = null

    /** @param port 0 picks an ephemeral port. @return the bound port. */
    @Synchronized
    fun start(port: Int = 0, onConnection: (SSLSocket) -> Unit): Int {
        check(serverSocket == null) { "server already started" }
        val server = HyphenTls.context(identity, isTrusted)
            .serverSocketFactory.createServerSocket(port) as SSLServerSocket
        server.needClientAuth = true
        server.enabledProtocols = arrayOf(HyphenTls.PROTOCOL)
        serverSocket = server

        thread(isDaemon = true, name = "hyphen-tls-accept") {
            while (true) {
                val socket = try {
                    server.accept() as SSLSocket
                } catch (_: IOException) {
                    return@thread // closed by stop()
                }
                thread(isDaemon = true, name = "hyphen-tls-handshake") {
                    try {
                        socket.soTimeout = handshakeTimeoutMillis
                        socket.startHandshake()
                        socket.soTimeout = 0
                        onConnection(socket)
                    } catch (_: IOException) {
                        // Handshake rejection (e.g. unpinned client) closes
                        // this socket only; the server keeps accepting.
                        runCatching { socket.close() }
                    }
                }
            }
        }
        return server.localPort
    }

    @Synchronized
    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
    }
}

/**
 * Dials a pinned peer; one shot — retries belong to the reconnect state
 * machine. Returns the socket after the authenticated handshake, or
 * throws ([javax.net.ssl.SSLHandshakeException] on pin rejection).
 */
object TlsClient {

    fun connect(
        host: String,
        port: Int,
        identity: TlsIdentity,
        isTrusted: (ByteArray) -> Boolean,
        timeoutMillis: Int = 5_000,
    ): SSLSocket {
        val socket = HyphenTls.context(identity, isTrusted)
            .socketFactory.createSocket() as SSLSocket
        try {
            socket.connect(InetSocketAddress(host, port), timeoutMillis)
            socket.soTimeout = timeoutMillis
            socket.enabledProtocols = arrayOf(HyphenTls.PROTOCOL)
            socket.startHandshake()
            return socket
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }
}
