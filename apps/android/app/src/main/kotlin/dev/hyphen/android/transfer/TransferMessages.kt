package dev.hyphen.android.transfer

import dev.hyphen.android.transport.Envelope
import dev.hyphen.android.transport.Json
import dev.hyphen.android.transport.ProtocolSession
import dev.hyphen.android.transport.SessionHandshake
import dev.hyphen.android.transport.Ulid
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Base64

object TransferProtocol {
    const val CAPABILITY = "transfer.v1"
    const val TYPE_MANIFEST = "transfer.manifest"
    const val TYPE_CHUNK = "transfer.chunk"
    const val TYPE_RESUME_REQUEST = "transfer.resume.request"
    const val TYPE_RESUME_INFO = "transfer.resume.info"
    const val TYPE_CANCEL = "transfer.cancel"
    const val MIN_CHUNK_SIZE_BYTES = 1024
    const val MAX_CHUNK_SIZE_BYTES = 2 * 1024 * 1024
}

private val FILE_ID = Regex("^f_[A-Za-z0-9_-]{8,128}$")

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
        val expectedChunks = if (sizeBytes == 0L) {
            0L
        } else {
            (sizeBytes + chunkSizeBytes - 1L) / chunkSizeBytes
        }
        require(expectedChunks <= Int.MAX_VALUE && chunkCount.toLong() == expectedChunks) {
            "chunkCount does not match sizeBytes/chunkSizeBytes"
        }
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
        private val MIME_TYPE = Regex("^[a-z0-9][a-z0-9!#\$&^_.+-]*/[a-z0-9][a-z0-9!#\$&^_.+-]*$")
        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

        fun fromSource(
            filename: String,
            mimeType: String,
            source: TransferByteSource,
            chunkSizeBytes: Int,
            fileId: String = "f_${Ulid.generate()}",
        ): TransferManifest =
            TransferManifest(
                fileId = fileId,
                filename = filename,
                sizeBytes = source.sizeBytes,
                mimeType = mimeType,
                sha256 = source.sha256Hex(),
                chunkSizeBytes = chunkSizeBytes,
                chunkCount = if (source.sizeBytes == 0L) {
                    0
                } else {
                    ((source.sizeBytes + chunkSizeBytes - 1L) / chunkSizeBytes).toInt()
                },
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

interface TransferByteSource {
    val sizeBytes: Long
    fun openStream(): InputStream

    fun sha256Hex(): String =
        openStream().use { sha256Hex(it) }

    fun readChunk(offset: Long, maxBytes: Int): ByteArray =
        openStream().use { input ->
            skipFully(input, offset)
            val buffer = ByteArray(maxBytes)
            var total = 0
            while (total < maxBytes) {
                val read = input.read(buffer, total, maxBytes - total)
                if (read == -1) break
                total += read
            }
            buffer.copyOf(total)
        }
}

class FileTransferByteSource(private val file: File) : TransferByteSource {
    override val sizeBytes: Long
        get() = file.length()

    override fun openStream(): InputStream = FileInputStream(file)
}

interface TransferStorage {
    fun prepare(manifest: TransferManifest): File
    fun discard(fileId: String)
}

class FileTransferStorage(private val root: File) : TransferStorage {
    override fun prepare(manifest: TransferManifest): File {
        require(root.exists() || root.mkdirs()) { "transfer storage unavailable" }
        val file = File(root, "${manifest.fileId}.part")
        if (file.exists()) require(file.delete()) { "could not reset transfer storage" }
        require(file.createNewFile()) { "could not create transfer storage" }
        return file
    }

    override fun discard(fileId: String) {
        File(root, "$fileId.part").delete()
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

data class TransferResumeRequest(val fileId: String) {
    init {
        require(FILE_ID.matches(fileId)) { "invalid fileId" }
    }

    fun toJson(): Json.Obj = Json.obj("fileId" to Json.Str(fileId))

    companion object {
        fun fromJson(payload: Json.Obj): TransferResumeRequest =
            TransferResumeRequest(string(payload, "fileId"))
    }
}

data class TransferResumeInfo(
    val fileId: String,
    val nextChunkIndex: Int,
) {
    init {
        require(FILE_ID.matches(fileId)) { "invalid fileId" }
        require(nextChunkIndex >= 0) { "nextChunkIndex must be >= 0" }
    }

    fun toJson(): Json.Obj =
        Json.obj(
            "fileId" to Json.Str(fileId),
            "nextChunkIndex" to Json.Num(nextChunkIndex.toString()),
        )

    companion object {
        fun fromJson(payload: Json.Obj): TransferResumeInfo =
            TransferResumeInfo(
                fileId = string(payload, "fileId"),
                nextChunkIndex = int(payload, "nextChunkIndex"),
            )
    }
}

data class TransferCancel(
    val fileId: String,
    val discard: Boolean = false,
) {
    init {
        require(FILE_ID.matches(fileId)) { "invalid fileId" }
    }

    fun toJson(): Json.Obj =
        Json.obj(
            "fileId" to Json.Str(fileId),
            "discard" to Json.Bool(discard),
        )

    companion object {
        fun fromJson(payload: Json.Obj): TransferCancel =
            TransferCancel(
                fileId = string(payload, "fileId"),
                discard = bool(payload, "discard"),
            )
    }
}

data class TransferProgress(
    val fileId: String,
    val filename: String,
    val completedChunks: Int,
    val totalChunks: Int,
    val completedBytes: Long,
    val totalBytes: Long,
) {
    val isComplete: Boolean
        get() = completedChunks == totalChunks

    companion object {
        fun from(manifest: TransferManifest, completedChunks: Int): TransferProgress {
            require(completedChunks in 0..manifest.chunkCount) { "completedChunks out of range" }
            val completedBytes = minOf(
                manifest.sizeBytes,
                completedChunks.toLong() * manifest.chunkSizeBytes.toLong(),
            )
            return TransferProgress(
                fileId = manifest.fileId,
                filename = manifest.filename,
                completedChunks = completedChunks,
                totalChunks = manifest.chunkCount,
                completedBytes = completedBytes,
                totalBytes = manifest.sizeBytes,
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

class TransferSender(
    private val outbox: TransferOutbox,
    private val negotiatedCapabilities: SessionHandshake.NegotiatedCapabilities? = null,
    private val onProgress: (TransferProgress) -> Unit = {},
) {
    private data class RegisteredTransfer(
        val manifest: TransferManifest,
        val source: TransferByteSource,
    )

    private val registered = mutableMapOf<String, RegisteredTransfer>()

    fun sendSource(
        filename: String,
        mimeType: String,
        source: TransferByteSource,
        chunkSizeBytes: Int,
        fileId: String = "f_${Ulid.generate()}",
    ): TransferManifest {
        require(negotiatedCapabilities?.contains(TransferProtocol.CAPABILITY) != false) {
            "plugin/unsupported-capability"
        }
        val effectiveChunkSize = negotiatedCapabilities
            ?.transferMaxChunkBytes()
            ?.let { minOf(chunkSizeBytes, it) }
            ?: chunkSizeBytes
        val manifest = TransferManifest.fromSource(filename, mimeType, source, effectiveChunkSize, fileId)
        registered[manifest.fileId] = RegisteredTransfer(manifest, source)
        outbox.send(TransferProtocol.TYPE_MANIFEST, TransferProtocol.CAPABILITY, true, manifest.toJson())
        onProgress(TransferProgress.from(manifest, completedChunks = 0))
        sendRemaining(manifest.fileId, fromChunkIndex = 0)
        return manifest
    }

    fun sendRemaining(fileId: String, fromChunkIndex: Int) {
        val transfer = registered[fileId] ?: throw IllegalArgumentException("unknown outbound fileId")
        val manifest = transfer.manifest
        val source = transfer.source
        require(fromChunkIndex in 0..manifest.chunkCount) { "fromChunkIndex out of range" }
        require(source.sizeBytes == manifest.sizeBytes) { "size mismatch" }
        require(source.sha256Hex() == manifest.sha256) { "file sha256 mismatch" }
        for (index in fromChunkIndex until manifest.chunkCount) {
            val start = index.toLong() * manifest.chunkSizeBytes.toLong()
            val expected = manifest.expectedChunkBytes(index)
            val chunk = TransferChunk(manifest.fileId, index, source.readChunk(start, expected))
            outbox.send(TransferProtocol.TYPE_CHUNK, TransferProtocol.CAPABILITY, true, chunk.toJson())
            onProgress(TransferProgress.from(manifest, completedChunks = index + 1))
        }
    }

    fun handleResumeInfo(info: TransferResumeInfo) {
        sendRemaining(info.fileId, info.nextChunkIndex)
    }

    fun requestResume(fileId: String): String =
        outbox.send(
            TransferProtocol.TYPE_RESUME_REQUEST,
            TransferProtocol.CAPABILITY,
            true,
            TransferResumeRequest(fileId).toJson(),
        )

    fun sendResumeInfo(info: TransferResumeInfo): String =
        outbox.send(
            TransferProtocol.TYPE_RESUME_INFO,
            TransferProtocol.CAPABILITY,
            true,
            info.toJson(),
        )

    fun sendCancel(cancel: TransferCancel): String =
        outbox.send(
            TransferProtocol.TYPE_CANCEL,
            TransferProtocol.CAPABILITY,
            true,
            cancel.toJson(),
        )
}

data class TransferCompleted(
    val manifest: TransferManifest,
    val file: File,
    val sha256: String,
)

sealed class TransferEvent {
    data class Completed(val completed: TransferCompleted) : TransferEvent()
    data class ResumeRequested(val info: TransferResumeInfo) : TransferEvent()
    data class Cancelled(val cancel: TransferCancel) : TransferEvent()
    object Ignored : TransferEvent()
}

class TransferReceiver(
    private val storage: TransferStorage = FileTransferStorage(
        File(System.getProperty("java.io.tmpdir") ?: ".", "hyphen-transfer"),
    ),
    private val onProgress: (TransferProgress) -> Unit = {},
) {
    private val states = mutableMapOf<String, TransferState>()

    fun checkpoint(fileId: String): TransferResumeInfo? =
        states[fileId]?.checkpoint()

    fun handle(envelope: Envelope): TransferEvent {
        if (envelope.capability != TransferProtocol.CAPABILITY) return TransferEvent.Ignored
        return when (envelope.type) {
            TransferProtocol.TYPE_MANIFEST -> {
                val manifest = TransferManifest.fromJson(envelope.payload)
                val state = TransferState(manifest, storage.prepare(manifest))
                states[manifest.fileId] = state
                onProgress(TransferProgress.from(manifest, completedChunks = 0))
                completeIfReady(state)?.let(TransferEvent::Completed) ?: TransferEvent.Ignored
            }
            TransferProtocol.TYPE_CHUNK -> {
                val chunk = TransferChunk.fromJson(envelope.payload)
                val state = states[chunk.fileId] ?: throw IllegalArgumentException("unknown fileId")
                state.accept(chunk)
                onProgress(state.progress())
                completeIfReady(state)?.let(TransferEvent::Completed) ?: TransferEvent.Ignored
            }
            TransferProtocol.TYPE_RESUME_REQUEST -> {
                val request = TransferResumeRequest.fromJson(envelope.payload)
                TransferEvent.ResumeRequested(
                    states[request.fileId]?.checkpoint() ?: TransferResumeInfo(request.fileId, nextChunkIndex = 0),
                )
            }
            TransferProtocol.TYPE_CANCEL -> {
                val cancel = TransferCancel.fromJson(envelope.payload)
                if (cancel.discard) {
                    states.remove(cancel.fileId)
                    storage.discard(cancel.fileId)
                }
                TransferEvent.Cancelled(cancel)
            }
            else -> TransferEvent.Ignored
        }
    }

    private fun completeIfReady(state: TransferState): TransferCompleted? {
        val file = state.fileIfComplete() ?: return null
        require(file.length() == state.manifest.sizeBytes) { "size mismatch" }
        val digest = file.inputStream().use { sha256Hex(it) }
        require(digest == state.manifest.sha256) { "file sha256 mismatch" }
        states.remove(state.manifest.fileId)
        return TransferCompleted(state.manifest, file, digest)
    }
}

private class TransferState(
    val manifest: TransferManifest,
    private val file: File,
) {
    private val received = BooleanArray(manifest.chunkCount)

    fun accept(chunk: TransferChunk) {
        require(chunk.chunkIndex < received.size) { "chunkIndex out of range" }
        require(chunk.data.size == manifest.expectedChunkBytes(chunk.chunkIndex)) { "chunk size mismatch" }
        RandomAccessFile(file, "rw").use {
            it.seek(chunk.chunkIndex.toLong() * manifest.chunkSizeBytes.toLong())
            it.write(chunk.data)
        }
        received[chunk.chunkIndex] = true
    }

    fun fileIfComplete(): File? {
        for (chunk in received) {
            if (!chunk) return null
        }
        return file
    }

    fun checkpoint(): TransferResumeInfo {
        var next = 0
        while (next < received.size && received[next]) next += 1
        return TransferResumeInfo(manifest.fileId, next)
    }

    fun progress(): TransferProgress {
        var completed = 0
        while (completed < received.size && received[completed]) completed += 1
        return TransferProgress.from(manifest, completed)
    }
}

private fun TransferManifest.expectedChunkBytes(chunkIndex: Int): Int {
    require(chunkIndex in 0 until chunkCount) { "chunkIndex out of range" }
    val offset = chunkIndex.toLong() * chunkSizeBytes.toLong()
    return minOf(chunkSizeBytes.toLong(), sizeBytes - offset).toInt()
}

private fun string(payload: Json.Obj, field: String): String =
    (payload[field] as? Json.Str)?.value ?: throw IllegalArgumentException("$field must be string")

private fun long(payload: Json.Obj, field: String): Long =
    (payload[field] as? Json.Num)?.asLong() ?: throw IllegalArgumentException("$field must be integer")

private fun int(payload: Json.Obj, field: String): Int =
    long(payload, field).also {
        require(it in Int.MIN_VALUE..Int.MAX_VALUE) { "$field out of int range" }
    }.toInt()

private fun bool(payload: Json.Obj, field: String): Boolean =
    (payload[field] as? Json.Bool)?.value ?: throw IllegalArgumentException("$field must be boolean")

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

private fun sha256Hex(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = input.read(buffer)
        if (read == -1) break
        digest.update(buffer, 0, read)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun skipFully(input: InputStream, bytes: Long) {
    var remaining = bytes
    while (remaining > 0) {
        val skipped = input.skip(remaining)
        if (skipped > 0) {
            remaining -= skipped
        } else if (input.read() == -1) {
            throw IllegalArgumentException("source ended before requested chunk")
        } else {
            remaining--
        }
    }
}
