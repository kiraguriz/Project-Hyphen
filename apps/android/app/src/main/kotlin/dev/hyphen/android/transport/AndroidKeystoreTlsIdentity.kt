package dev.hyphen.android.transport

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * Production identity for HYP-M2-008: a non-exportable EC P-256 signing
 * key in the Android Keystore plus the Keystore's own self-signed
 * certificate (so no hand-minted DER on-device). The key has no
 * user-authentication binding — background reconnects must handshake
 * without unlock interaction.
 *
 * JVM unit tests exercise the TLS stack with software keys and
 * [SelfSignedCertificateMinter] instead; this object needs a device and
 * is covered by the M2 on-device checklist (along with the API 26–28
 * TLS 1.3 gap noted on [HyphenTls]).
 */
object AndroidKeystoreTlsIdentity {

    const val KEY_ALIAS = "dev.hyphen.tls-identity.v0"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val VALID_YEARS = 10

    fun getOrCreate(commonName: String = "Hyphen Android"): TlsIdentity {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existingKey = keyStore.getKey(KEY_ALIAS, null) as? PrivateKey
        val existingCert = keyStore.getCertificate(KEY_ALIAS) as? X509Certificate
        if (existingKey != null && existingCert != null) {
            return TlsIdentity(existingKey, existingCert)
        }

        val now = System.currentTimeMillis()
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setCertificateSubject(X500Principal("CN=$commonName"))
                .setCertificateSerialNumber(BigInteger(120, SecureRandom()))
                .setCertificateNotBefore(Date(now - 3_600_000)) // clock-skew slack
                .setCertificateNotAfter(Date(now + VALID_YEARS * 365L * 86_400_000))
                .build()
        )
        generator.generateKeyPair()

        val key = keyStore.getKey(KEY_ALIAS, null) as PrivateKey
        val cert = keyStore.getCertificate(KEY_ALIAS) as X509Certificate
        return TlsIdentity(key, cert)
    }

    /** Identity reset: peers must re-pair afterwards (pin changes). */
    fun remove() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }
}
