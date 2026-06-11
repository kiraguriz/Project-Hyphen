package dev.hyphen.android.trust

import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class AesGcmTrustCipherTest {

    private val cipher = AesGcmTrustCipher(softwareAesKey())

    @Test
    fun `open returns what seal consumed`() {
        val plaintext = "hyphen-trust-v0\n".toByteArray()
        assertArrayEquals(plaintext, cipher.open(cipher.seal(plaintext)))
    }

    @Test
    fun `sealing twice never reuses the IV`() {
        val plaintext = ByteArray(64)
        val a = cipher.seal(plaintext)
        val b = cipher.seal(plaintext)
        val ivA = a.copyOfRange(1, 1 + (a[0].toInt() and 0xFF))
        val ivB = b.copyOfRange(1, 1 + (b[0].toInt() and 0xFF))
        assertFalse("GCM IV must be random per seal", ivA.contentEquals(ivB))
    }

    @Test
    fun `tampered ciphertext fails to open`() {
        val sealed = cipher.seal("secret".toByteArray())
        sealed[sealed.size - 1] = (sealed.last().toInt() xor 0x01).toByte()
        assertThrows(GeneralSecurityException::class.java) { cipher.open(sealed) }
    }

    @Test
    fun `truncated and empty payloads fail to open`() {
        val sealed = cipher.seal("secret".toByteArray())
        assertThrows(GeneralSecurityException::class.java) { cipher.open(ByteArray(0)) }
        assertThrows(GeneralSecurityException::class.java) {
            cipher.open(sealed.copyOfRange(0, 8))
        }
    }
}

internal fun softwareAesKey() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
