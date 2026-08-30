package dev.hyphen.android.transport

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanEndpointPolicyTest {
    @Test
    fun `syntax admits only private literals loopback and dot local names`() {
        listOf("10.1.2.3", "172.31.255.1", "192.168.0.8", "169.254.2.3", "127.0.0.1", "mac.local")
            .forEach { assertTrue(it, LanEndpointPolicy.isAllowedHostSyntax(it)) }

        listOf(
            "8.8.8.8",
            "93.184.216.34",
            "example.com",
            "local.evil.example",
            "999.1.1.1",
            "mác.local",
            "mac。local",
        )
            .forEach { assertFalse(it, LanEndpointPolicy.isAllowedHostSyntax(it)) }
    }

    @Test
    fun `resolved selection pins an allowed address and never returns a public answer`() {
        val public = InetAddress.getByName("93.184.216.34")
        val private = InetAddress.getByName("192.168.50.4")

        assertEquals(
            private,
            LanEndpointPolicy.selectResolvedAddress("mac.local", arrayOf(public, private)),
        )
        assertEquals(
            null,
            LanEndpointPolicy.selectResolvedAddress("mac.local", arrayOf(public)),
        )
    }

    @Test
    fun `runtime address policy includes ipv6 ula and excludes global and multicast`() {
        assertTrue(LanEndpointPolicy.isAllowedAddress(InetAddress.getByName("fd12:3456::1")))
        assertTrue(LanEndpointPolicy.isAllowedAddress(InetAddress.getByName("fe80::1")))
        assertFalse(LanEndpointPolicy.isAllowedAddress(InetAddress.getByName("2606:4700:4700::1111")))
        assertFalse(LanEndpointPolicy.isAllowedAddress(InetAddress.getByName("224.0.0.1")))
    }
}
