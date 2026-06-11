package dev.hyphen.android.transport

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

internal fun softwareKeyPair(): KeyPair =
    KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair()

class SelfSignedCertificateMinterTest {

    @Test
    fun `known OID encoding`() {
        // ecdsa-with-SHA256 1.2.840.10045.4.3.2, from X.690 base-128 rules.
        assertArrayEquals(
            byteArrayOf(0x06, 0x08, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x04, 0x03, 0x02),
            DerEncoder.objectIdentifier("1.2.840.10045.4.3.2"),
        )
    }

    @Test
    fun `minted certificate parses and verifies its own signature`() {
        val keyPair = softwareKeyPair()
        val cert = SelfSignedCertificateMinter.mint(keyPair, commonName = "Hyphen Test")

        cert.checkValidity() // throws if notBefore/notAfter are wrong
        cert.verify(keyPair.public) // throws if the ECDSA signature is bad
        assertEquals("CN=Hyphen Test", cert.subjectX500Principal.name)
        assertEquals(cert.subjectX500Principal, cert.issuerX500Principal)
    }

    @Test
    fun `pin matches the key the certificate carries`() {
        val keyPair = softwareKeyPair()
        val cert = SelfSignedCertificateMinter.mint(keyPair, commonName = "Hyphen Test")
        val identity = TlsIdentity(keyPair.private, cert)

        assertEquals(32, identity.spkiFingerprint.size)
        // publicKey.encoded is the DER SPKI on both sides of the mint.
        assertArrayEquals(
            java.security.MessageDigest.getInstance("SHA-256").digest(keyPair.public.encoded),
            identity.spkiFingerprint,
        )
    }
}
