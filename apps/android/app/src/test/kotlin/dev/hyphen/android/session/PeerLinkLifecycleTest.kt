package dev.hyphen.android.session

import dev.hyphen.android.pairing.ParsedEndpoint
import dev.hyphen.android.trust.PeerTrustStore
import dev.hyphen.android.trust.TrustedPeer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private class MemoryPeerLinkStore : PeerLinkStore {
    var descriptor: RememberedPeerLink? = null
    var clearCalls = 0
    var saveFailure: RuntimeException? = null

    override fun save(descriptor: RememberedPeerLink) {
        saveFailure?.let { throw it }
        this.descriptor = descriptor
    }

    override fun load(): RememberedPeerLink? = descriptor

    override fun clear() {
        clearCalls++
        descriptor = null
    }
}

private class MemoryPeerTrustStore : PeerTrustStore {
    private val peers = mutableListOf<TrustedPeer>()

    override fun add(peer: TrustedPeer) {
        peers.removeAll { it.spkiFingerprint.contentEquals(peer.spkiFingerprint) }
        peers += peer
    }

    override fun get(fingerprint: ByteArray): TrustedPeer? =
        peers.firstOrNull { it.spkiFingerprint.contentEquals(fingerprint) }

    override fun remove(fingerprint: ByteArray): Boolean =
        peers.removeAll { it.spkiFingerprint.contentEquals(fingerprint) }

    override fun allPeers(): List<TrustedPeer> = peers.toList()

    override fun removeAll() = peers.clear()
}

class PeerLinkLifecycleTest {
    private val fingerprint = ByteArray(32) { 0x42 }
    private val endpoint = ParsedEndpoint.Manual("192.168.1.42", 43123)

    @Test
    fun `remember and cold restore are pinned to current trust`() {
        val links = MemoryPeerLinkStore()
        val trust = MemoryPeerTrustStore().apply {
            add(TrustedPeer(fingerprint, "Studio Mac", 123L))
        }
        val lifecycle: PeerLinkLifecycle = PersistentPeerLinkLifecycle(links, trust)

        lifecycle.remember(endpoint, fingerprint)
        val restored = lifecycle.restoreTrusted()!!

        assertEquals(endpoint, restored.endpoint)
        assertTrue(fingerprint.contentEquals(restored.peer.spkiFingerprint))
        assertEquals("Studio Mac", restored.peer.displayName)
    }

    @Test
    fun `untrusted fingerprint can never be persisted`() {
        val links = MemoryPeerLinkStore()
        val lifecycle: PeerLinkLifecycle = PersistentPeerLinkLifecycle(links, MemoryPeerTrustStore())

        assertThrows(PeerLinkException.UntrustedPeer::class.java) {
            lifecycle.remember(endpoint, fingerprint)
        }
        assertNull(links.descriptor)
    }

    @Test
    fun `stale descriptor is cleared and never restored after trust removal`() {
        val links = MemoryPeerLinkStore().apply {
            descriptor = RememberedPeerLink(endpoint.host, endpoint.port, fingerprint)
        }
        val lifecycle: PeerLinkLifecycle = PersistentPeerLinkLifecycle(links, MemoryPeerTrustStore())

        assertNull(lifecycle.restoreTrusted())
        assertNull(links.descriptor)
        assertEquals(1, links.clearCalls)
    }

    @Test
    fun `trust-change invalidation removes cold-start routing`() {
        val links = MemoryPeerLinkStore().apply {
            descriptor = RememberedPeerLink(endpoint.host, endpoint.port, fingerprint)
        }
        val lifecycle: PeerLinkLifecycle = PersistentPeerLinkLifecycle(links, MemoryPeerTrustStore())

        lifecycle.invalidate()

        assertNull(links.descriptor)
    }

    @Test
    fun `failed replacement clears the previously current descriptor`() {
        val oldFingerprint = ByteArray(32) { 0x21 }
        val links = MemoryPeerLinkStore().apply {
            descriptor = RememberedPeerLink("192.168.1.21", 42100, oldFingerprint)
            saveFailure = IllegalStateException("save failed")
        }
        val trust = MemoryPeerTrustStore().apply {
            add(TrustedPeer(fingerprint, "Studio Mac", 123L))
        }
        val lifecycle: PeerLinkLifecycle = PersistentPeerLinkLifecycle(links, trust)

        assertThrows(IllegalStateException::class.java) {
            lifecycle.remember(endpoint, fingerprint)
        }

        assertNull(links.descriptor)
        assertEquals(1, links.clearCalls)
    }
}
