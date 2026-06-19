package dev.hyphen.android.pairing

import java.util.Base64

/**
 * Fallback endpoint inputs (HYP-M1-006): a scanned `hyphen://pair` QR
 * payload (protocol §5.1) or a user-typed `host:port`. Parsing must fail
 * safe — sealed results, never exceptions (HYP-M2-010 hardens further).
 */
sealed class ParsedEndpoint {
    abstract val host: String
    abstract val port: Int

    data class Manual(
        override val host: String,
        override val port: Int,
        /** base64url pairing nonce from the Mac pairing screen (16 bytes). */
        val nonceB64: String? = null,
    ) : ParsedEndpoint()

    data class QrPayload(
        val version: Int,
        override val host: String,
        override val port: Int,
        /** base64url, decodes to 32 bytes (SHA-256 of SPKI); absent for manual-IP SAS pairing. */
        val spkiFingerprintB64: String?,
        /** base64url, decodes to 16 bytes. */
        val nonceB64: String,
        val deviceName: String?,
    ) : ParsedEndpoint() {
        fun hasPinnedFingerprint(): Boolean = spkiFingerprintB64 != null

        /** The Mac's pre-shared pin when present; manual-IP pairing omits it. */
        fun decodedFingerprint(): ByteArray? =
            spkiFingerprintB64?.let { Base64.getUrlDecoder().decode(it) }

        /** The pairing-session nonce bound into the SAS transcript. */
        fun decodedNonce(): ByteArray = Base64.getUrlDecoder().decode(nonceB64)
    }
}

enum class RejectReason {
    EMPTY,
    TOO_LARGE,
    UNKNOWN_SCHEME,
    MISSING_FIELD,
    MALFORMED_ENDPOINT,
    MALFORMED_FIELD,
    UNSUPPORTED_VERSION,
}

sealed class ParseResult {
    data class Ok(val endpoint: ParsedEndpoint) : ParseResult()
    data class Rejected(val reason: RejectReason) : ParseResult()
}

object EndpointParser {

    private const val MAX_INPUT_LENGTH = 1024
    private const val QR_PREFIX = "hyphen://pair?"
    private const val SUPPORTED_VERSION = 0
    private const val FINGERPRINT_BYTES = 32
    private const val NONCE_BYTES = 16

    /** Parses user-typed `host:port` or `host:port?n=<base64url nonce>`. IPv6 literals are an M2 follow-up. */
    fun parseManual(input: String): ParseResult {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ParseResult.Rejected(RejectReason.EMPTY)
        if (trimmed.length > MAX_INPUT_LENGTH) return ParseResult.Rejected(RejectReason.TOO_LARGE)
        val nonceB64 = trimmed.substringAfter('?', missingDelimiterValue = "")
            .removePrefix("n=")
            .takeIf { trimmed.contains('?') && it.isNotEmpty() }
        val hostPortInput = trimmed.substringBefore('?')
        val hostPort = parseHostPort(hostPortInput) ?: return ParseResult.Rejected(RejectReason.MALFORMED_ENDPOINT)
        if (nonceB64 != null && !isBase64UrlOfLength(nonceB64, NONCE_BYTES)) {
            return ParseResult.Rejected(RejectReason.MALFORMED_FIELD)
        }
        return ParseResult.Ok(ParsedEndpoint.Manual(hostPort.first, hostPort.second, nonceB64))
    }

    /** Parses a scanned QR payload. All of v/ep/fp/n are required (§5.1). */
    fun parseQr(input: String): ParseResult {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ParseResult.Rejected(RejectReason.EMPTY)
        if (trimmed.length > MAX_INPUT_LENGTH) return ParseResult.Rejected(RejectReason.TOO_LARGE)
        if (!trimmed.startsWith(QR_PREFIX)) return ParseResult.Rejected(RejectReason.UNKNOWN_SCHEME)

        val params = mutableMapOf<String, String>()
        for (pair in trimmed.removePrefix(QR_PREFIX).split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            if (eq <= 0) return ParseResult.Rejected(RejectReason.MALFORMED_FIELD)
            val key = pair.substring(0, eq)
            val value = pair.substring(eq + 1)
            if (key in params) return ParseResult.Rejected(RejectReason.MALFORMED_FIELD)
            params[key] = value
        }

        val v = params["v"] ?: return ParseResult.Rejected(RejectReason.MISSING_FIELD)
        val ep = params["ep"] ?: return ParseResult.Rejected(RejectReason.MISSING_FIELD)
        val fp = params["fp"]
        val n = params["n"] ?: return ParseResult.Rejected(RejectReason.MISSING_FIELD)

        val version = v.toIntOrNull() ?: return ParseResult.Rejected(RejectReason.MALFORMED_FIELD)
        if (version != SUPPORTED_VERSION) return ParseResult.Rejected(RejectReason.UNSUPPORTED_VERSION)

        val hostPort = parseHostPort(ep) ?: return ParseResult.Rejected(RejectReason.MALFORMED_ENDPOINT)
        if (fp != null && !isBase64UrlOfLength(fp, FINGERPRINT_BYTES)) {
            return ParseResult.Rejected(RejectReason.MALFORMED_FIELD)
        }
        if (!isBase64UrlOfLength(n, NONCE_BYTES)) return ParseResult.Rejected(RejectReason.MALFORMED_FIELD)

        val deviceName = params["dn"]?.let { urlDecodeOrNull(it) ?: return ParseResult.Rejected(RejectReason.MALFORMED_FIELD) }

        return ParseResult.Ok(
            ParsedEndpoint.QrPayload(
                version = version,
                host = hostPort.first,
                port = hostPort.second,
                spkiFingerprintB64 = fp,
                nonceB64 = n,
                deviceName = deviceName,
            )
        )
    }

    private fun parseHostPort(value: String): Pair<String, Int>? {
        val colon = value.lastIndexOf(':')
        if (colon <= 0 || colon == value.length - 1) return null
        val host = value.substring(0, colon)
        // PoC scope: reject IPv6 literals (extra colons) instead of mis-parsing.
        if (host.contains(':') || host.contains('/') || host.any { it.isWhitespace() }) return null
        if (host.length > 253) return null
        val port = value.substring(colon + 1).toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return host to port
    }

    private fun isBase64UrlOfLength(value: String, expectedBytes: Int): Boolean =
        try {
            Base64.getUrlDecoder().decode(value).size == expectedBytes
        } catch (_: IllegalArgumentException) {
            false
        }

    private fun urlDecodeOrNull(value: String): String? =
        try {
            java.net.URLDecoder.decode(value, Charsets.UTF_8.name())
        } catch (_: IllegalArgumentException) {
            null
        }
}
