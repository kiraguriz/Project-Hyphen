package dev.hyphen.android.transport

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Minimal DER encoder — exactly the constructs a self-signed X.509 v3
 * certificate needs (HYP-M2-008; Kotlin twin of the macOS `DER` enum from
 * HYP-M2-007). Encoding only; parsing stays with `CertificateFactory`.
 * Clean-room from the ASN.1 DER rules (ITU-T X.690).
 */
internal object DerEncoder {

    fun sequence(vararg contents: ByteArray): ByteArray = tagged(0x30, concat(contents))

    fun set(vararg contents: ByteArray): ByteArray = tagged(0x31, concat(contents))

    /** EXPLICIT context-specific constructed tag, e.g. `[0]` for version. */
    fun contextTag(number: Int, contents: ByteArray): ByteArray =
        tagged(0xA0 or number, contents)

    /**
     * INTEGER from raw big-endian magnitude bytes (positive): strips
     * redundant leading zeros, then left-pads one 0x00 if the high bit is
     * set, per DER minimal two's complement.
     */
    fun integer(magnitude: ByteArray): ByteArray {
        var start = 0
        while (start < magnitude.size - 1 &&
            magnitude[start] == 0.toByte() &&
            magnitude[start + 1].toInt() and 0x80 == 0
        ) start++
        var bytes = magnitude.copyOfRange(start, magnitude.size)
        if (bytes.isEmpty()) bytes = byteArrayOf(0x00)
        if (bytes[0].toInt() and 0x80 != 0) bytes = byteArrayOf(0x00) + bytes
        return tagged(0x02, bytes)
    }

    fun integer(value: Int): ByteArray {
        require(value in 0..127)
        return integer(byteArrayOf(value.toByte()))
    }

    /** BIT STRING with zero unused bits (all X.509 uses here are byte-aligned). */
    fun bitString(contents: ByteArray): ByteArray =
        tagged(0x03, byteArrayOf(0x00) + contents)

    /** OBJECT IDENTIFIER from dotted notation, e.g. "1.2.840.10045.2.1". */
    fun objectIdentifier(dotted: String): ByteArray {
        val parts = dotted.split('.').map { it.toLong() }
        require(parts.size >= 2) { "OID needs at least two arcs" }
        var body = byteArrayOf((parts[0] * 40 + parts[1]).toByte())
        for (arc in parts.drop(2)) body += base128(arc)
        return tagged(0x06, body)
    }

    fun utf8String(value: String): ByteArray =
        tagged(0x0C, value.toByteArray(Charsets.UTF_8))

    /** UTCTime (YYMMDDHHMMSSZ); valid for dates in 1950–2049. */
    fun utcTime(instant: Instant): ByteArray =
        tagged(0x17, UTC_TIME.format(instant).toByteArray(Charsets.US_ASCII))

    private val UTC_TIME =
        DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'").withZone(ZoneOffset.UTC)

    fun tagged(tag: Int, contents: ByteArray): ByteArray =
        byteArrayOf(tag.toByte()) + length(contents.size) + contents

    private fun length(count: Int): ByteArray {
        if (count < 0x80) return byteArrayOf(count.toByte())
        var bytes = byteArrayOf()
        var remaining = count
        while (remaining > 0) {
            bytes = byteArrayOf((remaining and 0xFF).toByte()) + bytes
            remaining = remaining ushr 8
        }
        return byteArrayOf((0x80 or bytes.size).toByte()) + bytes
    }

    private fun base128(value: Long): ByteArray {
        var groups = byteArrayOf((value and 0x7F).toByte())
        var remaining = value ushr 7
        while (remaining > 0) {
            groups = byteArrayOf(((remaining and 0x7F) or 0x80).toByte()) + groups
            remaining = remaining ushr 7
        }
        return groups
    }

    private fun concat(parts: Array<out ByteArray>): ByteArray {
        var out = byteArrayOf()
        for (part in parts) out += part
        return out
    }
}
