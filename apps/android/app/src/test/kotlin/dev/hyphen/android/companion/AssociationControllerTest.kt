package dev.hyphen.android.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeCdmBackend(var sdk: Int) : CdmBackend {
    var associateCalls = 0
    var lastDisplayName: String? = null
    var lastCallbacks: CdmCallbacks? = null
    var associateFailure: RuntimeException? = null
    var listFailure: RuntimeException? = null
    val disassociated = mutableListOf<Int>()
    var ids: List<Int> = emptyList()

    override fun sdkInt() = sdk

    override fun requestSelfManagedAssociation(displayName: String, callbacks: CdmCallbacks) {
        associateFailure?.let { throw it }
        associateCalls++
        lastDisplayName = displayName
        lastCallbacks = callbacks
    }

    override fun disassociate(associationId: Int) {
        disassociated += associationId
    }

    override fun listAssociationIds(): List<Int> {
        listFailure?.let { throw it }
        return ids
    }
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
        val approval = AssociationApprovalRequest { _ -> Unit }
        backend.lastCallbacks!!.onPendingApproval(approval)
        val pending = events.filterIsInstance<AssociationEvent.PendingUserApproval>().single()
        assertSame(approval, pending.approval)
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
    fun `synchronous platform association failure is reported instead of escaping`() {
        val backend = FakeCdmBackend(sdk = 34).apply {
            associateFailure = SecurityException("association unavailable")
        }

        controller(backend).associate("Mac")

        assertEquals(
            AssociationEvent.Failed("association unavailable"),
            events.filterIsInstance<AssociationEvent.Failed>().single(),
        )
    }

    @Test
    fun `prepared disassociation does nothing until explicit confirmation`() {
        val backend = FakeCdmBackend(sdk = 33)
        backend.ids = listOf(7)

        val request = controller(backend).prepareDisassociation(7)!!

        assertTrue(backend.disassociated.isEmpty())
        assertTrue(events.filterIsInstance<AssociationEvent.Removed>().isEmpty())

        assertTrue(request.confirm())
        assertEquals(listOf(7), backend.disassociated)
        assertEquals(
            AssociationEvent.Removed(7),
            events.filterIsInstance<AssociationEvent.Removed>().single(),
        )
    }

    @Test
    fun `prepared disassociation is one shot`() {
        val backend = FakeCdmBackend(sdk = 33).apply { ids = listOf(7) }
        val request = controller(backend).prepareDisassociation(7)!!

        assertTrue(request.confirm())
        assertTrue(!request.confirm())
        assertTrue(!request.cancel())
        assertEquals(listOf(7), backend.disassociated)
        assertEquals(1, events.filterIsInstance<AssociationEvent.Removed>().size)
    }

    @Test
    fun `cancelled disassociation cannot later be confirmed`() {
        val backend = FakeCdmBackend(sdk = 33).apply { ids = listOf(7) }
        val request = controller(backend).prepareDisassociation(7)!!

        assertTrue(request.cancel())
        assertTrue(!request.confirm())
        assertTrue(backend.disassociated.isEmpty())
        assertTrue(events.filterIsInstance<AssociationEvent.Removed>().isEmpty())
    }

    @Test
    fun `association disappearing after confirmation prompt is not reported removed`() {
        val backend = FakeCdmBackend(sdk = 33).apply { ids = listOf(7) }
        val request = controller(backend).prepareDisassociation(7)!!
        backend.ids = emptyList()

        assertTrue(request.confirm())

        assertTrue(backend.disassociated.isEmpty())
        assertTrue(events.filterIsInstance<AssociationEvent.Removed>().isEmpty())
        assertEquals(
            AssociationEvent.Failed("association 7 is no longer present"),
            events.filterIsInstance<AssociationEvent.Failed>().single(),
        )
    }

    @Test
    fun `cannot prepare removal for an association that is no longer present`() {
        val backend = FakeCdmBackend(sdk = 33).apply { ids = listOf(8) }

        val request = controller(backend).prepareDisassociation(7)

        assertEquals(null, request)
        assertTrue(backend.disassociated.isEmpty())
        assertEquals(
            AssociationEvent.Failed("association 7 is no longer present"),
            events.filterIsInstance<AssociationEvent.Failed>().single(),
        )
    }

    @Test
    fun `prepare disassociation below api 33 is unsupported`() {
        val backend = FakeCdmBackend(sdk = 30)
        val request = controller(backend).prepareDisassociation(7)

        assertEquals(null, request)
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

    @Test
    fun `orphan association cleanup disassociates without a second user prompt`() {
        val backend = FakeCdmBackend(sdk = 33).apply { ids = listOf(7) }

        controller(backend).removeUnboundAssociation(7)

        assertEquals(listOf(7), backend.disassociated)
        assertEquals(
            AssociationEvent.Removed(7),
            events.filterIsInstance<AssociationEvent.Removed>().single(),
        )
    }

    @Test
    fun `reconciliation snapshot distinguishes list failure from an empty system list`() {
        val empty = FakeCdmBackend(sdk = 33)
        assertEquals(emptySet<Int>(), controller(empty).associationIdsForReconciliation())

        val failed = FakeCdmBackend(sdk = 33).apply {
            listFailure = IllegalStateException("association service unavailable")
        }
        assertEquals(null, controller(failed).associationIdsForReconciliation())
    }

    @Test
    fun `synchronous platform list failure is reported instead of escaping`() {
        val backend = FakeCdmBackend(sdk = 34).apply {
            listFailure = SecurityException("association list unavailable")
        }

        assertEquals(emptyList<Int>(), controller(backend).associations())

        assertEquals(
            AssociationEvent.Failed("association list unavailable"),
            events.filterIsInstance<AssociationEvent.Failed>().single(),
        )
    }

    @Test
    fun `list failure while preparing removal is reported instead of escaping`() {
        val backend = FakeCdmBackend(sdk = 34).apply {
            listFailure = IllegalStateException("association service unavailable")
        }

        assertEquals(null, controller(backend).prepareDisassociation(7))

        assertEquals(
            AssociationEvent.Failed("association service unavailable"),
            events.filterIsInstance<AssociationEvent.Failed>().single(),
        )
    }
}
