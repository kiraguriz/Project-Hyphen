package dev.hyphen.android.transfer

import dev.hyphen.android.transport.Envelope
import dev.hyphen.android.transport.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private data class SentTransferEnvelope(
    val type: String,
    val capability: String,
    val requiresAck: Boolean,
    val payload: Json.Obj,
)

private class RecordingTransferOutbox : TransferOutbox {
    val envelopes = mutableListOf<SentTransferEnvelope>()

    override fun send(
        type: String,
        capability: String,
        requiresAck: Boolean,
        payload: Json.Obj,
    ): String {
        envelopes += SentTransferEnvelope(type, capability, requiresAck, payload)
        return "01JZ0000000000000000000000"
    }
}

class TransferMessagesTest {
    @Test
    fun `sender emits manifest first and acked chunks`() {
        val outbox = RecordingTransferOutbox()

        val manifest = TransferSender(outbox).sendBytes(
            filename = "notes.txt",
            mimeType = "text/plain",
            bytes = ByteArray(1500) { it.toByte() },
            chunkSizeBytes = 1024,
            fileId = "f_android_to_mac",
        )

        assertEquals(2, manifest.chunkCount)
        assertEquals(3, outbox.envelopes.size)
        assertEquals(TransferProtocol.TYPE_MANIFEST, outbox.envelopes[0].type)
        assertEquals(TransferProtocol.TYPE_CHUNK, outbox.envelopes[1].type)
        assertTrue(outbox.envelopes.all { it.capability == TransferProtocol.CAPABILITY && it.requiresAck })
    }

    @Test
    fun `receiver reconstructs small files in both directions`() {
        val androidToMac = ByteArray(1500) { (it % 251).toByte() }
        val macToAndroid = "hello back from mac".toByteArray()

        assertArrayEquals(androidToMac, sendThroughReceiver(androidToMac, "f_android_to_mac").bytes)
        assertArrayEquals(macToAndroid, sendThroughReceiver(macToAndroid, "f_mac_to_android").bytes)
    }

    @Test
    fun `receiver rejects corrupted chunk hashes`() {
        val chunk = TransferChunk("f_corrupt_test", 0, "hello".toByteArray()).toJson()
        val badChunk = Json.Obj(chunk.entries + ("chunkSha256" to Json.Str("0".repeat(64))))

        val error = runCatching { TransferChunk.fromJson(badChunk) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `receiver rejects completed files whose sha256 does not match the manifest`() {
        val bytes = ByteArray(1500) { (it % 251).toByte() }
        val outbox = RecordingTransferOutbox()
        TransferSender(outbox).sendBytes(
            filename = "sample.bin",
            mimeType = "application/octet-stream",
            bytes = bytes,
            chunkSizeBytes = 1024,
            fileId = "f_bad_file_hash",
        )
        val receiver = TransferReceiver()
        val first = outbox.envelopes.first()
        val badManifest = first.copy(
            payload = Json.Obj(first.payload.entries + ("sha256" to Json.Str("0".repeat(64)))),
        )
        val error = runCatching {
            (listOf(badManifest) + outbox.envelopes.drop(1)).forEachIndexed { index, sent ->
                receiver.handle(toEnvelope(sent, index))
            }
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `interrupted transfer resumes from receiver checkpoint`() {
        val bytes = ByteArray(2500) { (it % 251).toByte() }
        val firstOutbox = RecordingTransferOutbox()
        val manifest = TransferSender(firstOutbox).sendBytes(
            filename = "sample.bin",
            mimeType = "application/octet-stream",
            bytes = bytes,
            chunkSizeBytes = 1024,
            fileId = "f_resume_test",
        )
        val receiver = TransferReceiver()

        firstOutbox.envelopes.take(2).forEachIndexed { index, sent ->
            receiver.handle(toEnvelope(sent, index))
        }
        val checkpoint = receiver.checkpoint(manifest.fileId)
        assertEquals(1, checkpoint?.nextChunkIndex)

        val resumeOutbox = RecordingTransferOutbox()
        TransferSender(resumeOutbox).sendRemainingBytes(manifest, bytes, checkpoint!!.nextChunkIndex)
        var completed: TransferCompleted? = null
        resumeOutbox.envelopes.forEachIndexed { index, sent ->
            completed = receiver.handle(toEnvelope(sent, index + 2)) ?: completed
        }

        assertArrayEquals(bytes, completed?.bytes)
    }

    @Test
    fun `resume request and info use protocol wire names`() {
        val outbox = RecordingTransferOutbox()
        val sender = TransferSender(outbox)

        sender.requestResume("f_resume_test")
        sender.sendResumeInfo(TransferResumeInfo("f_resume_test", nextChunkIndex = 2))

        assertEquals(TransferProtocol.TYPE_RESUME_REQUEST, outbox.envelopes[0].type)
        assertEquals(Json.Str("f_resume_test"), outbox.envelopes[0].payload["fileId"])
        assertEquals(TransferProtocol.TYPE_RESUME_INFO, outbox.envelopes[1].type)
        assertEquals(Json.Num("2"), outbox.envelopes[1].payload["nextChunkIndex"])
    }

    @Test
    fun `sender and receiver report transfer progress`() {
        val bytes = ByteArray(2500) { (it % 251).toByte() }
        val outbox = RecordingTransferOutbox()
        val sendProgress = mutableListOf<TransferProgress>()
        val manifest = TransferSender(outbox, onProgress = sendProgress::add).sendBytes(
            filename = "sample.bin",
            mimeType = "application/octet-stream",
            bytes = bytes,
            chunkSizeBytes = 1024,
            fileId = "f_progress_test",
        )
        val receiveProgress = mutableListOf<TransferProgress>()
        val receiver = TransferReceiver(onProgress = receiveProgress::add)

        outbox.envelopes.forEachIndexed { index, sent ->
            receiver.handle(toEnvelope(sent, index))
        }

        assertEquals(listOf(0, 1, 2, 3), sendProgress.map { it.completedChunks })
        assertEquals(listOf(0, 1, 2, 3), receiveProgress.map { it.completedChunks })
        assertEquals(bytes.size.toLong(), sendProgress.last().completedBytes)
        assertEquals(manifest.sizeBytes, receiveProgress.last().totalBytes)
        assertTrue(sendProgress.last().isComplete)
    }

    @Test
    fun `cancel message uses protocol wire name and discard clears receiver checkpoint`() {
        val bytes = ByteArray(2500) { (it % 251).toByte() }
        val firstOutbox = RecordingTransferOutbox()
        val manifest = TransferSender(firstOutbox).sendBytes(
            filename = "sample.bin",
            mimeType = "application/octet-stream",
            bytes = bytes,
            chunkSizeBytes = 1024,
            fileId = "f_cancel_test",
        )
        val receiver = TransferReceiver()
        firstOutbox.envelopes.take(2).forEachIndexed { index, sent ->
            receiver.handle(toEnvelope(sent, index))
        }
        assertEquals(1, receiver.checkpoint(manifest.fileId)?.nextChunkIndex)

        val cancelOutbox = RecordingTransferOutbox()
        TransferSender(cancelOutbox).sendCancel(TransferCancel(manifest.fileId, discard = true))
        val cancel = cancelOutbox.envelopes.single()
        assertEquals(TransferProtocol.TYPE_CANCEL, cancel.type)
        assertEquals(Json.Bool(true), cancel.payload["discard"])

        receiver.handle(toEnvelope(cancel, index = 2))

        assertEquals(null, receiver.checkpoint(manifest.fileId))
    }

    private fun sendThroughReceiver(bytes: ByteArray, fileId: String): TransferCompleted {
        val outbox = RecordingTransferOutbox()
        TransferSender(outbox).sendBytes(
            filename = "sample.bin",
            mimeType = "application/octet-stream",
            bytes = bytes,
            chunkSizeBytes = 1024,
            fileId = fileId,
        )
        val receiver = TransferReceiver()
        var completed: TransferCompleted? = null
        outbox.envelopes.forEachIndexed { index, sent ->
            completed = receiver.handle(toEnvelope(sent, index)) ?: completed
        }
        return completed ?: error("transfer did not complete")
    }

    private fun toEnvelope(sent: SentTransferEnvelope, index: Int): Envelope =
        Envelope(
            messageId = "01JZ000000000000000000000$index",
            sessionId = "s_test1",
            type = sent.type,
            capability = sent.capability,
            seq = (index + 1).toLong(),
            sentAtUnixMs = 1_781_020_800_000,
            requiresAck = sent.requiresAck,
            payload = sent.payload,
        )
}
