package dev.hyphen.android.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class MemoryAssociationBindingStorage : AssociationBindingStorage {
    var entries = emptyMap<String, Int>()
    var failWrites = false

    override fun load(): Map<String, Int> = entries

    override fun save(entries: Map<String, Int>) {
        if (failWrites) throw IllegalStateException("disk full")
        this.entries = entries.toMap()
    }
}

class CompanionAssociationBindingsTest {
    private val peerA = ByteArray(32) { 0x11 }
    private val peerB = ByteArray(32) { 0x22 }

    @Test
    fun `association ids are peer-bound and replacements are atomic at the index seam`() {
        val storage = MemoryAssociationBindingStorage()
        val bindings = CompanionAssociationBindings(storage)

        assertNull(bindings.bind(peerA, 7))
        bindings.bind(peerB, 8)
        assertEquals(7, bindings.bind(peerA, 9))

        assertEquals(9, bindings.associationFor(peerA))
        assertEquals(8, bindings.associationFor(peerB))
        assertEquals(2, storage.entries.size)
    }

    @Test
    fun `rebinding an association id cannot make it identify two trusted peers`() {
        val bindings = CompanionAssociationBindings(MemoryAssociationBindingStorage())

        bindings.bind(peerA, 7)
        bindings.bind(peerB, 7)

        assertNull(bindings.associationFor(peerA))
        assertEquals(7, bindings.associationFor(peerB))
        assertEquals(setOf(7), bindings.associationIds())
    }

    @Test
    fun `association and trust removal clear only their owned mappings`() {
        val bindings = CompanionAssociationBindings(MemoryAssociationBindingStorage())
        bindings.bind(peerA, 7)
        bindings.bind(peerB, 8)

        bindings.removeAssociation(7)
        assertNull(bindings.associationFor(peerA))
        assertEquals(8, bindings.associationFor(peerB))

        bindings.removePeer(peerB)
        assertNull(bindings.associationFor(peerB))
    }

    @Test
    fun `recreated registry reloads a binding written by an older activity before trust removal`() {
        val storage = MemoryAssociationBindingStorage()
        val callbackEra = CompanionAssociationBindings(storage)
        val recreatedActivity = CompanionAssociationBindings(storage)
        assertTrue(recreatedActivity.associationIds().isEmpty())

        callbackEra.bind(peerA, 7)

        assertEquals(7, recreatedActivity.removePeer(peerA))
        assertTrue(storage.entries.isEmpty())
    }

    @Test
    fun `recreated registry clear returns every late durable association`() {
        val storage = MemoryAssociationBindingStorage()
        val callbackEra = CompanionAssociationBindings(storage)
        val recreatedActivity = CompanionAssociationBindings(storage)
        callbackEra.bind(peerA, 7)
        callbackEra.bind(peerB, 8)

        assertEquals(setOf(7, 8), recreatedActivity.clear())
        assertTrue(storage.entries.isEmpty())
    }

    @Test
    fun `failed durable replacement does not publish a phantom in-memory binding`() {
        val storage = MemoryAssociationBindingStorage()
        val bindings = CompanionAssociationBindings(storage)
        bindings.bind(peerA, 7)
        storage.failWrites = true

        val replacement = runCatching { bindings.bind(peerA, 9) }

        assertTrue(replacement.isFailure)
        assertEquals(7, bindings.associationFor(peerA))
        assertEquals(mapOf(peerA.toAssociationFingerprintHex() to 7), storage.entries)
    }

    @Test
    fun `rebinding the same platform association reports no displaced orphan`() {
        val bindings = CompanionAssociationBindings(MemoryAssociationBindingStorage())

        assertNull(bindings.bind(peerA, 7))
        assertNull(bindings.bind(peerA, 7))
    }

    @Test
    fun `reconcile removes bindings deleted through system settings`() {
        val storage = MemoryAssociationBindingStorage()
        val bindings = CompanionAssociationBindings(storage)
        bindings.bind(peerA, 7)
        bindings.bind(peerB, 8)

        val removed = bindings.reconcile(setOf(8, 99))

        assertEquals(setOf(7), removed)
        assertNull(bindings.associationFor(peerA))
        assertEquals(8, bindings.associationFor(peerB))
        assertEquals(mapOf(peerB.toAssociationFingerprintHex() to 8), storage.entries)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid fingerprints are never accepted as association identity`() {
        CompanionAssociationBindings(MemoryAssociationBindingStorage()).bind(ByteArray(3), 7)
    }
}
