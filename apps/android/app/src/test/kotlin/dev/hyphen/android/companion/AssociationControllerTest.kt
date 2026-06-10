package dev.hyphen.android.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeCdmBackend(var sdk: Int) : CdmBackend {
    var associateCalls = 0
    var lastDisplayName: String? = null
    var lastCallbacks: CdmCallbacks? = null
    val disassociated = mutableListOf<Int>()
    var ids: List<Int> = emptyList()

    override fun sdkInt() = sdk

    override fun requestSelfManagedAssociation(displayName: String, callbacks: CdmCallbacks) {
        associateCalls++
        lastDisplayName = displayName
        lastCallbacks = callbacks
    }

    override fun disassociate(associationId: Int) {
        disassociated += associationId
    }

    override fun listAssociationIds(): List<Int> = ids
}

class AssociationControllerTest {

    private val events = mutableListOf<AssociationEvent>()

    private fun controller(backend: FakeCdmBackend) =
        AssociationController(backend) { events += it }

    @Test
    fun `associate below api 33 reports unsupported and never hits cdm`() {
        val backend = FakeCdmBackend(sdk = 32)
        controller(backend).associate("Mac Studio")
        assertEquals(listOf<AssociationEvent>(AssociationEvent.UnsupportedOnThisSdk), events)
        assertEquals(0, backend.associateCalls)
    }

    @Test
    fun `associate on api 33 forwards display name`() {
        val backend = FakeCdmBackend(sdk = 33)
        controller(backend).associate("Mac Studio")
        assertEquals(1, backend.associateCalls)
        assertEquals("Mac Studio", backend.lastDisplayName)
    }

    @Test
    fun `pending approval surfaces a launchable event`() {
        val backend = FakeCdmBackend(sdk = 34)
        controller(backend).associate("Mac")
        var launched = false
        backend.lastCallbacks!!.onPendingApproval { launched = true }
        val pending = events.filterIsInstance<AssociationEvent.PendingUserApproval>().single()
        pending.launch()
        assertTrue(launched)
    }

    @Test
    fun `created association is reported with id and name`() {
        val backend = FakeCdmBackend(sdk = 34)
        controller(backend).associate("Mac")
        backend.lastCallbacks!!.onAssociated(7, "Mac")
        assertEquals(
            AssociationEvent.Associated(7, "Mac"),
            events.filterIsInstance<AssociationEvent.Associated>().single(),
        )
    }

    @Test
    fun `cdm failure is reported`() {
        val backend = FakeCdmBackend(sdk = 34)
        controller(backend).associate("Mac")
        backend.lastCallbacks!!.onFailure("user rejected")
        assertEquals(
            AssociationEvent.Failed("user rejected"),
            events.filterIsInstance<AssociationEvent.Failed>().single(),
        )
    }

    @Test
    fun `disassociate forwards and reports removal on api 33`() {
        val backend = FakeCdmBackend(sdk = 33)
        controller(backend).disassociate(7)
        assertEquals(listOf(7), backend.disassociated)
        assertEquals(
            AssociationEvent.Removed(7),
            events.filterIsInstance<AssociationEvent.Removed>().single(),
        )
    }

    @Test
    fun `disassociate below api 33 is unsupported`() {
        val backend = FakeCdmBackend(sdk = 30)
        controller(backend).disassociate(7)
        assertTrue(backend.disassociated.isEmpty())
        assertEquals(listOf<AssociationEvent>(AssociationEvent.UnsupportedOnThisSdk), events)
    }

    @Test
    fun `associations list is empty below 33 and passthrough at 33`() {
        val legacy = FakeCdmBackend(sdk = 28).apply { ids = listOf(1) }
        assertEquals(emptyList<Int>(), controller(legacy).associations())
        val modern = FakeCdmBackend(sdk = 33).apply { ids = listOf(1, 2) }
        assertEquals(listOf(1, 2), controller(modern).associations())
    }
}
