package dev.hyphen.android.transport

import java.security.SecureRandom
import java.util.Base64

/**
 * Responder-side resume tokens (HYP-M2-013, protocol v0 §4.5–4.6,
 * resolving §9.1): a token is a random 32-byte handle (base64url,
 * 43 chars — fits the hello schema's 16–128 pattern), bound to one
 * session AND one peer fingerprint, single-use, and expired after
 * 10 minutes. In-memory only: tokens deliberately do not survive an app
 * restart (worst case is a fresh session). Trust revocation must call
 * [invalidatePeer]. Tokens are continuity hints, never authentication —
 * the TLS pin check has always already run by the time one is redeemed.
 */
class ResumeTokenStore(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val DEFAULT_TTL_MS = 10 * 60 * 1000L
    }

    private data class Entry(
        val sessionId: String,
        val peerFingerprintHex: String,
        val issuedAtMs: Long,
    )

    private val random = SecureRandom()
    private val entries = HashMap<String, Entry>()

    /** Drops unredeemed tokens past [ttlMs]. Called from issue/redeem/liveCount. */
    @Synchronized
    fun purgeExpired() {
        val now = nowMs()
        entries.entries.removeIf { now - it.value.issuedAtMs > ttlMs }
    }

    /** Issues a fresh token for resuming [sessionId]; invalidates any
     *  previous token for the same session (one live token per session). */
    @Synchronized
    fun issue(sessionId: String, peerFingerprint: ByteArray): String {
        purgeExpired()
        entries.values.removeAll { it.sessionId == sessionId }
        val token = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(32).also(random::nextBytes))
        entries[token] = Entry(sessionId, peerFingerprint.toHexLower(), nowMs())
        return token
    }

    /** @return the resumable sessionId, or null (unknown, expired, or
     *  wrong peer). The token is consumed either way — single-use. */
    @Synchronized
    fun redeem(token: String, peerFingerprint: ByteArray): String? {
        purgeExpired()
        val entry = entries.remove(token) ?: return null
        if (nowMs() - entry.issuedAtMs > ttlMs) return null
        if (entry.peerFingerprintHex != peerFingerprint.toHexLower()) return null
        return entry.sessionId
    }

    /** Trust revocation hook (§4.6): drops every token for the peer. */
    @Synchronized
    fun invalidatePeer(peerFingerprint: ByteArray) {
        val hex = peerFingerprint.toHexLower()
        entries.values.removeAll { it.peerFingerprintHex == hex }
    }

    @Synchronized
    fun invalidateAll() {
        entries.clear()
    }

    @Synchronized
    fun liveCount(): Int {
        purgeExpired()
        return entries.size
    }
}

private fun ByteArray.toHexLower(): String = joinToString("") { "%02x".format(it) }
