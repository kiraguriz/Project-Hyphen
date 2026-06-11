package dev.hyphen.android.trust

/**
 * A trusted peer record (HYP-M2-006), Android counterpart of the macOS
 * `KeychainTrustStore` (HYP-M2-005). The SPKI fingerprint IS the peer
 * identity (protocol v0 §2): a different key is a different (untrusted)
 * peer, and a key change always re-enters pairing.
 */
class TrustedPeer(
    /** SHA-256 of the peer certificate's DER SubjectPublicKeyInfo; 32 bytes. */
    val spkiFingerprint: ByteArray,
    val displayName: String,
    val addedAtEpochMs: Long,
) {
    val fingerprintHex: String get() = spkiFingerprint.toHyphenHex()

    override fun equals(other: Any?): Boolean =
        other is TrustedPeer &&
            spkiFingerprint.contentEquals(other.spkiFingerprint) &&
            displayName == other.displayName &&
            addedAtEpochMs == other.addedAtEpochMs

    override fun hashCode(): Int =
        31 * (31 * spkiFingerprint.contentHashCode() + displayName.hashCode()) +
            addedAtEpochMs.hashCode()

    override fun toString(): String =
        "TrustedPeer(fingerprintHex=$fingerprintHex, displayName=$displayName, addedAtEpochMs=$addedAtEpochMs)"
}

sealed class TrustStoreException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    class InvalidFingerprintLength(val actual: Int) :
        TrustStoreException("fingerprint must be 32 bytes, got $actual")

    /**
     * The store file failed to decrypt or parse (tampering, wrong key, or
     * disk corruption). Deliberately NOT treated as an empty store: the UI
     * must surface a trust reset instead of silently re-trusting no one.
     */
    class CorruptStore(cause: Throwable? = null) :
        TrustStoreException("trust store could not be decrypted or parsed", cause)
}

interface PeerTrustStore {
    /** Upsert: re-trusting the same fingerprint updates metadata. */
    fun add(peer: TrustedPeer)

    fun get(fingerprint: ByteArray): TrustedPeer?

    /** @return true if the fingerprint was present and removed. */
    fun remove(fingerprint: ByteArray): Boolean

    fun allPeers(): List<TrustedPeer>

    /** Removes every peer (trust reset, tests). */
    fun removeAll()
}

internal fun ByteArray.toHyphenHex(): String =
    joinToString("") { "%02x".format(it) }

internal fun String.hyphenHexToBytesOrNull(): ByteArray? {
    if (length % 2 != 0) return null
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        val byte = substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null
        out[i] = byte.toByte()
    }
    return out
}
