package dev.hyphen.android.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakePresenceSource : PresenceSource {
    val observing = mutableMapOf<PeerId, (PresenceEvent) -> Unit>()
    val stopped = mutableListOf<PeerId>()

    override fun startObserving(peerId: PeerId, onEvent: (PresenceEvent) -> Unit) {
        observing[peerId] = onEvent
    }

    override fun stopObserving(peerId: PeerId) {
        stopped += peerId
        observing.remove(peerId)
    }

    fun emit(event: PresenceEvent, peerId: PeerId) {
        observing[peerId]?.invoke(event)
    }
}

private class FakeBackend(var sdk: Int) : CdmBackend {
    var lastCallbacks: CdmCallbacks? = null
    var lastDisplayName: String? = null
    val disassociated = mutableListOf<Int>()

    override fun sdkInt() = sdk

    override fun requestSelfManagedAssociation(displayName: String, callbacks: CdmCallbacks) {
        lastDisplayName = displayName
        lastCallbacks = callbacks
    }

    override fun disassociate(associationId: Int) {
        disassociated += associationId
    }

    override fun listAssociationIds(): List<Int> = emptyList()
}

class CompanionPresenceAdapterTest {

    private val mac = PeerId("mac-fp-1")
    private val other = PeerId("mac-fp-2")

    @Test
    fun `factory selects legacy below 36 and api36 from 36`() {
        val source = FakePresenceSource()
        assertTrue(
            CompanionPresenceAdapterFactory.forSdk(26, FakeBackend(26), source)
                is LegacyPresenceAdapter,
        )
        assertTrue(
            CompanionPresenceAdapterFactory.forSdk(35, FakeBackend(35), source)
                is LegacyPresenceAdapter,
        )
        assertTrue(
            CompanionPresenceAdapterFactory.forSdk(36, FakeBackend(36), source)
                is Api36PresenceAdapter,
        )
    }

    @Test
    fun `associate below 33 is unsupported on the legacy adapter`() {
        val results = mutableListOf<PresenceAssociationResult>()
        LegacyPresenceAdapter(FakeBackend(30))
            .associate(HyphenAssociationRequest(mac, "Mac")) { results += it }
        assertEquals(listOf<PresenceAssociationResult>(PresenceAssociationResult.Unsupported), results)
    }

    @Test
    fun `associate from 33 delegates and records the association id`() {
        val backend = FakeBackend(34)
        val adapter = LegacyPresenceAdapter(backend)
        val results = mutableListOf<PresenceAssociationResult>()
        adapter.associate(HyphenAssociationRequest(mac, "Mac")) { results += it }
        assertEquals("Mac", backend.lastDisplayName)
        backend.lastCallbacks!!.onAssociated(11, "Mac")
        assertEquals(PresenceAssociationResult.Associated(11), results.single())

        var disassociated = false
        adapter.disassociate(mac) { disassociated = it }
        assertTrue(disassociated)
        assertEquals(listOf(11), backend.disassociated)
    }

    @Test
    fun `disassociate of unknown peer reports false`() {
        var result = true
        LegacyPresenceAdapter(FakeBackend(34)).disassociate(other) { result = it }
        assertEquals(false, result)
    }

    @Test
    fun `legacy presence is a noop subscription`() {
        val sub = LegacyPresenceAdapter(FakeBackend(34))
            .observePresence(mac) { throw AssertionError("legacy must not emit") }
        sub.cancel()
        sub.cancel() // idempotent
    }

    @Test
    fun `api36 presence routes events and dedupes consecutive duplicates`() {
        val source = FakePresenceSource()
        val adapter = Api36PresenceAdapter(FakeBackend(36), source)
        val seen = mutableListOf<PresenceEvent>()
        adapter.observePresence(mac) { seen += it }

        source.emit(PresenceEvent.Appeared(mac), mac)
        source.emit(PresenceEvent.Appeared(mac), mac) // duplicate: swallowed
        source.emit(PresenceEvent.Disappeared(mac), mac)
        source.emit(PresenceEvent.Appeared(mac), mac)

        assertEquals(
            listOf<PresenceEvent>(
                PresenceEvent.Appeared(mac),
                PresenceEvent.Disappeared(mac),
                PresenceEvent.Appeared(mac),
            ),
            seen,
        )
    }

    @Test
    fun `api36 cancel stops observing exactly once`() {
        val source = FakePresenceSource()
        val adapter = Api36PresenceAdapter(FakeBackend(36), source)
        val sub = adapter.observePresence(mac) {}
        sub.cancel()
        sub.cancel()
        assertEquals(listOf(mac), source.stopped)
    }

    @Test
    fun `api36 events for other peers do not leak across observers`() {
        val source = FakePresenceSource()
        val adapter = Api36PresenceAdapter(FakeBackend(36), source)
        val seenMac = mutableListOf<PresenceEvent>()
        adapter.observePresence(mac) { seenMac += it }
        adapter.observePresence(other) { throw AssertionError("other peer must stay silent") }

        source.emit(PresenceEvent.Appeared(mac), mac)
        assertEquals(1, seenMac.size)
    }
}
