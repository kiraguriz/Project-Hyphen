package dev.hyphen.android.transport

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant

/**
 * Mints a long-lived self-signed identity certificate (protocol v0 §2)
 * from a JCA EC P-256 key pair — clean-room X.509 v3 DER, mirroring the
 * macOS `SelfSignedCertificate` (HYP-M2-007). No extensions on purpose:
 * peers never run default chain evaluation — the pinned trust manager
 * replaces it — so SAN/EKU/BasicConstraints would be dead weight in v0.
 *
 * On-device production uses the Android Keystore's own self-signed
 * certificate generation instead ([AndroidKeystoreTlsIdentity]); this
 * minter exists so JVM tests exercise the real TLS stack with software
 * keys, and BouncyCastle stays out of the dependency tree.
 */
internal object SelfSignedCertificateMinter {

    private const val ECDSA_WITH_SHA256 = "1.2.840.10045.4.3.2"
    private const val COMMON_NAME = "2.5.4.3"

    fun mint(
        keyPair: KeyPair,
        commonName: String,
        validFrom: Instant = Instant.now().minusSeconds(3600),
        validDays: Long = 3650,
    ): X509Certificate {
        val der = mintDer(keyPair, commonName, validFrom, validDays)
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }

    fun mintDer(
        keyPair: KeyPair,
        commonName: String,
        validFrom: Instant,
        validDays: Long,
    ): ByteArray {
        // publicKey.encoded for JCA EC keys IS the DER SubjectPublicKeyInfo.
        require(keyPair.public.format == "X.509") {
            "expected X.509-format public key, got ${keyPair.public.format}"
        }

        val signatureAlgorithm = DerEncoder.sequence(DerEncoder.objectIdentifier(ECDSA_WITH_SHA256))
        val name = DerEncoder.sequence(
            DerEncoder.set(
                DerEncoder.sequence(
                    DerEncoder.objectIdentifier(COMMON_NAME),
                    DerEncoder.utf8String(commonName),
                )
            )
        )
        val validity = DerEncoder.sequence(
            DerEncoder.utcTime(validFrom),
            DerEncoder.utcTime(validFrom.plus(Duration.ofDays(validDays))),
        )

        val tbsCertificate = DerEncoder.sequence(
            DerEncoder.contextTag(0, DerEncoder.integer(2)), // version v3
            DerEncoder.integer(BigInteger(120, SecureRandom()).toByteArray()),
            signatureAlgorithm,
            name, // issuer == subject: self-signed
            validity,
            name,
            keyPair.public.encoded,
        )

        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(tbsCertificate)
            sign()
        }

        return DerEncoder.sequence(tbsCertificate, signatureAlgorithm, DerEncoder.bitString(signature))
    }
}
