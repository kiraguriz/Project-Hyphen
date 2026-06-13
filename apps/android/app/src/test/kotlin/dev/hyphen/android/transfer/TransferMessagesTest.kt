package dev.hyphen.android.transfer

import dev.hyphen.android.transport.Envelope
import dev.hyphen.android.transport.Json
import dev.hyphen.android.transport.SessionHandshake
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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
        val id = "sent-${envelopes.size}"
        envelopes += SentTransferEnvelope(type, capability, requiresAck, payload)
        return id
    }
}

class TransferMessagesTest {
    @Rule
    @JvmField
    val temp = TemporaryFolder()

    @Test
    fun `sender emits manifest first and acked chunks`() {
        val outbox = RecordingTransferOutbox()
        val bytes = ByteArray(1500) { it.toByte() }

        val manifest = TransferSender(outbox).sendSource(
            filename = "notes.txt",
            mimeType = "text/plain",
            source = source(bytes),
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

        assertEquals(androidToMac.toList(), sendThroughReceiver(androidToMac, "f_android_to_mac").file.readBytes().toList())
        assertEquals(macToAndroid.toList(), sendThroughReceiver(macToAndroid, "f_mac_to_android").file.readBytes().toList())
    }

    @Test
    fun `receiver completes empty files without chunks`() {
        val outbox = RecordingTransferOutbox()
        TransferSender(outbox).sendSource(
            filename = "empty.txt",
            mimeType = "text/plain",
            source = source(ByteArray(0)),
            chunkSizeBytes = 1024,
            fileId = "f_empty_file",
        )

        val event = receiver().handle(toEnvelope(outbox.envelopes.single(), index = 0))

        val completed = (event as TransferEvent.Completed).completed
        assertEquals(0L, completed.file.length())
        assertEquals(completed.manifest.sha256, completed.sha256)
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
        TransferSender(outbox).sendSource(
            filename = "sample.bin",
            mimeType = "application/octet-stream",
            source = source(bytes),
            chunkSizeBytes = 1024,
            fileId = "f_bad_file_hash",
        )
        val receiver = receiver()
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
        val outbox = RecordingTransferOutbox()
        val sender = TransferSender(outbox)
        val manifest = sender.sendSource(
            filename = "sample.bin",
            mimeType = "application/octet-stream",
            source = source(bytes),
            chunkSizeBytes = 1024,
            fileId = "f_resume_test",
        )
        val receiver = receiver()

        outbox.envelopes.take(2).forEachIndexed { index, sent ->
            receiver.handle(toEnvelope(sent, index))
        }
        outbox.envelopes.clear()

        sender.requestResume(manifest.fileId)
        val resumeRequest = outbox.envelopes.single()
        val resumeEvent = receiver.handle(toEnvelope(resumeRequest, index = 2))
        val info = (resumeEvent as TransferEvent.ResumeRequested).info
        assertEquals(1, info.nextChunkIndex)
        outbox.envelopes.clear()

        sender.handleResumeInfo(info)
        var completed: TransferCompleted? = null
        outbox.envelopes.forEachIndexed { index, sent ->
            val event = receiver.handle(toEnvelope(sent, index + 3))
            if (event is TransferEvent.Completed) completed = event.completed
        }

        assertEquals(bytes.toList(), completed?.file?.readBytes()?.toList())
    }

    @Test
    fun `duplicate manifest is idempotent and keeps received chunks`() {
        val bytes = ByteArray(2500) { (it % 251).toByte() }
        val outbox = RecordingTransferOutbox()
        val manifest = TransferSender(outbox).sendSource(
            filename = "sample.bin",
            mimeType = "application/octet-stream",
            source = source(bytes),
            chunkSizeBytes = 1024,
            fileId = "f_duplicate_manifest",
        )
        val receiver = receiver()

        receiver.handle(toEnvelope(outbox.envelopes[0], index = 0))
        receiver.handle(toEnvelope(outbox.envelopes[1], index = 1))
        receiver.handle(toEnvelope(outbox.envelopes[0], index = 2))

        assertEquals(1, receiver.checkpoint(manifest.fileId)?.nextChunkIndex)
        var completed: TransferCompleted? = null
        outbox.envelopes.drop(2).forEachIndexed { index, sent ->
            val event = receiver.handle(toEnvelope(sent, index + 3))
            if (event is TransferEvent.Completed) completed = event.completed
        }
        assertEquals(bytes.toList(), completed?.file?.readBytes()?.toList())
    }

    @Test
    fun `duplicate manifest with changed payload is rejected`() {
        val outbox = RecordingTransferOutbox()
        TransferSender(outbox).sendSource(
            filename = "sample.bin",
            mimeType = "application/octet-stream",
            source = source(ByteArray(1500) { it.toByte() }),
            chunkSizeBytes = 1024,
            fileId = "f_changed_manifest",
        )
        val receiver = receiver()
        receiver.handle(toEnvelope(outbox.envelopes[0], index = 0))
        val changed = outbox.envelopes[0].copy(
            payload = Json.Obj(outbox.envelopes[0].payload.entries + ("filename" to Json.Str("other.bin"))),
        )

        assertRejected("changed manifest") {
            receiver.handle(toEnvelope(changed, index = 1))
        }
    }

    @Test
    fun `unknown resume asks sender to resend manifest before chunks`() {
        val bytes = ByteArray(1500) { (it % 251).toByte() }
        val receiver = receiver()
        val requestOutbox = RecordingTransferOutbox()
        val originalSender = TransferSender(requestOutbox)
        val manifest = originalSender.sendSource(
            filename = "sample.bin",
            mimeType = "application/octet-stream",
            source = source(bytes),
            chunkSizeBytes = 1024,
            fileId = "f_unknown_resume",
        )
        requestOutbox.envelopes.clear()

        originalSender.requestResume(manifest.fileId)
        val info = (receiver.handle(toEnvelope(requestOutbox.envelopes.single(), index = 0)) as TransferEvent.ResumeRequested).info
        assertTrue(info.needsManifest)

        originalSender.handleResumeInfo(info)
        assertEquals(TransferProtocol.TYPE_MANIFEST, requestOutbox.envelopes[1].type)
        assertEquals(TransferProtocol.TYPE_CHUNK, requestOutbox.envelopes[2].type)
    }

    @Test
    fun `resume request for completed transfer returns final checkpoint without manifest`() {
        val bytes = ByteArray(2500) { (it % 251).toByte() }
        val outbox = RecordingTransferOutbox()
        val sender = TransferSender(outbox)
        val manifest = sender.sendSource(
            filename = "sample.bin",
            mimeType = "application/octet-stream",
            source = source(bytes),
            chunkSizeBytes = 1024,
            fileId = "f_completed_resume",
        )
        val receiver = receiver()
        outbox.envelopes.forEachIndexed { index, sent ->
            receiver.handle(toEnvelope(sent, index))
        }
        outbox.envelopes.clear()

        sender.requestResume(manifest.fileId)
        val info = (receiver.handle(toEnvelope(outbox.envelopes.single(), index = 0)) as TransferEvent.ResumeRequested).info

        assertEquals(manifest.chunkCount, info.nextChunkIndex)
        assertFalse(info.needsManifest)
        sender.handleResumeInfo(info)
        assertEquals(1, outbox.envelopes.size)
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
        assertEquals(null, outbox.envelopes[1].payload["needsManifest"])
    }

    @Test
    fun `sender uses negotiated transfer max chunk size and rejects unsupported transfer`() {
        val outbox = RecordingTransferOutbox()
        val bytes = ByteArray(3000) { it.toByte() }
        val capped = TransferSender(
            outbox,
            negotiatedCapabilities = SessionHandshake.NegotiatedCapabilities.advertised(maxTransferChunkBytes = 1024),
        ).sendSource(
            filename = "sample.bin",
            mimeType = "application/octet-stream",
            source = source(bytes),
            chunkSizeBytes = 2048,
            fileId = "f_negotiated_chunk",
        )

        assertEquals(1024, capped.chunkSizeBytes)
        assertRejected("unsupported transfer") {
            TransferSender(
                RecordingTransferOutbox(),
                negotiatedCapabilities = SessionHandshake.NegotiatedCapabilities.empty(),
            ).sendSource(
                filename = "sample.bin",
                mimeType = "application/octet-stream",
                source = source(bytes),
                chunkSizeBytes = 1024,
                fileId = "f_unsupported_transfer",
            )
        }
    }

    @Test
    fun `sender and receiver report transfer progress`() {
        val bytes = ByteArray(2500) { (it % 251).toByte() }
        val outbox = RecordingTransferOutbox()
        val sendProgress = mutableListOf<TransferProgress>()
        val manifest = TransferSender(outbox, onProgress = sendProgress::add).sendSource(
            filename = "sample.bin",
            mimeType = "application/octet-stream",
            source = source(bytes),
            chunkSizeBytes = 1024,
            fileId = "f_progress_test",
        )
        val receiveProgress = mutableListOf<TransferProgress>()
        val receiver = receiver(onProgress = receiveProgress::add)

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
        val manifest = TransferSender(firstOutbox).sendSource(
            filename = "sample.bin",
            mimeType = "application/octet-stream",
            source = source(bytes),
            chunkSizeBytes = 1024,
            fileId = "f_cancel_test",
        )
        val receiver = receiver()
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

    @Test
    fun `receiver rejects chunk sizes that do not match the manifest`() {
        val bytes = ByteArray(2500) { (it % 251).toByte() }
        val outbox = RecordingTransferOutbox()
        val manifest = TransferSender(outbox).sendSource(
            filename = "sample.bin",
            mimeType = "application/octet-stream",
            source = source(bytes),
            chunkSizeBytes = 1024,
            fileId = "f_bad_chunk_size",
        )
        val receiver = receiver()
        receiver.handle(toEnvelope(outbox.envelopes.first(), index = 0))

        assertRejected("short non-final chunk") {
            receiver.handle(toEnvelope(chunk(manifest.fileId, 0, ByteArray(1000)), index = 1))
        }
        assertRejected("oversized non-final chunk") {
            receiver.handle(toEnvelope(chunk(manifest.fileId, 0, ByteArray(1025)), index = 2))
        }
        assertRejected("wrong final chunk") {
            receiver.handle(toEnvelope(chunk(manifest.fileId, 2, ByteArray(500)), index = 3))
        }
    }

    private fun sendThroughReceiver(bytes: ByteArray, fileId: String): TransferCompleted {
        val outbox = RecordingTransferOutbox()
        TransferSender(outbox).sendSource(
            filename = "sample.bin",
            mimeType = "application/octet-stream",
            source = source(bytes),
            chunkSizeBytes = 1024,
            fileId = fileId,
        )
        val receiver = receiver()
        var completed: TransferCompleted? = null
        outbox.envelopes.forEachIndexed { index, sent ->
            val event = receiver.handle(toEnvelope(sent, index))
            if (event is TransferEvent.Completed) completed = event.completed
        }
        return completed ?: error("transfer did not complete")
    }

    @Test
    fun `sender honors bounded outstanding window and advances on ack`() {
        val bytes = ByteArray(5000) { (it % 251).toByte() }
        val outbox = RecordingTransferOutbox()
        val sender = TransferSender(outbox, outstandingWindow = 2)

        sender.sendSource(
            filename = "sample.bin",
            mimeType = "application/octet-stream",
            source = source(bytes),
            chunkSizeBytes = 1024,
            fileId = "f_window_test",
        )

        // Only the manifest plus a window's worth of chunks go out before any ack.
        assertEquals(
            listOf(TransferProtocol.TYPE_MANIFEST, TransferProtocol.TYPE_CHUNK, TransferProtocol.TYPE_CHUNK),
            outbox.envelopes.map { it.type },
        )

        sender.handleAck("sent-1")
        assertEquals(4, outbox.envelopes.size)
        assertEquals(TransferProtocol.TYPE_CHUNK, outbox.envelopes[3].type)
        sender.handleAck("sent-2")
        assertEquals(5, outbox.envelopes.size)
        sender.handleAck("sent-3")
        assertEquals(6, outbox.envelopes.size) // 5000 bytes / 1024 = 5 chunks, all now sent
        sender.handleAck("sent-4")
        sender.handleAck("sent-5")
    }

    @Test
    fun `windowed sender completes a multi-window transfer when acks drive the pump`() {
        val bytes = ByteArray(10_000) { (it % 251).toByte() } // 10 chunks @1024 > window 3
        val outbox = RecordingTransferOutbox()
        val sender = TransferSender(outbox, outstandingWindow = 3)
        val manifest = sender.sendSource(
            filename = "big.bin",
            mimeType = "application/octet-stream",
            source = source(bytes),
            chunkSizeBytes = 1024,
            fileId = "f_windowed_loopback",
        )
        assertEquals(10, manifest.chunkCount)
        assertEquals(1 + 3, outbox.envelopes.size) // manifest + one window of chunks

        val receiver = receiver()
        var completed: TransferCompleted? = null
        var processed = 0
        // Drain: reconstruct each new envelope through the receiver and ack chunks
        // back so the bounded window keeps advancing until the file completes.
        while (processed < outbox.envelopes.size) {
            val sent = outbox.envelopes[processed]
            val event = receiver.handle(toEnvelope(sent, processed))
            if (event is TransferEvent.Completed) completed = event.completed
            val isChunk = sent.type == TransferProtocol.TYPE_CHUNK
            val ackId = "sent-$processed"
            processed += 1
            if (isChunk) sender.handleAck(ackId)
        }

        assertEquals(1 + 10, outbox.envelopes.size)
        assertEquals(bytes.toList(), completed?.file?.readBytes()?.toList())
    }

    @Test
    fun `stream source reports size and re-opens a fresh stream each call`() {
        val bytes = ByteArray(2048) { it.toByte() }
        var opens = 0
        val src = StreamTransferByteSource(bytes.size.toLong()) {
            opens += 1
            java.io.ByteArrayInputStream(bytes)
        }

        assertEquals(2048L, src.sizeBytes)
        assertEquals(source(bytes).sha256Hex(), src.sha256Hex())
        assertTrue(src.openStream().readBytes().contentEquals(bytes))
        assertEquals(2, opens)
    }

    private fun source(bytes: ByteArray): FileTransferByteSource {
        val file = temp.newFile("source-${System.nanoTime()}.bin")
        file.writeBytes(bytes)
        return FileTransferByteSource(file)
    }

    private fun receiver(
        onProgress: (TransferProgress) -> Unit = {},
    ): TransferReceiver =
        TransferReceiver(FileTransferStorage(temp.newFolder("receive-${System.nanoTime()}")), onProgress)

    private fun chunk(fileId: String, chunkIndex: Int, bytes: ByteArray): SentTransferEnvelope =
        SentTransferEnvelope(
            type = TransferProtocol.TYPE_CHUNK,
            capability = TransferProtocol.CAPABILITY,
            requiresAck = true,
            payload = TransferChunk(fileId, chunkIndex, bytes).toJson(),
        )

    private fun assertRejected(label: String, block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        if (error !is IllegalArgumentException) fail("$label should reject with IllegalArgumentException")
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
