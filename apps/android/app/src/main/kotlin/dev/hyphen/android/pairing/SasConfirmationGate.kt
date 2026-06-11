package dev.hyphen.android.pairing

import dev.hyphen.android.trust.PeerTrustStore
import dev.hyphen.android.trust.TrustedPeer

/**
 * The trust-write gate behind the SAS confirmation UI (HYP-M2-011,
 * protocol v0 §5.3; Kotlin twin of the macOS gate): the peer fingerprint
 * reaches the trust store ONLY through [confirm], and a rejection is
 * sticky — nothing persists, the pairing session is dead
 * (`trust/sas-rejected`). One gate instance per pairing attempt.
 */
class SasConfirmationGate(
    val transcript: PairingTranscript,
    /**
     * The fingerprint trusted on confirm — on Android this is the Mac's
     * SPKI fingerprint from the QR payload (already authenticated against
     * the provisional TLS server certificate).
     */
    private val peerFingerprint: ByteArray,
    private val peerDisplayName: String,
    private val trustStore: PeerTrustStore,
) {
    enum class Outcome { TRUSTED, REJECTED }

    var outcome: Outcome? = null
        private set

    /** The 6-digit code the UI displays for cross-device comparison. */
    val sas: String get() = transcript.sas

    /**
     * Persists the peer. Idempotent after a confirm; refused after a
     * rejection (a dead pairing session can never become trusted).
     */
    @Synchronized
    fun confirm(nowEpochMs: Long = System.currentTimeMillis()): Outcome {
        outcome?.let { return it }
        trustStore.add(TrustedPeer(peerFingerprint, peerDisplayName, nowEpochMs))
        outcome = Outcome.TRUSTED
        return Outcome.TRUSTED
    }

    /** Aborts the pairing session. Persists nothing, ever. */
    @Synchronized
    fun reject(): Outcome {
        if (outcome == null) outcome = Outcome.REJECTED
        return outcome!!
    }
}
