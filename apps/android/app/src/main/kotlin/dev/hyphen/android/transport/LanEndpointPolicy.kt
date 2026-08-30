package dev.hyphen.android.transport

import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Locale

/**
 * Frozen v1 locality boundary. Text parsing accepts only private/link-local
 * IPv4 literals, loopback (for local development), or mDNS `.local` names.
 * Runtime resolution then pins one concrete LAN address so a later DNS answer
 * cannot redirect the socket to a public endpoint.
 */
object LanEndpointPolicy {
    fun isAllowedHostSyntax(host: String): Boolean {
        val normalized = host.lowercase(Locale.ROOT).removeSuffix(".")
        val ipv4 = parseIpv4(normalized)
        if (ipv4 != null) {
            return isAllowedAddress(InetAddress.getByAddress(ipv4))
        }
        if (normalized.all { it.isDigit() || it == '.' }) return false
        if (!normalized.endsWith(".local")) return false
        val labels = normalized.split('.')
        return labels.all { label ->
            label.isNotEmpty() &&
                label.length <= 63 &&
                label.first() != '-' &&
                label.last() != '-' &&
                label.all { it.code < 128 && (it.isLetterOrDigit() || it == '-') }
        }
    }

    fun resolveAllowed(host: String): InetAddress {
        if (!isAllowedHostSyntax(host)) {
            throw UnknownHostException("endpoint is outside the local-network policy")
        }
        return selectResolvedAddress(host, InetAddress.getAllByName(host))
            ?: throw UnknownHostException("endpoint did not resolve to a local-network address")
    }

    internal fun selectResolvedAddress(host: String, addresses: Array<InetAddress>): InetAddress? {
        if (!isAllowedHostSyntax(host)) return null
        return addresses.firstOrNull(::isAllowedAddress)
    }

    fun isAllowedAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isMulticastAddress) return false
        if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) return true
        if (address is Inet6Address) {
            val first = address.address.first().toInt() and 0xff
            return first and 0xfe == 0xfc // RFC 4193 unique-local fc00::/7.
        }
        return false
    }

    private fun parseIpv4(host: String): ByteArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = parts.map { part ->
            if (part.isEmpty() || part.length > 3 || part.any { !it.isDigit() }) return null
            if (part.length > 1 && part.startsWith('0')) return null
            part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
        }
        return ByteArray(4) { index -> octets[index].toByte() }
    }
}
