package dev.hyphen.android.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TransferCheckpointStoreTest {

    @get:org.junit.Rule
    val temp = TemporaryFolder()

    private val peerA = byteArrayOf(0x01, 0x02)
    private val peerB = byteArrayOf(0x03, 0x04)

    @Test
    fun `save load and invalidate peer`() {
        var now = 1_000L
        val store = TransferCheckpointStore(temp.newFolder("checkpoints"), nowMs = { now })
        val manifest = TransferManifest.fromJson(
            manifestPayload(
                fileId = "f_test_checkpoint012345",
                sizeBytes = 3,
                chunkSizeBytes = 1024,
                chunkCount = 1,
            ),
        )
        store.save(
            TransferCheckpointStore.Record(
                fileId = manifest.fileId,
                manifest = manifest,
                peerFingerprintHex = peerA.toHexLower(),
                sessionId = "s_test",
                nextChunkIndex = 1,
                receivedRanges = listOf(0 until 1),
                updatedAtMs = now,
                expiresAtMs = now + TransferCheckpointStore.DEFAULT_TTL_MS,
            ),
        )

        val loaded = store.load(manifest.fileId)
        assertEquals(manifest.fileId, loaded?.fileId)
        assertEquals(1, loaded?.nextChunkIndex)

        store.invalidatePeer(peerA)
        assertNull(store.load(manifest.fileId))

        store.save(
            TransferCheckpointStore.Record(
                fileId = "f_other_peer012345678",
                manifest = manifest.copy(fileId = "f_other_peer012345678"),
                peerFingerprintHex = peerB.toHexLower(),
                sessionId = "s_test",
                nextChunkIndex = 0,
                receivedRanges = emptyList(),
                updatedAtMs = now,
                expiresAtMs = now + TransferCheckpointStore.DEFAULT_TTL_MS,
            ),
        )
        assertEquals(1, store.loadActiveForPeer(peerB).size)
        store.invalidatePeer(peerA)
        assertEquals(1, store.loadActiveForPeer(peerB).size)
    }

    @Test
    fun `purgeExpired removes stale records`() {
        var now = 0L
        val store = TransferCheckpointStore(temp.newFolder("checkpoints"), nowMs = { now })
        val manifest = TransferManifest.fromJson(
            manifestPayload(
                fileId = "f_expired012345678",
                sizeBytes = 0,
                chunkSizeBytes = 1024,
                chunkCount = 0,
            ),
        )
        store.save(
            TransferCheckpointStore.Record(
                fileId = manifest.fileId,
                manifest = manifest,
                peerFingerprintHex = peerA.toHexLower(),
                sessionId = "s_test",
                nextChunkIndex = 0,
                receivedRanges = emptyList(),
                updatedAtMs = now,
                expiresAtMs = now + 10,
            ),
        )
        now = 11
        store.purgeExpired()
        assertTrue(store.loadActiveForPeer(peerA).isEmpty())
    }

    @Test
    fun `save atomically replaces a read-only checkpoint file`() {
        val now = 2_000L
        val root = temp.newFolder("atomic-checkpoints")
        val store = TransferCheckpointStore(root, nowMs = { now })
        val manifest = TransferManifest.fromJson(
            manifestPayload(
                fileId = "f_atomic_checkpoint123",
                sizeBytes = 3,
                chunkSizeBytes = 1024,
                chunkCount = 1,
            ),
        )
        fun record(nextChunkIndex: Int) = TransferCheckpointStore.Record(
            fileId = manifest.fileId,
            manifest = manifest,
            peerFingerprintHex = peerA.toHexLower(),
            sessionId = "s_test",
            nextChunkIndex = nextChunkIndex,
            receivedRanges = if (nextChunkIndex == 0) emptyList() else listOf(0 until 1),
            updatedAtMs = now,
            expiresAtMs = now + TransferCheckpointStore.DEFAULT_TTL_MS,
        )

        store.save(record(0))
        val checkpoint = root.resolve("${manifest.fileId}.json")
        assertTrue("test fixture must become read-only", checkpoint.setReadOnly())
        try {
            store.save(record(1))
        } finally {
            checkpoint.setWritable(true)
        }

        assertEquals(1, store.load(manifest.fileId)?.nextChunkIndex)
        assertEquals(listOf(checkpoint.name), root.list()?.sorted())
    }

    @Test
    fun `opening a store scavenges an orphaned staged checkpoint`() {
        val root = temp.newFolder("orphan-checkpoints")
        val orphan = root.resolve(".checkpoint-crash.tmp").apply { writeText("partial") }
        assertTrue(orphan.setLastModified(System.currentTimeMillis() - 2 * 60 * 60 * 1000))

        TransferCheckpointStore(root)

        assertFalse(orphan.exists())
    }

    @Test
    fun `save syncs the checkpoint directory after atomic replacement`() {
        val root = temp.newFolder("directory-sync-checkpoints")
        var synced: java.nio.file.Path? = null
        val store = TransferCheckpointStore(
            root = root,
            nowMs = { 2_000L },
            syncDirectory = { synced = it },
        )
        val manifest = TransferManifest.fromJson(
            manifestPayload(
                fileId = "f_directory_sync012345",
                sizeBytes = 0,
                chunkSizeBytes = 1024,
                chunkCount = 0,
            ),
        )

        store.save(
            TransferCheckpointStore.Record(
                fileId = manifest.fileId,
                manifest = manifest,
                peerFingerprintHex = peerA.toHexLower(),
                sessionId = "s_test",
                nextChunkIndex = 0,
                receivedRanges = emptyList(),
                updatedAtMs = 2_000L,
                expiresAtMs = 2_000L + TransferCheckpointStore.DEFAULT_TTL_MS,
            ),
        )

        assertEquals(root.toPath(), synced)
        assertEquals(manifest.fileId, store.load(manifest.fileId)?.fileId)
    }

    private fun manifestPayload(
        fileId: String,
        sizeBytes: Long,
        chunkSizeBytes: Int,
        chunkCount: Int,
    ) = dev.hyphen.android.transport.Json.Obj(
        mapOf(
            "fileId" to dev.hyphen.android.transport.Json.Str(fileId),
            "filename" to dev.hyphen.android.transport.Json.Str("a.bin"),
            "sizeBytes" to dev.hyphen.android.transport.Json.Num(sizeBytes.toString()),
            "mimeType" to dev.hyphen.android.transport.Json.Str("application/octet-stream"),
            "sha256" to dev.hyphen.android.transport.Json.Str(
                if (sizeBytes == 0L) {
                    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
                } else {
                    "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20"
                },
            ),
            "chunkSizeBytes" to dev.hyphen.android.transport.Json.Num(chunkSizeBytes.toString()),
            "chunkCount" to dev.hyphen.android.transport.Json.Num(chunkCount.toString()),
        ),
    )

    private fun ByteArray.toHexLower(): String = joinToString("") { "%02x".format(it) }
}
