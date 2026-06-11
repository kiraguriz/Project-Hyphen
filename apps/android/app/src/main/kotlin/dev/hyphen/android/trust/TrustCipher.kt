package dev.hyphen.android.trust

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Seals/opens the serialized trust store (HYP-M2-006). */
interface TrustCipher {
    fun seal(plaintext: ByteArray): ByteArray

    /** @throws GeneralSecurityException on tampering, wrong key, or malformed input. */
    fun open(sealed: ByteArray): ByteArray
}

/**
 * AES-256-GCM with a provider-generated random IV per seal, output
 * `ivLength(1 byte) || iv || ciphertext+tag`. Works unchanged with both a
 * software [SecretKey] (JVM unit tests) and an Android Keystore key
 * (production, see [AndroidTrustStores]) — Keystore keys require the
 * provider to pick the IV, so `init` is never given one when encrypting.
 */
class AesGcmTrustCipher(private val key: SecretKey) : TrustCipher {

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }

    override fun seal(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        check(iv != null && iv.size in 1..255) { "unexpected GCM IV from provider" }
        return byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(plaintext)
    }

    override fun open(sealed: ByteArray): ByteArray {
        if (sealed.isEmpty()) throw GeneralSecurityException("sealed payload is empty")
        val ivLength = sealed[0].toInt() and 0xFF
        if (ivLength == 0 || sealed.size < 1 + ivLength + TAG_BITS / 8) {
            throw GeneralSecurityException("sealed payload is truncated")
        }
        val iv = sealed.copyOfRange(1, 1 + ivLength)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(sealed.copyOfRange(1 + ivLength, sealed.size))
    }
}
