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
            completed = receiver.handle(
                Envelope(
                    messageId = "01JZ000000000000000000000$index",
                    sessionId = "s_test1",
                    type = sent.type,
                    capability = sent.capability,
                    seq = (index + 1).toLong(),
                    sentAtUnixMs = 1_781_020_800_000,
                    requiresAck = sent.requiresAck,
                    payload = sent.payload,
                ),
            ) ?: completed
        }
        return completed ?: error("transfer did not complete")
    }
}
