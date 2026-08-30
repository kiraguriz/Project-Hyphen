package dev.hyphen.android.pairing

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
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
    private const val MAX_DEVICE_NAME_LENGTH = 80
    private val QR_FIELDS = setOf("v", "ep", "fp", "n", "dn")

    /** Parses user-typed `host:port` or `host:port?n=<base64url nonce>`. IPv6 literals are an M2 follow-up. */
    fun parseManual(input: String): ParseResult {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ParseResult.Rejected(RejectReason.EMPTY)
        if (trimmed.length > MAX_INPUT_LENGTH) return ParseResult.Rejected(RejectReason.TOO_LARGE)
        val hasQuery = trimmed.contains('?')
        val nonceB64 = if (hasQuery) {
            if (trimmed.count { it == '?' } != 1) {
                return ParseResult.Rejected(RejectReason.MALFORMED_FIELD)
            }
            val query = trimmed.substringAfter('?')
            if (!query.startsWith("n=") || query.contains('&') || query.indexOf('=') != 1) {
                return ParseResult.Rejected(RejectReason.MALFORMED_FIELD)
            }
            query.removePrefix("n=").takeIf(String::isNotEmpty)
                ?: return ParseResult.Rejected(RejectReason.MALFORMED_FIELD)
        } else {
            null
        }
        val hostPortInput = trimmed.substringBefore('?')
        val hostPort = parseHostPort(hostPortInput) ?: return ParseResult.Rejected(RejectReason.MALFORMED_ENDPOINT)
        if (nonceB64 != null && !isBase64UrlOfLength(nonceB64, NONCE_BYTES)) {
            return ParseResult.Rejected(RejectReason.MALFORMED_FIELD)
        }
        return ParseResult.Ok(ParsedEndpoint.Manual(hostPort.first, hostPort.second, nonceB64))
    }

    /** Parses a scanned QR payload. v/ep/n are required; fp is optional for manual SAS (§5.1). */
    fun parseQr(input: String): ParseResult {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ParseResult.Rejected(RejectReason.EMPTY)
        if (trimmed.length > MAX_INPUT_LENGTH) return ParseResult.Rejected(RejectReason.TOO_LARGE)
        if (!trimmed.startsWith(QR_PREFIX)) return ParseResult.Rejected(RejectReason.UNKNOWN_SCHEME)

        val rawQuery = trimmed.removePrefix(QR_PREFIX)
        if (
            rawQuery.isEmpty() ||
            rawQuery.startsWith('&') ||
            rawQuery.endsWith('&') ||
            "&&" in rawQuery
        ) {
            return ParseResult.Rejected(RejectReason.MALFORMED_FIELD)
        }
        val params = mutableMapOf<String, String>()
        for (pair in rawQuery.split('&')) {
            if (pair.isEmpty()) return ParseResult.Rejected(RejectReason.MALFORMED_FIELD)
            val eq = pair.indexOf('=')
            if (eq <= 0) return ParseResult.Rejected(RejectReason.MALFORMED_FIELD)
            val key = pair.substring(0, eq)
            val value = pair.substring(eq + 1)
            if (key !in QR_FIELDS) return ParseResult.Rejected(RejectReason.MALFORMED_FIELD)
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

        val deviceName = params["dn"]?.let {
            if (!isCanonicalPercentEncodedValue(it)) {
                return ParseResult.Rejected(RejectReason.MALFORMED_FIELD)
            }
            val decoded = percentDecodeUtf8OrNull(it)
                ?: return ParseResult.Rejected(RejectReason.MALFORMED_FIELD)
            if (!isSafeDeviceName(decoded)) {
                return ParseResult.Rejected(RejectReason.MALFORMED_FIELD)
            }
            decoded
        }

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
        if (host.any { !it.isAsciiHostCharacter() }) return null
        if (host.length > 253) return null
        val port = value.substring(colon + 1).toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return host to port
    }

    private fun isBase64UrlOfLength(value: String, expectedBytes: Int): Boolean =
        try {
            val expectedCharacters = (expectedBytes * 8 + 5) / 6
            value.length == expectedCharacters &&
                value.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' } &&
                Base64.getUrlDecoder().decode(value).size == expectedBytes
        } catch (_: IllegalArgumentException) {
            false
        }

    private fun Char.isAsciiHostCharacter(): Boolean =
        code < 128 && (isLetterOrDigit() || this == '-' || this == '.')

    private fun isSafeDeviceName(value: String): Boolean =
        value.isNotEmpty() &&
            value.codePointCount(0, value.length) <= MAX_DEVICE_NAME_LENGTH &&
            value.codePoints().allMatch { codePoint ->
                val type = Character.getType(codePoint)
                type != Character.CONTROL.toInt() && type != Character.FORMAT.toInt()
                    && type != Character.SURROGATE.toInt()
            }

    /** macOS emits raw RFC 3986 unreserved bytes and %HH for everything else. */
    private fun isCanonicalPercentEncodedValue(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val current = value[index]
            if (current == '%') {
                if (
                    index + 2 >= value.length ||
                    Character.digit(value[index + 1], 16) < 0 ||
                    Character.digit(value[index + 2], 16) < 0
                ) return false
                index += 3
            } else {
                if (
                    current.code >= 128 ||
                    !(current.isLetterOrDigit() || current == '-' || current == '.' || current == '_' || current == '~')
                ) return false
                index += 1
            }
        }
        return true
    }

    /** RFC 3986 percent decoding: `%2B` becomes `+` and malformed UTF-8 fails closed. */
    private fun percentDecodeUtf8OrNull(value: String): String? {
        val bytes = ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            val current = value[index]
            if (current == '%') {
                if (index + 2 >= value.length) return null
                val high = Character.digit(value[index + 1], 16)
                val low = Character.digit(value[index + 2], 16)
                if (high < 0 || low < 0) return null
                bytes.write((high shl 4) or low)
                index += 3
                continue
            }

            val end = when {
                Character.isHighSurrogate(current) -> {
                    if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) {
                        return null
                    }
                    index + 2
                }
                Character.isLowSurrogate(current) -> return null
                else -> index + 1
            }
            bytes.write(value.substring(index, end).toByteArray(Charsets.UTF_8))
            index = end
        }

        return runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray()))
                .toString()
        }.getOrNull()
    }
}
