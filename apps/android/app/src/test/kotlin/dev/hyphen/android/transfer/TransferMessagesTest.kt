package dev.hyphen.android.transfer

import dev.hyphen.android.transport.Envelope
import dev.hyphen.android.transport.Json
import dev.hyphen.android.transport.ProtocolSession
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

private class OneShotFlowControlledTransferOutbox(
    private val rejectedAttempt: Int,
) : TransferOutbox {
    val envelopes = mutableListOf<SentTransferEnvelope>()
    private var attempts = 0

    override fun send(
        type: String,
        capability: String,
        requiresAck: Boolean,
        payload: Json.Obj,
    ): String {
        if (attempts++ == rejectedAttempt) {
            throw ProtocolSession.SendRejected(
                code = "protocol/flow-control",
                detail = "test ack window full",
            )
        }
        val id = "sent-${envelopes.size}"
        envelopes += SentTransferEnvelope(type, capability, requiresAck, payload)
        return id
    }
}

private class GatedFlowControlledTransferOutbox : TransferOutbox {
    val envelopes = mutableListOf<SentTransferEnvelope>()
    var accepting = false

    override fun send(
        type: String,
        capability: String,
        requiresAck: Boolean,
        payload: Json.Obj,
    ): String {
        if (!accepting) {
            throw ProtocolSession.SendRejected(
                code = "protocol/flow-control",
                detail = "test ack window full",
            )
        }
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
    fun `sender waits for manifest ack before emitting chunks`() {
        val outbox = RecordingTransferOutbox()
        val bytes = ByteArray(1500) { it.toByte() }
        val sender = TransferSender(outbox)

        val manifest = sender.sendSource(
            filename = "notes.txt",
            mimeType = "text/plain",
            source = source(bytes),
            chunkSizeBytes = 1024,
            fileId = "f_android_to_mac",
        )

        assertEquals(2, manifest.chunkCount)
        assertEquals(listOf(TransferProtocol.TYPE_MANIFEST), outbox.envelopes.map { it.type })

        sender.handleAck("sent-0")

        assertEquals(3, outbox.envelopes.size)
        assertEquals(TransferProtocol.TYPE_MANIFEST, outbox.envelopes[0].type)
        assertEquals(TransferProtocol.TYPE_CHUNK, outbox.envelopes[1].type)
        assertTrue(outbox.envelopes.all { it.capability == TransferProtocol.CAPABILITY && it.requiresAck })
    }

    @Test
    fun `manifest rejects control characters in filename`() {
        val invalidNames = listOf("foo\u0000bar", "foo\u001fbar", "foo\u007fbar", "foo\u0085bar")

        invalidNames.forEach { filename ->
            assertRejected("control filename") {
                TransferManifest.fromSource(
                    filename = filename,
                    mimeType = "text/plain",
                    source = source(ByteArray(0)),
                    chunkSizeBytes = 1024,
                    fileId = "f_control_filename",
                )
            }
        }
    }

    @Test
    fun `manifest accepts unicode format characters outside c0 and c1`() {
        TransferManifest.fromSource(
            filename = "family-\uD83D\uDC69\u200D\uD83D\uDCBB.txt",
            mimeType = "text/plain",
            source = source(ByteArray(0)),
            chunkSizeBytes = 1024,
            fileId = "f_unicode_format_filename",
        )
        TransferManifest.fromSource(
            filename = "\u200B",
            mimeType = "text/plain",
            source = source(ByteArray(0)),
            chunkSizeBytes = 1024,
            fileId = "f_zero_width_filename",
        )
    }

    @Test
    fun `manifest rejects ascii-space-only and unpaired-surrogate filenames`() {
        listOf(" ", "\uD800").forEach { filename ->
            assertRejected("invalid scalar or blank filename") {
                TransferManifest.fromSource(
                    filename = filename,
                    mimeType = "text/plain",
                    source = source(ByteArray(0)),
                    chunkSizeBytes = 1024,
                    fileId = "f_invalid_unicode_filename",
                )
            }
        }
    }

    @Test
    fun `manifest filename length is counted in unicode scalar values`() {
        val emoji255 = buildString { repeat(255) { appendCodePoint(0x1F600) } }
        val emoji256 = buildString { repeat(256) { appendCodePoint(0x1F600) } }
        val combining255 = "e\u0301".repeat(255)

        TransferManifest.fromSource(
            filename = emoji255,
            mimeType = "text/plain",
            source = source(ByteArray(0)),
            chunkSizeBytes = 1024,
            fileId = "f_codepoint_boundary",
        )
        listOf(emoji256, combining255).forEach { filename ->
            assertRejected("code point filename boundary") {
                TransferManifest.fromSource(
                    filename = filename,
                    mimeType = "text/plain",
                    source = source(ByteArray(0)),
                    chunkSizeBytes = 1024,
                    fileId = "f_codepoint_boundary",
                )
            }
        }
    }

    @Test
    fun `sender retries a flow-controlled chunk without relabeling later bytes`() {
        val bytes = ByteArray(2500) { (it % 251).toByte() }
        val outbox = OneShotFlowControlledTransferOutbox(rejectedAttempt = 1)
        val sender = TransferSender(outbox)

        sender.sendSource(
            filename = "sample.bin",
            mimeType = "application/octet-stream",
            source = source(bytes),
            chunkSizeBytes = 1024,
            fileId = "f_flow_controlled_chunk",
        )
        assertEquals(listOf(TransferProtocol.TYPE_MANIFEST), outbox.envelopes.map { it.type })

        sender.handleAck("sent-0")
        sender.handleAckTimeout("unrelated-session-message")

        val receiver = receiver()
        var completed: TransferCompleted? = null
        outbox.envelopes.forEachIndexed { index, sent ->
            val event = receiver.handle(toEnvelope(sent, index))
            if (event is TransferEvent.Completed) completed = event.completed
        }
        assertEquals(bytes.toList(), completed?.file?.readBytes()?.toList())
    }

    @Test
    fun `sender retries a flow-controlled manifest after timeout frees capacity`() {
        val outbox = OneShotFlowControlledTransferOutbox(rejectedAttempt = 0)
        val sender = TransferSender(outbox)

        sender.sendSource(
            filename = "sample.bin",
            mimeType = "application/octet-stream",
            source = source(ByteArray(8) { it.toByte() }),
            chunkSizeBytes = 1024,
            fileId = "f_flow_controlled_manifest",
        )
        assertTrue(outbox.envelopes.isEmpty())

        sender.handleAckTimeout("unrelated-session-message")

        assertEquals(listOf(TransferProtocol.TYPE_MANIFEST), outbox.envelopes.map { it.type })
        sender.handleAck("sent-0")

        assertEquals(
            listOf(TransferProtocol.TYPE_MANIFEST, TransferProtocol.TYPE_CHUNK),
            outbox.envelopes.map { it.type },
        )
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
        val sender = TransferSender(outbox)
        sender.sendSource(
            filename = "sample.bin",
            mimeType = "application/octet-stream",
            source = source(bytes),
            chunkSizeBytes = 1024,
            fileId = "f_bad_file_hash",
        )
        sender.handleAck("sent-0")
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
        sender.handleAck("sent-0")
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
        val sender = TransferSender(outbox)
        val manifest = sender.sendSource(
            filename = "sample.bin",
            mimeType = "application/octet-stream",
            source = source(bytes),
            chunkSizeBytes = 1024,
            fileId = "f_duplicate_manifest",
        )
        sender.handleAck("sent-0")
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
        originalSender.handleAck("sent-1")
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
        sender.handleAck("sent-0")
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
    fun `receiver confirmed completion releases sender registry and durable checkpoint`() {
        val outbox = RecordingTransferOutbox()
        val checkpointStore = TransferCheckpointStore(temp.newFolder("completed-sender-checkpoint"))
        val sender = TransferSender(
            outbox = outbox,
            limits = TransferResourceLimits(maxSenderRegistrySize = 1),
            checkpointStore = checkpointStore,
        )
        sender.bindSession(ByteArray(32) { 4 }, "s_completed_sender")
        val first = sender.sendSource(
            filename = "first.bin",
            mimeType = "application/octet-stream",
            source = source(ByteArray(16) { it.toByte() }),
            chunkSizeBytes = 1024,
            fileId = "f_completed_sender_one",
        )
        assertTrue(checkpointStore.load(first.fileId) != null)

        sender.handleResumeInfo(TransferResumeInfo(first.fileId, first.chunkCount))

        assertEquals(null, checkpointStore.load(first.fileId))
        sender.sendSource(
            filename = "second.bin",
            mimeType = "application/octet-stream",
            source = source(ByteArray(16) { (it + 1).toByte() }),
            chunkSizeBytes = 1024,
            fileId = "f_completed_sender_two",
        )
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
    fun `flow controlled transfer control retries after ack capacity frees`() {
        val outbox = OneShotFlowControlledTransferOutbox(rejectedAttempt = 0)
        val sender = TransferSender(outbox)
        val info = TransferResumeInfo("f_control_retry", nextChunkIndex = 2)

        val initial = sender.sendResumeInfo(info)

        assertEquals(null, initial)
        assertTrue(outbox.envelopes.isEmpty())
        sender.handleAck("unrelated-capacity-release")
        assertEquals(listOf(TransferProtocol.TYPE_RESUME_INFO), outbox.envelopes.map { it.type })
        assertEquals(info.toJson(), outbox.envelopes.single().payload)
    }

    @Test
    fun `timed out transfer control is retried without being dropped`() {
        val outbox = RecordingTransferOutbox()
        val sender = TransferSender(outbox)
        val cancel = TransferCancel("f_control_timeout", discard = true)

        val firstId = sender.sendCancel(cancel)
        sender.handleAckTimeout(firstId!!)

        assertEquals(2, outbox.envelopes.size)
        assertEquals(outbox.envelopes[0].payload, outbox.envelopes[1].payload)
        assertEquals(TransferProtocol.TYPE_CANCEL, outbox.envelopes[1].type)
    }

    @Test
    fun `timed out transfer control stops after three wire attempts`() {
        val outbox = RecordingTransferOutbox()
        val sender = TransferSender(outbox)
        sender.sendCancel(TransferCancel("f_control_retry_limit", discard = true))

        repeat(8) { index -> sender.handleAckTimeout("sent-$index") }

        assertEquals(3, outbox.envelopes.size)
        assertTrue(outbox.envelopes.all { it.type == TransferProtocol.TYPE_CANCEL })
    }

    @Test
    fun `flow controlled transfer control queue stays bounded`() {
        val outbox = GatedFlowControlledTransferOutbox()
        val sender = TransferSender(outbox)

        repeat(65) { index ->
            sender.sendResumeInfo(TransferResumeInfo("f_bounded_control_$index", nextChunkIndex = 0))
        }
        assertTrue(outbox.envelopes.isEmpty())

        outbox.accepting = true
        sender.handleAck("unrelated-capacity-release")
        repeat(64) { index -> sender.handleAck("sent-$index") }

        assertEquals(64, outbox.envelopes.size)
        assertEquals("f_bounded_control_63", outbox.envelopes.last().payload["fileId"]?.let {
            (it as Json.Str).value
        })
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
        val sender = TransferSender(outbox, onProgress = sendProgress::add)
        val manifest = sender.sendSource(
            filename = "sample.bin",
            mimeType = "application/octet-stream",
            source = source(bytes),
            chunkSizeBytes = 1024,
            fileId = "f_progress_test",
        )
        val receiveProgress = mutableListOf<TransferProgress>()
        val receiver = receiver(onProgress = receiveProgress::add)

        assertEquals(listOf(0), sendProgress.map { it.completedChunks })
        sender.handleAck("sent-0")

        var processed = 0
        while (processed < outbox.envelopes.size) {
            val sent = outbox.envelopes[processed]
            receiver.handle(toEnvelope(sent, processed))
            if (sent.type == TransferProtocol.TYPE_CHUNK) {
                sender.handleAck("sent-$processed")
            }
            processed += 1
        }

        assertEquals(listOf(0, 1, 2, 3), sendProgress.map { it.completedChunks })
        assertEquals(listOf(0, 1, 2, 3), receiveProgress.map { it.completedChunks })
        assertTrue(sendProgress.all { (it.sentChunks ?: 0) >= it.completedChunks })
        assertEquals(bytes.size.toLong(), sendProgress.last().completedBytes)
        assertEquals(manifest.sizeBytes, receiveProgress.last().totalBytes)
        assertTrue(sendProgress.last().isComplete)
    }

    @Test
    fun `duplicate and out of order chunks keep contiguous checkpoint separate`() {
        val bytes = ByteArray(2500) { (it % 251).toByte() }
        val outbox = RecordingTransferOutbox()
        val sender = TransferSender(outbox)
        val manifest = sender.sendSource(
            filename = "sample.bin",
            mimeType = "application/octet-stream",
            source = source(bytes),
            chunkSizeBytes = 1024,
            fileId = "f_out_of_order",
        )
        sender.handleAck("sent-0")
        val receiveProgress = mutableListOf<TransferProgress>()
        val receiver = receiver(onProgress = receiveProgress::add)
        receiver.handle(toEnvelope(outbox.envelopes[0], index = 0))
        receiver.handle(toEnvelope(outbox.envelopes[1], index = 1)) // chunk 0
        receiver.handle(toEnvelope(outbox.envelopes[1], index = 2)) // duplicate chunk 0
        receiver.handle(toEnvelope(outbox.envelopes[3], index = 3)) // chunk 2 out of order

        assertEquals(1, receiver.checkpoint(manifest.fileId)?.nextChunkIndex)
        val progress = receiveProgress.last()
        assertEquals(1, progress.completedChunks)
        assertEquals(2, progress.receivedChunks)

        receiver.handle(toEnvelope(outbox.envelopes[2], index = 4)) // chunk 1 fills gap
        assertEquals(3, receiver.checkpoint(manifest.fileId)?.nextChunkIndex)
    }

    @Test
    fun `payload is synced before durable checkpoint advances`() {
        val bytes = ByteArray(1500) { (it % 251).toByte() }
        val outbox = RecordingTransferOutbox()
        val sender = TransferSender(outbox)
        val manifest = sender.sendSource(
            filename = "durable.bin",
            mimeType = "application/octet-stream",
            source = source(bytes),
            chunkSizeBytes = 1024,
            fileId = "f_payload_sync_order",
        )
        sender.handleAck("sent-0")
        val store = TransferCheckpointStore(temp.newFolder("payload-sync-checkpoints"))
        var observedSync = false
        val receiver = TransferReceiver(
            storage = FileTransferStorage(temp.newFolder("payload-sync-storage")),
            checkpointStore = store,
            onInboundPayloadSynced = { fileId ->
                observedSync = true
                assertEquals(fileId, manifest.fileId)
                assertEquals(0, store.load(fileId)?.nextChunkIndex)
            },
        )
        receiver.bindSession(ByteArray(32) { 5 }, "s_payload_sync")
        receiver.handle(toEnvelope(outbox.envelopes[0], index = 0))

        receiver.handle(toEnvelope(outbox.envelopes[1], index = 1))

        assertTrue(observedSync)
        assertEquals(1, store.load(manifest.fileId)?.nextChunkIndex)
    }

    @Test
    fun `receiver enforces concurrent transfer and staging quotas`() {
        val limits = TransferResourceLimits(
            maxConcurrentTransfers = 1,
            maxStagingBytes = 2048,
            maxCompletedEntries = 1,
        )
        val receiver = receiver(limits = limits)
        val first = manifestPayload("f_quota_one", sizeBytes = 1500, chunkSizeBytes = 1024, chunkCount = 2)
        val second = manifestPayload("f_quota_two", sizeBytes = 1500, chunkSizeBytes = 1024, chunkCount = 2)

        receiver.handle(
            toEnvelope(
                SentTransferEnvelope(TransferProtocol.TYPE_MANIFEST, TransferProtocol.CAPABILITY, true, first),
                index = 0,
            ),
        )
        assertRejected("concurrent transfer quota") {
            receiver.handle(
                toEnvelope(
                    SentTransferEnvelope(TransferProtocol.TYPE_MANIFEST, TransferProtocol.CAPABILITY, true, second),
                    index = 1,
                ),
            )
        }
    }

    @Test
    fun `pending completed delivery remains charged to staging bytes`() {
        val bytes = ByteArray(1500) { (it % 251).toByte() }
        val outbox = RecordingTransferOutbox()
        val sender = TransferSender(outbox)
        sender.sendSource(
            filename = "pending.bin",
            mimeType = "application/octet-stream",
            source = source(bytes),
            chunkSizeBytes = 1024,
            fileId = "f_pending_byte_quota",
        )
        sender.handleAck("sent-0")
        val receiver = receiver(
            limits = TransferResourceLimits(maxStagingBytes = bytes.size.toLong()),
        )
        outbox.envelopes.forEachIndexed { index, sent ->
            receiver.handle(toEnvelope(sent, index))
        }

        val next = manifestPayload(
            fileId = "f_after_pending_quota",
            sizeBytes = 1,
            chunkSizeBytes = 1024,
            chunkCount = 1,
        )
        val error = runCatching {
            receiver.handle(
                toEnvelope(
                    SentTransferEnvelope(
                        TransferProtocol.TYPE_MANIFEST,
                        TransferProtocol.CAPABILITY,
                        true,
                        next,
                    ),
                    index = outbox.envelopes.size,
                ),
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals("plugin/disk-full", error?.message)
    }

    @Test
    fun `new receive evicts oldest delivered tombstone at completed entry limit`() {
        val bytes = ByteArray(32) { it.toByte() }
        val outbox = RecordingTransferOutbox()
        val sender = TransferSender(outbox)
        val first = sender.sendSource(
            filename = "first.bin",
            mimeType = "application/octet-stream",
            source = source(bytes),
            chunkSizeBytes = 1024,
            fileId = "f_terminal_quota_one",
        )
        sender.handleAck("sent-0")
        val receiver = receiver(
            limits = TransferResourceLimits(maxCompletedEntries = 1),
        )
        outbox.envelopes.forEachIndexed { index, sent ->
            receiver.handle(toEnvelope(sent, index))
        }
        receiver.markDeliverySucceeded(first.fileId)

        val event = receiver.handle(
            toEnvelope(
                SentTransferEnvelope(
                    TransferProtocol.TYPE_MANIFEST,
                    TransferProtocol.CAPABILITY,
                    true,
                    manifestPayload(
                        fileId = "f_terminal_quota_two",
                        sizeBytes = 1,
                        chunkSizeBytes = 1024,
                        chunkCount = 1,
                    ),
                ),
                index = outbox.envelopes.size,
            ),
        )

        assertEquals(TransferEvent.Ignored, event)
        assertEquals(null, receiver.checkpoint(first.fileId))
    }

    @Test
    fun `active receives reserve bounded pending delivery slots`() {
        val receiver = receiver(
            limits = TransferResourceLimits(
                maxConcurrentTransfers = 2,
                maxCompletedEntries = 1,
            ),
        )
        receiver.handle(
            toEnvelope(
                SentTransferEnvelope(
                    TransferProtocol.TYPE_MANIFEST,
                    TransferProtocol.CAPABILITY,
                    true,
                    manifestPayload("f_reserved_slot_one", 1, 1024, 1),
                ),
                index = 0,
            ),
        )

        val error = runCatching {
            receiver.handle(
                toEnvelope(
                    SentTransferEnvelope(
                        TransferProtocol.TYPE_MANIFEST,
                        TransferProtocol.CAPABILITY,
                        true,
                        manifestPayload("f_reserved_slot_two", 1, 1024, 1),
                    ),
                    index = 1,
                ),
            )
        }.exceptionOrNull()

        assertEquals("plugin/transfer-quota-exceeded", error?.message)
    }

    @Test
    fun `sender enforces registry and outstanding quotas`() {
        val limits = TransferResourceLimits(
            maxSenderRegistrySize = 1,
            maxOutstandingMessages = 1,
            maxOutstandingBytes = 2048,
        )
        val outbox = RecordingTransferOutbox()
        val sender = TransferSender(outbox, outstandingWindow = 4, limits = limits)
        sender.sendSource(
            filename = "one.bin",
            mimeType = "application/octet-stream",
            source = source(ByteArray(1500) { it.toByte() }),
            chunkSizeBytes = 1024,
            fileId = "f_registry_one",
        )
        assertRejected("registry quota") {
            sender.sendSource(
                filename = "two.bin",
                mimeType = "application/octet-stream",
                source = source(ByteArray(1500) { it.toByte() }),
                chunkSizeBytes = 1024,
                fileId = "f_registry_two",
            )
        }
        sender.handleAck("sent-0")
        assertEquals(1, outbox.envelopes.count { it.type == TransferProtocol.TYPE_CHUNK })
    }

    @Test
    fun `ack timeout aborts sender transfer`() {
        val outbox = RecordingTransferOutbox()
        val sender = TransferSender(outbox, outstandingWindow = 2)
        val manifest = sender.sendSource(
            filename = "sample.bin",
            mimeType = "application/octet-stream",
            source = source(ByteArray(5000) { (it % 251).toByte() }),
            chunkSizeBytes = 1024,
            fileId = "f_ack_timeout",
        )
        sender.handleAck("sent-0")
        val before = outbox.envelopes.size
        sender.handleAckTimeout("sent-1")
        sender.handleAck("sent-2")
        assertEquals(before, outbox.envelopes.size)
        assertRejected("terminal after ack timeout") {
            sender.sendRemaining(manifest.fileId, fromChunkIndex = 0)
        }
    }

    @Test
    fun `manifest ack timeout aborts its transfer`() {
        val outbox = RecordingTransferOutbox()
        val sender = TransferSender(outbox, outstandingWindow = 2)
        val manifest = sender.sendSource(
            filename = "sample.bin",
            mimeType = "application/octet-stream",
            source = source(ByteArray(5000) { (it % 251).toByte() }),
            chunkSizeBytes = 1024,
            fileId = "f_manifest_timeout",
        )
        val before = outbox.envelopes.size

        sender.handleAckTimeout("sent-0")
        sender.handleAck("sent-1")

        assertEquals(before, outbox.envelopes.size)
        assertRejected("terminal after manifest timeout") {
            sender.sendRemaining(manifest.fileId, fromChunkIndex = 0)
        }
    }

    @Test
    fun `cancel on same sender stops further chunks after ack`() {
        val bytes = ByteArray(10_000) { (it % 251).toByte() }
        val outbox = RecordingTransferOutbox()
        val sender = TransferSender(outbox, outstandingWindow = 2)
        val manifest = sender.sendSource(
            filename = "sample.bin",
            mimeType = "application/octet-stream",
            source = source(bytes),
            chunkSizeBytes = 1024,
            fileId = "f_cancel_same_sender",
        )

        sender.cancel(manifest.fileId, discard = true)
        assertEquals(TransferProtocol.TYPE_CANCEL, outbox.envelopes.last().type)
        val countAfterCancel = outbox.envelopes.size

        sender.handleAck("sent-1")
        sender.handleAck("sent-2")
        assertEquals(countAfterCancel, outbox.envelopes.size)
        assertRejected("terminal sendRemaining") {
            sender.sendRemaining(manifest.fileId, fromChunkIndex = 0)
        }
    }

    @Test
    fun `sender recycles registry after all chunks are acked`() {
        val bytes = ByteArray(5000) { (it % 251).toByte() }
        val outbox = RecordingTransferOutbox()
        val sender = TransferSender(outbox, outstandingWindow = 2)
        val manifest = sender.sendSource(
            filename = "sample.bin",
            mimeType = "application/octet-stream",
            source = source(bytes),
            chunkSizeBytes = 1024,
            fileId = "f_sender_recycle_complete",
        )
        sender.handleAck("sent-0")

        var processed = 0
        while (processed < outbox.envelopes.size) {
            val sent = outbox.envelopes[processed]
            if (sent.type == TransferProtocol.TYPE_CHUNK) {
                sender.handleAck("sent-$processed")
            }
            processed += 1
        }
        assertRejected("completed sendRemaining") {
            sender.sendRemaining(manifest.fileId, manifest.chunkCount)
        }
    }

    @Test
    fun `receiver recycleSession clears active partial state`() {
        val bytes = ByteArray(2500) { (it % 251).toByte() }
        val outbox = RecordingTransferOutbox()
        val sender = TransferSender(outbox)
        val manifest = sender.sendSource(
            filename = "sample.bin",
            mimeType = "application/octet-stream",
            source = source(bytes),
            chunkSizeBytes = 1024,
            fileId = "f_receiver_recycle",
        )
        sender.handleAck("sent-0")
        val receiver = receiver()
        receiver.handle(toEnvelope(outbox.envelopes[0], index = 0))
        receiver.handle(toEnvelope(outbox.envelopes[1], index = 1))
        assertEquals(1, receiver.checkpoint(manifest.fileId)?.nextChunkIndex)

        receiver.recycleSession()

        assertEquals(null, receiver.checkpoint(manifest.fileId))
        assertRejected("unknown fileId after recycle") {
            receiver.handle(toEnvelope(outbox.envelopes[1], index = 2))
        }
    }

    @Test
    fun `receiver resumes transfers after recycle and rebind`() {
        val bytes = ByteArray(2500) { (it % 251).toByte() }
        val outbox = RecordingTransferOutbox()
        val sender = TransferSender(outbox)
        sender.sendSource(
            filename = "sample.bin",
            mimeType = "application/octet-stream",
            source = source(bytes),
            chunkSizeBytes = 1024,
            fileId = "f_rebind_rearm",
        )
        sender.handleAck("sent-0")
        val receiver = receiver()

        // Freeze the long-lived receiver as a session teardown would, then re-arm
        // it on the next session: a fresh transfer must complete, not be rejected.
        receiver.recycleSession()
        receiver.bindSession(ByteArray(32) { 1 }, "s_rebind")

        var completed: TransferCompleted? = null
        outbox.envelopes.forEachIndexed { index, sent ->
            val event = receiver.handle(toEnvelope(sent, index))
            if (event is TransferEvent.Completed) completed = event.completed
        }
        assertEquals(bytes.toList(), completed?.file?.readBytes()?.toList())
    }

    @Test
    fun `completed receiver keeps bounded tombstone checkpoint after delivery succeeds`() {
        val bytes = ByteArray(2500) { (it % 251).toByte() }
        val outbox = RecordingTransferOutbox()
        val sender = TransferSender(outbox)
        val manifest = sender.sendSource(
            filename = "sample.bin",
            mimeType = "application/octet-stream",
            source = source(bytes),
            chunkSizeBytes = 1024,
            fileId = "f_completed_tombstone",
        )
        sender.handleAck("sent-0")
        val receiver = receiver()
        outbox.envelopes.forEachIndexed { index, sent ->
            receiver.handle(toEnvelope(sent, index))
        }

        val checkpoint = receiver.checkpoint(manifest.fileId)
        assertEquals(manifest.chunkCount, checkpoint?.nextChunkIndex)
        receiver.markDeliverySucceeded(manifest.fileId)
        receiver.handle(toEnvelope(outbox.envelopes[0], index = outbox.envelopes.size))
    }

    @Test
    fun `failed completed delivery retries in session and after process recreation`() {
        val bytes = ByteArray(2500) { (it % 251).toByte() }
        val outbox = RecordingTransferOutbox()
        val sender = TransferSender(outbox)
        val manifest = sender.sendSource(
            filename = "sample.bin",
            mimeType = "application/octet-stream",
            source = source(bytes),
            chunkSizeBytes = 1024,
            fileId = "f_delivery_retry012345",
        )
        sender.handleAck("sent-0")
        val storageRoot = temp.newFolder("delivery-retry-storage")
        val checkpointStore = TransferCheckpointStore(temp.newFolder("delivery-retry-checkpoints"))
        val peer = ByteArray(32) { 7 }
        val receiver = TransferReceiver(
            storage = FileTransferStorage(storageRoot),
            checkpointStore = checkpointStore,
        )
        receiver.bindSession(peer, "s_retry_1")
        var completed: TransferCompleted? = null
        outbox.envelopes.forEachIndexed { index, sent ->
            val event = receiver.handle(toEnvelope(sent, index))
            if (event is TransferEvent.Completed) completed = event.completed
        }
        assertEquals(bytes.toList(), completed?.file?.readBytes()?.toList())
        receiver.markDeliveryFailed(manifest.fileId)

        val duplicate = receiver.handle(
            toEnvelope(outbox.envelopes.first(), outbox.envelopes.size),
        )
        assertTrue(duplicate is TransferEvent.Completed)

        val recreated = TransferReceiver(
            storage = FileTransferStorage(
                storageRoot,
                isCheckpointActive = { checkpointStore.load(it) != null },
            ),
            checkpointStore = checkpointStore,
        )
        val restored = recreated.bindSession(peer, "s_retry_2")
        assertEquals(1, restored.size)
        assertEquals(bytes.toList(), restored.single().file.readBytes().toList())
        recreated.markDeliverySucceeded(manifest.fileId)
        assertEquals(null, checkpointStore.load(manifest.fileId))
    }

    @Test
    fun `expired checkpoint also expires in-memory pending delivery`() {
        val bytes = ByteArray(1500) { (it % 251).toByte() }
        val outbox = RecordingTransferOutbox()
        val sender = TransferSender(outbox)
        val manifest = sender.sendSource(
            filename = "expired.bin",
            mimeType = "application/octet-stream",
            source = source(bytes),
            chunkSizeBytes = 1024,
            fileId = "f_pending_ttl_expired",
        )
        sender.handleAck("sent-0")
        var now = System.currentTimeMillis()
        val storageRoot = temp.newFolder("pending-ttl-storage")
        val checkpointStore = TransferCheckpointStore(
            root = temp.newFolder("pending-ttl-checkpoints"),
            nowMs = { now },
        )
        val peer = ByteArray(32) { 9 }
        val receiver = TransferReceiver(
            storage = FileTransferStorage(storageRoot),
            checkpointStore = checkpointStore,
        )
        receiver.bindSession(peer, "s_ttl_1")
        outbox.envelopes.forEachIndexed { index, sent ->
            receiver.handle(toEnvelope(sent, index))
        }
        val source = storageRoot.resolve("${manifest.fileId}.part")
        assertTrue(source.exists())

        now = Long.MAX_VALUE
        val restored = receiver.bindSession(peer, "s_ttl_2")

        assertTrue(restored.isEmpty())
        assertFalse(source.exists())
        assertEquals(null, checkpointStore.load(manifest.fileId))
    }

    @Test
    fun `trust invalidation drops pending delivery before another peer binds`() {
        val bytes = ByteArray(1500) { (it % 251).toByte() }
        val outbox = RecordingTransferOutbox()
        val sender = TransferSender(outbox)
        val manifest = sender.sendSource(
            filename = "private.bin",
            mimeType = "application/octet-stream",
            source = source(bytes),
            chunkSizeBytes = 1024,
            fileId = "f_pending_peer_reset",
        )
        sender.handleAck("sent-0")
        val storageRoot = temp.newFolder("pending-peer-storage")
        val checkpointStore = TransferCheckpointStore(temp.newFolder("pending-peer-checkpoints"))
        val peerA = ByteArray(32) { 1 }
        val peerB = ByteArray(32) { 2 }
        val receiver = TransferReceiver(
            storage = FileTransferStorage(storageRoot),
            checkpointStore = checkpointStore,
        )
        receiver.bindSession(peerA, "s_peer_a")
        outbox.envelopes.forEachIndexed { index, sent ->
            receiver.handle(toEnvelope(sent, index))
        }
        assertTrue(storageRoot.resolve("${manifest.fileId}.part").exists())

        receiver.invalidatePeerCheckpoints(peerA)
        val forPeerB = receiver.bindSession(peerB, "s_peer_b")

        assertTrue(forPeerB.isEmpty())
        assertFalse(storageRoot.resolve("${manifest.fileId}.part").exists())
    }

    @Test
    fun `trust invalidation drops active partial source for bound peer`() {
        val bytes = ByteArray(2500) { (it % 251).toByte() }
        val outbox = RecordingTransferOutbox()
        val sender = TransferSender(outbox)
        val manifest = sender.sendSource(
            filename = "partial.bin",
            mimeType = "application/octet-stream",
            source = source(bytes),
            chunkSizeBytes = 1024,
            fileId = "f_active_peer_reset",
        )
        sender.handleAck("sent-0")
        val storageRoot = temp.newFolder("active-peer-storage")
        val checkpointStore = TransferCheckpointStore(temp.newFolder("active-peer-checkpoints"))
        val peer = ByteArray(32) { 4 }
        val receiver = TransferReceiver(
            storage = FileTransferStorage(storageRoot),
            checkpointStore = checkpointStore,
        )
        receiver.bindSession(peer, "s_active_peer")
        outbox.envelopes.take(2).forEachIndexed { index, sent ->
            receiver.handle(toEnvelope(sent, index))
        }
        val part = storageRoot.resolve("${manifest.fileId}.part")
        assertTrue(part.exists())

        receiver.invalidatePeerCheckpoints(peer)

        assertEquals(null, receiver.checkpoint(manifest.fileId))
        assertFalse(part.exists())
        assertEquals(null, checkpointStore.load(manifest.fileId))
    }

    @Test
    fun `all-checkpoint invalidation drops every pending delivery source`() {
        val bytes = ByteArray(1500) { (it % 251).toByte() }
        val outbox = RecordingTransferOutbox()
        val sender = TransferSender(outbox)
        val manifest = sender.sendSource(
            filename = "private.bin",
            mimeType = "application/octet-stream",
            source = source(bytes),
            chunkSizeBytes = 1024,
            fileId = "f_pending_reset_all",
        )
        sender.handleAck("sent-0")
        val storageRoot = temp.newFolder("pending-reset-all-storage")
        val checkpointStore = TransferCheckpointStore(temp.newFolder("pending-reset-all-checkpoints"))
        val receiver = TransferReceiver(
            storage = FileTransferStorage(storageRoot),
            checkpointStore = checkpointStore,
        )
        receiver.bindSession(ByteArray(32) { 3 }, "s_reset_all")
        outbox.envelopes.forEachIndexed { index, sent -> receiver.handle(toEnvelope(sent, index)) }
        val part = storageRoot.resolve("${manifest.fileId}.part")
        assertTrue(part.exists())

        receiver.invalidateAllCheckpoints()

        assertEquals(null, receiver.checkpoint(manifest.fileId))
        assertFalse(part.exists())
        assertEquals(null, checkpointStore.load(manifest.fileId))
    }

    @Test
    fun `cancel message uses protocol wire name and discard clears receiver checkpoint`() {
        val bytes = ByteArray(2500) { (it % 251).toByte() }
        val outbox = RecordingTransferOutbox()
        val sender = TransferSender(outbox)
        val manifest = sender.sendSource(
            filename = "sample.bin",
            mimeType = "application/octet-stream",
            source = source(bytes),
            chunkSizeBytes = 1024,
            fileId = "f_cancel_test",
        )
        sender.handleAck("sent-0")
        val receiver = receiver()
        outbox.envelopes.take(2).forEachIndexed { index, sent ->
            receiver.handle(toEnvelope(sent, index))
        }
        assertEquals(1, receiver.checkpoint(manifest.fileId)?.nextChunkIndex)

        sender.cancel(manifest.fileId, discard = true)
        val cancel = outbox.envelopes.last()
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

    @Test
    fun `receiver rejects oversized manifests before state allocation`() {
        val receiver = TransferReceiver(object : TransferStorage {
            override fun prepare(manifest: TransferManifest, reuseExisting: Boolean): java.io.File {
                fail("oversized manifest should reject before storage allocation")
                throw IllegalStateException("unexpected storage allocation")
            }

            override fun discard(fileId: String) = Unit
        })

        assertRejected("oversized manifest") {
            receiver.handle(
                toEnvelope(
                    SentTransferEnvelope(
                        type = TransferProtocol.TYPE_MANIFEST,
                        capability = TransferProtocol.CAPABILITY,
                        requiresAck = true,
                        payload = manifestPayload(
                            fileId = "f_oversized_manifest",
                            sizeBytes = TransferProtocol.MAX_V0_TRANSFER_SIZE_BYTES + 1,
                            chunkSizeBytes = 1024,
                            chunkCount = TransferProtocol.MAX_V0_TRANSFER_CHUNK_COUNT + 1,
                        ),
                    ),
                    index = 0,
                ),
            )
        }
    }

    @Test
    fun `manifest decoder rejects smuggled unknown fields`() {
        val smuggled = Json.Obj(
            manifestPayload(
                fileId = "f_smuggled_destination",
                sizeBytes = 1024,
                chunkSizeBytes = 1024,
                chunkCount = 1,
            ).entries + mapOf("destinationPath" to Json.Str("../../etc/passwd")),
        )
        assertRejected("smuggled manifest field") { TransferManifest.fromJson(smuggled) }
    }

    @Test
    fun `manifest accepts exactly one gibibyte when chunk math is valid`() {
        val manifest = TransferManifest.fromJson(
            manifestPayload(
                fileId = "f_exactly_one_gib",
                sizeBytes = TransferProtocol.MAX_V0_TRANSFER_SIZE_BYTES,
                chunkSizeBytes = TransferProtocol.MAX_CHUNK_SIZE_BYTES,
                chunkCount = 512,
            ),
        )

        assertEquals(TransferProtocol.MAX_V0_TRANSFER_SIZE_BYTES, manifest.sizeBytes)
        assertEquals(512, manifest.chunkCount)
    }

    @Test
    fun `sender rejects oversized sources before hashing`() {
        var opened = false
        val source = StreamTransferByteSource(TransferProtocol.MAX_V0_TRANSFER_SIZE_BYTES + 1) {
            opened = true
            java.io.ByteArrayInputStream(ByteArray(0))
        }

        assertRejected("oversized source") {
            TransferManifest.fromSource(
                filename = "large.bin",
                mimeType = "application/octet-stream",
                source = source,
                chunkSizeBytes = 1024,
                fileId = "f_oversized_source",
            )
        }
        assertFalse(opened)
    }

    private fun sendThroughReceiver(bytes: ByteArray, fileId: String): TransferCompleted {
        val outbox = RecordingTransferOutbox()
        val sender = TransferSender(outbox)
        sender.sendSource(
            filename = "sample.bin",
            mimeType = "application/octet-stream",
            source = source(bytes),
            chunkSizeBytes = 1024,
            fileId = fileId,
        )
        sender.handleAck("sent-0")
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

        assertEquals(listOf(TransferProtocol.TYPE_MANIFEST), outbox.envelopes.map { it.type })
        sender.handleAck("sent-0")

        // Only a bounded chunk window goes out after the manifest is acknowledged.
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
        assertEquals(1, outbox.envelopes.size)
        sender.handleAck("sent-0")
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
        limits: TransferResourceLimits = TransferResourceLimits(),
        onProgress: (TransferProgress) -> Unit = {},
    ): TransferReceiver =
        TransferReceiver(
            storage = FileTransferStorage(temp.newFolder("receive-${System.nanoTime()}")),
            limits = limits,
            onProgress = onProgress,
        )

    private fun chunk(fileId: String, chunkIndex: Int, bytes: ByteArray): SentTransferEnvelope =
        SentTransferEnvelope(
            type = TransferProtocol.TYPE_CHUNK,
            capability = TransferProtocol.CAPABILITY,
            requiresAck = true,
            payload = TransferChunk(fileId, chunkIndex, bytes).toJson(),
        )

    private fun manifestPayload(
        fileId: String,
        sizeBytes: Long,
        chunkSizeBytes: Int,
        chunkCount: Int,
    ): Json.Obj =
        Json.obj(
            "fileId" to Json.Str(fileId),
            "filename" to Json.Str("sample.bin"),
            "sizeBytes" to Json.Num(sizeBytes.toString()),
            "mimeType" to Json.Str("application/octet-stream"),
            "sha256" to Json.Str("0".repeat(64)),
            "chunkSizeBytes" to Json.Num(chunkSizeBytes.toString()),
            "chunkCount" to Json.Num(chunkCount.toString()),
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
