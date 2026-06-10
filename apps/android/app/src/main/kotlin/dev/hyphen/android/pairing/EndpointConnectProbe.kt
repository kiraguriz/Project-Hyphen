package dev.hyphen.android.pairing

import java.net.InetSocketAddress
import java.net.Socket

/**
 * Plain-TCP reachability probe for the mDNS-disabled fallback path
 * (HYP-M1-006). Proves "a Hyphen peer endpoint is reachable" before the
 * TLS session work lands in M2 — it never sends or trusts any data.
 */
class EndpointConnectProbe(private val timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS) {

    sealed class Result {
        data class Connected(val host: String, val port: Int) : Result()
        data class Failed(val reason: String) : Result()
    }

    /** Blocking; callers off the main thread. */
    fun probe(endpoint: ParsedEndpoint): Result = probe(endpoint.host, endpoint.port)

    fun probe(host: String, port: Int): Result =
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMillis)
            }
            Result.Connected(host, port)
        } catch (e: Exception) {
            // Reason string carries the exception class, not addresses
            // beyond what the user typed (threat model §8 logging rule).
            Result.Failed(e.javaClass.simpleName)
        }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 5_000
    }
}
