package dev.hyphen.android.session

import dev.hyphen.android.pairing.ParsedEndpoint
import dev.hyphen.android.trust.PeerTrustStore
import dev.hyphen.android.trust.TrustedPeer

/**
 * Minimal durable routing descriptor for one v0 peer link.
 *
 * It deliberately excludes pairing nonces, session ids, and resume tokens.
 * Cold start performs a new mutually authenticated handshake and uses this
 * fingerprint only after [PersistentPeerLinkLifecycle] revalidates trust.
 */
class RememberedPeerLink(
    val host: String,
    val port: Int,
    val peerFingerprint: ByteArray,
) {
    init {
        require(host.isNotBlank() && host.length <= 253) { "invalid peer-link host" }
        require(host.none { it.isWhitespace() || it == ':' || it == '/' || it == '\\' }) {
            "invalid peer-link host"
        }
        require(port in 1..65535) { "invalid peer-link port" }
        require(peerFingerprint.size == 32) { "peer fingerprint must be 32 bytes" }
    }

    override fun equals(other: Any?): Boolean =
        other is RememberedPeerLink &&
            host == other.host &&
            port == other.port &&
            peerFingerprint.contentEquals(other.peerFingerprint)

    override fun hashCode(): Int =
        31 * (31 * host.hashCode() + port) + peerFingerprint.contentHashCode()
}

data class RestoredPeerLink(
    val endpoint: ParsedEndpoint.Manual,
    val peer: TrustedPeer,
)

sealed class PeerLinkException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class CorruptStore(cause: Throwable? = null) :
        PeerLinkException("remembered peer link is corrupt", cause)

    class UntrustedPeer : PeerLinkException("remembered peer link is not trusted")
}

interface PeerLinkStore {
    fun save(descriptor: RememberedPeerLink)
    fun load(): RememberedPeerLink?
    fun clear()
}

/** Lifecycle seam shared by Activity, the process supervisor, and Phase-4 FGS. */
interface PeerLinkLifecycle {
    fun remember(endpoint: ParsedEndpoint, peerFingerprint: ByteArray)
    fun restoreTrusted(): RestoredPeerLink?
    fun invalidate()
}

class PersistentPeerLinkLifecycle(
    private val store: PeerLinkStore,
    private val trustStore: PeerTrustStore,
) : PeerLinkLifecycle {
    override fun remember(endpoint: ParsedEndpoint, peerFingerprint: ByteArray) {
        if (trustStore.get(peerFingerprint) == null) throw PeerLinkException.UntrustedPeer()
        try {
            store.save(RememberedPeerLink(endpoint.host, endpoint.port, peerFingerprint.copyOf()))
        } catch (error: RuntimeException) {
            // A failed atomic replacement may have left the superseded current
            // descriptor intact. Best-effort removal keeps the next cold start
            // fail-closed instead of silently selecting that previous peer.
            runCatching { store.clear() }
            throw error
        }
    }

    override fun restoreTrusted(): RestoredPeerLink? {
        val descriptor = store.load() ?: return null
        val peer = trustStore.get(descriptor.peerFingerprint)
        if (peer == null) {
            store.clear()
            return null
        }
        return RestoredPeerLink(
            endpoint = ParsedEndpoint.Manual(descriptor.host, descriptor.port),
            peer = peer,
        )
    }

    override fun invalidate() = store.clear()
}
