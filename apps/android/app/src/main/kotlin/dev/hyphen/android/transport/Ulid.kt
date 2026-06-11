package dev.hyphen.android.transport

import java.security.SecureRandom

/**
 * ULID generation (HYP-M2-012): 48-bit unix-ms timestamp + 80 random
 * bits, Crockford base32, 26 chars — the `messageId` format pinned by
 * `envelope.schema.json`. Clean-room from the ULID spec description.
 */
object Ulid {

    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val random = SecureRandom()
    private val SHAPE = Regex("^[0-9A-HJKMNP-TV-Z]{26}$")

    fun generate(nowMs: Long = System.currentTimeMillis()): String {
        val chars = CharArray(26)
        var time = nowMs
        for (i in 9 downTo 0) {
            chars[i] = ALPHABET[(time and 31).toInt()]
            time = time ushr 5
        }
        val randomBytes = ByteArray(10).also(random::nextBytes)
        var buffer = 0L
        var bits = 0
        var index = 10
        for (byte in randomBytes) {
            buffer = (buffer shl 8) or (byte.toLong() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                chars[index++] = ALPHABET[((buffer ushr bits) and 31).toInt()]
            }
        }
        return String(chars)
    }

    fun isValid(value: String): Boolean = SHAPE.matches(value)
}
