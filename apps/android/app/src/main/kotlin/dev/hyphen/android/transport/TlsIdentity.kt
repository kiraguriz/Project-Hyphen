package dev.hyphen.android.transport

import java.net.Socket
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.X509ExtendedKeyManager

/**
 * The device's own TLS identity (HYP-M2-008): a long-lived EC P-256 key
 * plus its self-signed certificate. The SPKI fingerprint peers pin
 * survives certificate renewal; a key change is a new identity and
 * forces re-pairing (protocol v0 §2).
 */
class TlsIdentity(
    val privateKey: PrivateKey,
    val certificate: X509Certificate,
) {
    /** SHA-256 of this device's DER SubjectPublicKeyInfo; 32 bytes. */
    val spkiFingerprint: ByteArray = spkiFingerprintOf(certificate)

    companion object {
        /** `publicKey.encoded` for JCA keys is the DER SubjectPublicKeyInfo. */
        fun spkiFingerprintOf(certificate: X509Certificate): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
    }
}

/**
 * Key manager that always presents [identity] — no alias selection, no
 * keystore. Works with both software keys (JVM tests) and non-extractable
 * Android Keystore keys (which `KeyStore.setKeyEntry` would reject).
 */
internal class SingleIdentityKeyManager(private val identity: TlsIdentity) : X509ExtendedKeyManager() {

    private val alias = "hyphen-identity"

    override fun chooseClientAlias(keyType: Array<String>?, issuers: Array<Principal>?, socket: Socket?) = alias
    override fun chooseServerAlias(keyType: String?, issuers: Array<Principal>?, socket: Socket?) = alias
    override fun getClientAliases(keyType: String?, issuers: Array<Principal>?) = arrayOf(alias)
    override fun getServerAliases(keyType: String?, issuers: Array<Principal>?) = arrayOf(alias)
    override fun getCertificateChain(alias: String?) = arrayOf(identity.certificate)
    override fun getPrivateKey(alias: String?): PrivateKey = identity.privateKey
}
