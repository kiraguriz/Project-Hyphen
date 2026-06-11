package dev.hyphen.android.transport

import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * Pin check replacing default X.509 chain evaluation (HYP-M2-008; twin of
 * macOS `SPKIPinVerifier`): the peer leaf certificate's SPKI fingerprint
 * must satisfy `isTrusted` (in the app, a `PeerTrustStore` lookup; during
 * pairing, the QR-pre-shared fingerprint). Discovery is not trust, and a
 * CA chain proves nothing here; only the pinned key does. No hostname
 * verification by design — the pin is the identity.
 */
class PinnedTrustManager(private val isTrusted: (ByteArray) -> Boolean) : X509TrustManager {

    override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) =
        check(chain)

    override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) =
        check(chain)

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private fun check(chain: Array<X509Certificate>?) {
        val leaf = chain?.firstOrNull()
            ?: throw CertificateException("peer presented no certificate")
        val fingerprint = try {
            TlsIdentity.spkiFingerprintOf(leaf)
        } catch (e: Exception) {
            throw CertificateException("could not fingerprint peer certificate", e)
        }
        if (!isTrusted(fingerprint)) {
            // trust/fingerprint-mismatch in the protocol error taxonomy:
            // the MITM signal. Callers tear down and surface re-verify UI.
            throw CertificateException("peer SPKI fingerprint is not trusted")
        }
    }
}
