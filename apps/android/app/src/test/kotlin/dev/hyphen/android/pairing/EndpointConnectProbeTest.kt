package dev.hyphen.android.pairing

import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointConnectProbeTest {

    @Test
    fun `connects to a live listener`() {
        ServerSocket(0).use { server ->
            val result = EndpointConnectProbe().probe("127.0.0.1", server.localPort)
            assertEquals(
                EndpointConnectProbe.Result.Connected("127.0.0.1", server.localPort),
                result,
            )
        }
    }

    @Test
    fun `fails fast against a closed port`() {
        // Bind then close to get a port that is very likely unused.
        val deadPort = ServerSocket(0).use { it.localPort }
        val result = EndpointConnectProbe(timeoutMillis = 2_000).probe("127.0.0.1", deadPort)
        assertTrue("expected Failed, got $result", result is EndpointConnectProbe.Result.Failed)
    }

    @Test
    fun `probe accepts a parsed endpoint`() {
        ServerSocket(0).use { server ->
            val parsed = EndpointParser.parseManual("127.0.0.1:${server.localPort}")
            val endpoint = (parsed as ParseResult.Ok).endpoint
            val result = EndpointConnectProbe().probe(endpoint)
            assertTrue(result is EndpointConnectProbe.Result.Connected)
        }
    }
}
