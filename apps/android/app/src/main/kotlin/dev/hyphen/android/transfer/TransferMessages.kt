package dev.hyphen.android.transfer

import dev.hyphen.android.transport.Envelope
import dev.hyphen.android.transport.Json
import dev.hyphen.android.transport.ProtocolSession
import dev.hyphen.android.transport.Ulid
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64

object TransferProtocol {
    const val CAPABILITY = "transfer.v1"
    const val TYPE_MANIFEST = "transfer.manifest"
    const val TYPE_CHUNK = "transfer.chunk"
    const val MIN_CHUNK_SIZE_BYTES = 1024
    const val MAX_CHUNK_SIZE_BYTES = 2 * 1024 * 1024
}

data class TransferManifest(
    val fileId: String,
    val filename: String,
    val sizeBytes: Long,
    val mimeType: String,
    val sha256: String,
    val chunkSizeBytes: Int,
    val chunkCount: Int,
) {
    init {
        require(FILE_ID.matches(fileId)) { "invalid fileId" }
        require(filename.isNotBlank() && filename.length <= 255) { "invalid filename" }
        require(!filename.contains('/') && !filename.contains('\\')) { "filename must not contain a path" }
        require(sizeBytes >= 0) { "sizeBytes must be >= 0" }
        require(MIME_TYPE.matches(mimeType)) { "invalid mimeType" }
        require(SHA256_HEX.matches(sha256)) { "invalid sha256" }
        require(chunkSizeBytes in TransferProtocol.MIN_CHUNK_SIZE_BYTES..TransferProtocol.MAX_CHUNK_SIZE_BYTES) {
            "invalid chunkSizeBytes"
        }
        require(chunkCount >= 0) { "chunkCount must be >= 0" }
    }

    fun toJson(): Json.Obj =
        Json.obj(
            "fileId" to Json.Str(fileId),
            "filename" to Json.Str(filename),
            "sizeBytes" to Json.Num(sizeBytes.toString()),
            "mimeType" to Json.Str(mimeType),
            "sha256" to Json.Str(sha256),
            "chunkSizeBytes" to Json.Num(chunkSizeBytes.toString()),
            "chunkCount" to Json.Num(chunkCount.toString()),
        )

    companion object {
        private val FILE_ID = Regex("^f_[A-Za-z0-9_-]{8,128}$")
        private val MIME_TYPE = Regex("^[a-z0-9][a-z0-9!#\$&^_.+-]*/[a-z0-9][a-z0-9!#\$&^_.+-]*$")
        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

        fun fromBytes(
            filename: String,
            mimeType: String,
            bytes: ByteArray,
            chunkSizeBytes: Int,
            fileId: String = "f_${Ulid.generate()}",
        ): TransferManifest =
            TransferManifest(
                fileId = fileId,
                filename = filename,
                sizeBytes = bytes.size.toLong(),
                mimeType = mimeType,
                sha256 = sha256Hex(bytes),
                chunkSizeBytes = chunkSizeBytes,
                chunkCount = if (bytes.isEmpty()) 0 else (bytes.size + chunkSizeBytes - 1) / chunkSizeBytes,
            )

        fun fromJson(payload: Json.Obj): TransferManifest =
            TransferManifest(
                fileId = string(payload, "fileId"),
                filename = string(payload, "filename"),
                sizeBytes = long(payload, "sizeBytes"),
                mimeType = string(payload, "mimeType"),
                sha256 = string(payload, "sha256"),
                chunkSizeBytes = int(payload, "chunkSizeBytes"),
                chunkCount = int(payload, "chunkCount"),
            )
    }
}

data class TransferChunk(
    val fileId: String,
    val chunkIndex: Int,
    val data: ByteArray,
    val chunkSha256: String = sha256Hex(data),
) {
    init {
        require(chunkIndex >= 0) { "chunkIndex must be >= 0" }
        require(chunkSha256 == sha256Hex(data)) { "chunkSha256 mismatch" }
    }

    fun toJson(): Json.Obj =
        Json.obj(
            "fileId" to Json.Str(fileId),
            "chunkIndex" to Json.Num(chunkIndex.toString()),
            "dataBase64" to Json.Str(Base64.getEncoder().encodeToString(data)),
            "chunkSha256" to Json.Str(chunkSha256),
        )

    companion object {
        fun fromJson(payload: Json.Obj): TransferChunk {
            val data = try {
                Base64.getDecoder().decode(string(payload, "dataBase64"))
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("dataBase64 must be valid base64", e)
            }
            return TransferChunk(
                fileId = string(payload, "fileId"),
                chunkIndex = int(payload, "chunkIndex"),
                data = data,
                chunkSha256 = string(payload, "chunkSha256"),
            )
        }
    }
}

interface TransferOutbox {
    fun send(
        type: String,
        capability: String,
        requiresAck: Boolean,
        payload: Json.Obj,
    ): String
}

class ProtocolSessionTransferOutbox(private val session: ProtocolSession) : TransferOutbox {
    override fun send(
        type: String,
        capability: String,
        requiresAck: Boolean,
        payload: Json.Obj,
    ): String =
        session.send(type = type, capability = capability, requiresAck = requiresAck, payload = payload)
}

class TransferSender(private val outbox: TransferOutbox) {
    fun sendBytes(
        filename: String,
        mimeType: String,
        bytes: ByteArray,
        chunkSizeBytes: Int,
        fileId: String = "f_${Ulid.generate()}",
    ): TransferManifest {
        val manifest = TransferManifest.fromBytes(filename, mimeType, bytes, chunkSizeBytes, fileId)
        outbox.send(TransferProtocol.TYPE_MANIFEST, TransferProtocol.CAPABILITY, true, manifest.toJson())
        for (index in 0 until manifest.chunkCount) {
            val start = index * manifest.chunkSizeBytes
            val end = minOf(start + manifest.chunkSizeBytes, bytes.size)
            val chunk = TransferChunk(manifest.fileId, index, bytes.copyOfRange(start, end))
            outbox.send(TransferProtocol.TYPE_CHUNK, TransferProtocol.CAPABILITY, true, chunk.toJson())
        }
        return manifest
    }
}

data class TransferCompleted(
    val manifest: TransferManifest,
    val bytes: ByteArray,
)

class TransferReceiver {
    private val states = mutableMapOf<String, TransferState>()

    fun handle(envelope: Envelope): TransferCompleted? {
        if (envelope.capability != TransferProtocol.CAPABILITY) return null
        return when (envelope.type) {
            TransferProtocol.TYPE_MANIFEST -> {
                val manifest = TransferManifest.fromJson(envelope.payload)
                val state = TransferState(manifest)
                states[manifest.fileId] = state
                completeIfReady(state)
            }
            TransferProtocol.TYPE_CHUNK -> {
                val chunk = TransferChunk.fromJson(envelope.payload)
                val state = states[chunk.fileId] ?: throw IllegalArgumentException("unknown fileId")
                state.accept(chunk)
                completeIfReady(state)
            }
            else -> null
        }
    }

    private fun completeIfReady(state: TransferState): TransferCompleted? {
        val bytes = state.bytesIfComplete() ?: return null
        require(bytes.size.toLong() == state.manifest.sizeBytes) { "size mismatch" }
        require(sha256Hex(bytes) == state.manifest.sha256) { "file sha256 mismatch" }
        states.remove(state.manifest.fileId)
        return TransferCompleted(state.manifest, bytes)
    }
}

private class TransferState(val manifest: TransferManifest) {
    private val chunks = arrayOfNulls<ByteArray>(manifest.chunkCount)

    fun accept(chunk: TransferChunk) {
        require(chunk.chunkIndex < chunks.size) { "chunkIndex out of range" }
        chunks[chunk.chunkIndex] = chunk.data
    }

    fun bytesIfComplete(): ByteArray? {
        val out = ByteArrayOutputStream()
        for (chunk in chunks) {
            if (chunk == null) return null
            out.write(chunk)
        }
        return out.toByteArray()
    }
}

private fun string(payload: Json.Obj, field: String): String =
    (payload[field] as? Json.Str)?.value ?: throw IllegalArgumentException("$field must be string")

private fun long(payload: Json.Obj, field: String): Long =
    (payload[field] as? Json.Num)?.asLong() ?: throw IllegalArgumentException("$field must be integer")

private fun int(payload: Json.Obj, field: String): Int =
    long(payload, field).also {
        require(it in Int.MIN_VALUE..Int.MAX_VALUE) { "$field out of int range" }
    }.toInt()

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
