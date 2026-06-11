package dev.hyphen.android.pairing

import java.security.MessageDigest

/**
 * Pairing transcript and SAS (protocol v0 §5.3, HYP-M2-010/011; Kotlin
 * twin of the macOS `PairingTranscript`):
 *
 * ```text
 * transcript     = "hyphen-pair-v0" || nonce || macSpkiFp || androidSpkiFp || protocolVersion
 * transcriptHash = SHA-256(transcript)
 * SAS            = uint64_be(hash[0..7]) mod 10^6, zero-padded to 6 digits
 * ```
 *
 * Field order binds roles and the version is bound last (downgrade
 * detection); all of it is pinned by the normative vectors in
 * `protocol/test-vectors/pairing/sas-vectors.json`, which the test suite
 * reproduces case by case.
 */
class PairingTranscript private constructor(
    val transcriptData: ByteArray,
    val hash: ByteArray,
    /** Exactly 6 decimal digits, leading zeros preserved. */
    val sas: String,
) {
    companion object {
        const val LABEL = "hyphen-pair-v0"
        const val NONCE_LENGTH = 16
        const val FINGERPRINT_LENGTH = 32

        /** Protocol identifier bound into the transcript; must match macOS. */
        const val PROTOCOL_VERSION = dev.hyphen.android.transport.Envelope.PROTOCOL_ID

        fun create(
            nonce: ByteArray,
            macSpkiFingerprint: ByteArray,
            androidSpkiFingerprint: ByteArray,
            protocolVersion: String,
        ): PairingTranscript? {
            if (nonce.size != NONCE_LENGTH ||
                macSpkiFingerprint.size != FINGERPRINT_LENGTH ||
                androidSpkiFingerprint.size != FINGERPRINT_LENGTH ||
                protocolVersion.isEmpty()
            ) return null

            val transcript = LABEL.toByteArray(Charsets.US_ASCII) +
                nonce +
                macSpkiFingerprint +
                androidSpkiFingerprint +
                protocolVersion.toByteArray(Charsets.UTF_8)

            val hash = MessageDigest.getInstance("SHA-256").digest(transcript)
            var value = 0uL
            for (i in 0 until 8) {
                value = (value shl 8) or hash[i].toUByte().toULong()
            }
            return PairingTranscript(
                transcriptData = transcript,
                hash = hash,
                sas = String.format("%06d", (value % 1_000_000uL).toLong()),
            )
        }
    }
}
