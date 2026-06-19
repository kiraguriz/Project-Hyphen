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
    const val MAX_V0_TRANSFER_SIZE_BYTES = 1_073_741_824L
    const val MAX_V0_TRANSFER_CHUNK_COUNT = 1_048_576
}

/** Per-session transfer resource ceilings; fail closed when exceeded. */
data class TransferResourceLimits(
    val maxConcurrentTransfers: Int = 4,
    val maxStagingBytes: Long = 256L * 1024 * 1024,
    val maxCompletedEntries: Int = 64,
    val maxSenderRegistrySize: Int = 16,
    val maxOutstandingMessages: Int = 64,
    val maxOutstandingBytes: Long = 32L * 1024 * 1024,
) {
    init {
        require(maxConcurrentTransfers > 0) { "maxConcurrentTransfers must be positive" }
        require(maxStagingBytes >= 0) { "maxStagingBytes must be >= 0" }
        require(maxCompletedEntries > 0) { "maxCompletedEntries must be positive" }
        require(maxSenderRegistrySize > 0) { "maxSenderRegistrySize must be positive" }
        require(maxOutstandingMessages > 0) { "maxOutstandingMessages must be positive" }
        require(maxOutstandingBytes >= 0) { "maxOutstandingBytes must be >= 0" }
    }
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
        require(sizeBytes <= TransferProtocol.MAX_V0_TRANSFER_SIZE_BYTES) { "sizeBytes exceeds v0 transfer limit" }
        require(MIME_TYPE.matches(mimeType)) { "invalid mimeType" }
        require(SHA256_HEX.matches(sha256)) { "invalid sha256" }
        require(chunkSizeBytes in TransferProtocol.MIN_CHUNK_SIZE_BYTES..TransferProtocol.MAX_CHUNK_SIZE_BYTES) {
            "invalid chunkSizeBytes"
        }
        require(chunkCount >= 0) { "chunkCount must be >= 0" }
        require(chunkCount <= TransferProtocol.MAX_V0_TRANSFER_CHUNK_COUNT) {
            "chunkCount exceeds v0 transfer limit"
        }
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
        ): TransferManifest {
            require(source.sizeBytes <= TransferProtocol.MAX_V0_TRANSFER_SIZE_BYTES) {
                "sizeBytes exceeds v0 transfer limit"
            }
            return TransferManifest(
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
        }

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
}

class FileTransferByteSource(val file: File) : TransferByteSource {
    override val sizeBytes: Long
        get() = file.length()

    override fun openStream(): InputStream = FileInputStream(file)
}

/**
 * Stream-backed source for inputs that are not plain files (e.g. content://
 * URIs resolved through a ContentResolver). [open] must return a fresh stream
 * on every call, since the sender re-opens for hashing, sending, and resume.
 */
class StreamTransferByteSource(
    override val sizeBytes: Long,
    private val open: () -> InputStream,
) : TransferByteSource {
    override fun openStream(): InputStream = open()
}

interface TransferStorage {
    fun prepare(manifest: TransferManifest, reuseExisting: Boolean = false): File
    fun discard(fileId: String)
    fun partFile(fileId: String): File? = null
}

class FileTransferStorage(private val root: File) : TransferStorage {
    override fun partFile(fileId: String): File = File(root, "$fileId.part")

    override fun prepare(manifest: TransferManifest, reuseExisting: Boolean): File {
        require(root.exists() || root.mkdirs()) { "transfer storage unavailable" }
        val file = partFile(manifest.fileId)!!
        if (reuseExisting && file.exists()) return file
        if (file.exists()) require(file.delete()) { "could not reset transfer storage" }
        require(file.createNewFile()) { "could not create transfer storage" }
        return file
    }

    override fun discard(fileId: String) {
        partFile(fileId)?.delete()
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
    val needsManifest: Boolean = false,
) {
    init {
        require(FILE_ID.matches(fileId)) { "invalid fileId" }
        require(nextChunkIndex >= 0) { "nextChunkIndex must be >= 0" }
    }

    fun toJson(): Json.Obj =
        Json.obj(
            "fileId" to Json.Str(fileId),
            "nextChunkIndex" to Json.Num(nextChunkIndex.toString()),
        ).let { obj ->
            if (needsManifest) Json.Obj(obj.entries + ("needsManifest" to Json.Bool(true))) else obj
        }

    companion object {
        fun fromJson(payload: Json.Obj): TransferResumeInfo =
            TransferResumeInfo(
                fileId = string(payload, "fileId"),
                nextChunkIndex = int(payload, "nextChunkIndex"),
                needsManifest = optionalBool(payload, "needsManifest") ?: false,
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
    /** Primary UI progress: contiguous acked chunks (sender) or verified chunks (receiver). */
    val completedChunks: Int,
    val totalChunks: Int,
    val completedBytes: Long,
    val totalBytes: Long,
    /** Sender debug: chunks queued/sent but not necessarily acked. */
    val sentChunks: Int? = null,
    /** Receiver debug: total unique chunks received, including out-of-order. */
    val receivedChunks: Int? = null,
) {
    val isComplete: Boolean
        get() = completedChunks == totalChunks

    companion object {
        fun from(
            manifest: TransferManifest,
            completedChunks: Int,
            sentChunks: Int? = null,
            receivedChunks: Int? = null,
        ): TransferProgress {
            require(completedChunks in 0..manifest.chunkCount) { "completedChunks out of range" }
            val completedBytes = contiguousCompletedBytes(manifest, completedChunks)
            return TransferProgress(
                fileId = manifest.fileId,
                filename = manifest.filename,
                completedChunks = completedChunks,
                totalChunks = manifest.chunkCount,
                completedBytes = completedBytes,
                totalBytes = manifest.sizeBytes,
                sentChunks = sentChunks,
                receivedChunks = receivedChunks,
            )
        }

        private fun contiguousCompletedBytes(manifest: TransferManifest, contiguousChunks: Int): Long =
            when {
                contiguousChunks <= 0 -> 0L
                contiguousChunks >= manifest.chunkCount -> manifest.sizeBytes
                else -> minOf(
                    manifest.sizeBytes,
                    contiguousChunks.toLong() * manifest.chunkSizeBytes.toLong(),
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

/**
 * Streams an outbound file under a bounded in-flight window: at most
 * [outstandingWindow] chunks may be unacked at once; [handleAck] releases a slot
 * and pumps the next. Mirrors the macOS `TransferSender`. macOS serializes all
 * calls onto its main queue; on Android `handleAck`/`handleResumeInfo` arrive on
 * the session read thread while `sendSource` runs on a sender thread, so the
 * mutating entry points are `@Synchronized` on the instance monitor.
 */
class TransferSender(
    private val outbox: TransferOutbox,
    private val negotiatedCapabilities: SessionHandshake.NegotiatedCapabilities? = null,
    private val outstandingWindow: Int = 8,
    private val limits: TransferResourceLimits = TransferResourceLimits(),
    private val checkpointStore: TransferCheckpointStore? = null,
    private val onProgress: (TransferProgress) -> Unit = {},
) {
    init {
        require(outstandingWindow > 0) { "outstandingWindow must be positive" }
    }

    private data class RegisteredTransfer(
        val manifest: TransferManifest,
        val source: TransferByteSource,
    )

    private class ActiveSend(
        val manifest: TransferManifest,
        var stream: InputStream?,
        var nextIndex: Int,
    ) {
        val outstanding = mutableMapOf<String, Int>()
        val acked = BooleanArray(manifest.chunkCount)
        var highestContiguousAcked = -1
        var outstandingBytes = 0L

        fun closeStream() {
            stream?.close()
            stream = null
        }

        fun markAcked(chunkIndex: Int): Int {
            if (chunkIndex !in acked.indices || acked[chunkIndex]) return highestContiguousAcked + 1
            acked[chunkIndex] = true
            if (chunkIndex == highestContiguousAcked + 1) {
                highestContiguousAcked = chunkIndex
                while (highestContiguousAcked + 1 < acked.size && acked[highestContiguousAcked + 1]) {
                    highestContiguousAcked += 1
                }
            }
            return highestContiguousAcked + 1
        }
    }

    private val registered = mutableMapOf<String, RegisteredTransfer>()
    private val activeSends = mutableMapOf<String, ActiveSend>()
    private val tombstones = linkedMapOf<String, TerminalOutcome>()
    private var frozen = false
    private var sessionBinding: TransferSessionBinding? = null

    data class TransferSessionBinding(
        val peerFingerprint: ByteArray,
        val sessionId: String,
    )

    @Synchronized
    fun bindSession(peerFingerprint: ByteArray, sessionId: String) {
        sessionBinding = TransferSessionBinding(peerFingerprint.copyOf(), sessionId)
        checkpointStore?.purgeExpired()
        restoreOutboundCheckpoints(peerFingerprint)
    }

    @Synchronized
    fun invalidatePeerCheckpoints(peerFingerprint: ByteArray) {
        checkpointStore?.invalidatePeer(peerFingerprint)
    }

    @Synchronized
    fun sendSource(
        filename: String,
        mimeType: String,
        source: TransferByteSource,
        chunkSizeBytes: Int,
        fileId: String = "f_${Ulid.generate()}",
    ): TransferManifest {
        require(!frozen) { "plugin/transfer-cancelled" }
        require(negotiatedCapabilities?.contains(TransferProtocol.CAPABILITY) != false) {
            "plugin/unsupported-capability"
        }
        if (registered.size >= limits.maxSenderRegistrySize && fileId !in registered) {
            throw IllegalArgumentException("plugin/transfer-quota-exceeded")
        }
        val effectiveChunkSize = negotiatedCapabilities
            ?.transferMaxChunkBytes()
            ?.let { minOf(chunkSizeBytes, it) }
            ?: chunkSizeBytes
        val manifest = TransferManifest.fromSource(filename, mimeType, source, effectiveChunkSize, fileId)
        tombstones.remove(manifest.fileId)
        registered[manifest.fileId] = RegisteredTransfer(manifest, source)
        persistOutboundCheckpoint(manifest, source, fromChunkIndex = 0)
        outbox.send(TransferProtocol.TYPE_MANIFEST, TransferProtocol.CAPABILITY, true, manifest.toJson())
        reportProgress(manifest, completedChunks = 0, sentChunks = 0)
        sendRemaining(manifest.fileId, fromChunkIndex = 0)
        return manifest
    }

    @Synchronized
    fun sendRemaining(fileId: String, fromChunkIndex: Int) {
        require(!frozen) { "plugin/transfer-cancelled" }
        require(!isTerminal(fileId)) { "transfer is terminal" }
        val transfer = registered[fileId] ?: throw IllegalArgumentException("unknown outbound fileId")
        val manifest = transfer.manifest
        val source = transfer.source
        require(fromChunkIndex in 0..manifest.chunkCount) { "fromChunkIndex out of range" }
        require(source.sizeBytes == manifest.sizeBytes) { "size mismatch" }
        activeSends.remove(fileId)?.closeStream()
        if (fromChunkIndex == manifest.chunkCount) {
            reportProgress(manifest, completedChunks = manifest.chunkCount, sentChunks = manifest.chunkCount)
            return
        }
        val stream = source.openStream()
        skipFully(stream, fromChunkIndex.toLong() * manifest.chunkSizeBytes.toLong())
        activeSends[fileId] = ActiveSend(manifest, stream, fromChunkIndex)
        pumpChunks(fileId)
    }

    @Synchronized
    fun handleResumeInfo(info: TransferResumeInfo) {
        if (frozen || isTerminal(info.fileId)) return
        if (info.needsManifest) {
            val transfer = registered[info.fileId] ?: throw IllegalArgumentException("unknown outbound fileId")
            outbox.send(TransferProtocol.TYPE_MANIFEST, TransferProtocol.CAPABILITY, true, transfer.manifest.toJson())
            reportProgress(transfer.manifest, completedChunks = 0, sentChunks = 0)
        }
        sendRemaining(info.fileId, info.nextChunkIndex)
    }

    @Synchronized
    fun handleAck(messageId: String) {
        if (frozen) return
        val fileId = activeSends.entries
            .firstOrNull { it.value.outstanding.containsKey(messageId) }?.key ?: return
        if (isTerminal(fileId)) return
        val active = activeSends[fileId] ?: return
        val chunkIndex = active.outstanding.remove(messageId) ?: return
        active.outstandingBytes -= active.manifest.expectedChunkBytes(chunkIndex)
        val completedChunks = active.markAcked(chunkIndex)
        reportProgress(active.manifest, completedChunks, sentChunks = active.nextIndex)
        pumpChunks(fileId)
    }

    /** ACK timeout aborts the affected transfer and releases sender resources. */
    @Synchronized
    fun handleAckTimeout(messageId: String) {
        if (frozen) return
        val fileId = activeSends.entries
            .firstOrNull { it.value.outstanding.containsKey(messageId) }?.key ?: return
        val active = activeSends.remove(fileId) ?: return
        active.closeStream()
        registered.remove(fileId)
        checkpointStore?.delete(fileId)
        recordTombstone(fileId, TerminalOutcome.CANCELLED)
    }

    @Synchronized
    fun cancel(fileId: String, discard: Boolean = false): String {
        activeSends.remove(fileId)?.closeStream()
        registered.remove(fileId)
        checkpointStore?.delete(fileId)
        recordTombstone(fileId, TerminalOutcome.CANCELLED)
        return sendCancel(TransferCancel(fileId, discard))
    }

    /** Clears in-flight outbound state when the transport session ends. */
    @Synchronized
    fun recycleSession() {
        frozen = true
        activeSends.values.forEach { it.closeStream() }
        activeSends.clear()
        registered.clear()
    }

    private fun pumpChunks(fileId: String) {
        if (frozen) return
        val active = activeSends[fileId] ?: return
        while (active.nextIndex < active.manifest.chunkCount) {
            if (active.outstanding.size >= minOf(outstandingWindow, limits.maxOutstandingMessages)) break
            val stream = active.stream ?: break
            val index = active.nextIndex
            val chunkBytes = active.manifest.expectedChunkBytes(index)
            if (active.outstandingBytes + chunkBytes > limits.maxOutstandingBytes) break
            val chunk = TransferChunk(
                active.manifest.fileId,
                index,
                readNextChunk(stream, chunkBytes),
            )
            val messageId = outbox.send(
                TransferProtocol.TYPE_CHUNK,
                TransferProtocol.CAPABILITY,
                true,
                chunk.toJson(),
            )
            active.outstanding[messageId] = index
            active.outstandingBytes += chunkBytes
            active.nextIndex += 1
        }
        if (active.nextIndex == active.manifest.chunkCount) {
            active.closeStream()
            if (active.outstanding.isEmpty()) {
                activeSends.remove(fileId)
                registered.remove(fileId)
                checkpointStore?.delete(fileId)
                recordTombstone(fileId, TerminalOutcome.COMPLETED)
            }
        } else {
            persistOutboundCheckpoint(active.manifest, registered[fileId]?.source, fromChunkIndex = active.nextIndex)
        }
    }

    private fun persistOutboundCheckpoint(
        manifest: TransferManifest,
        source: TransferByteSource?,
        fromChunkIndex: Int,
    ) {
        val store = checkpointStore ?: return
        val binding = sessionBinding ?: return
        val sourcePath = (source as? FileTransferByteSource)?.file?.absolutePath
        if (sourcePath == null && fromChunkIndex > 0) {
            store.delete(manifest.fileId)
            return
        }
        val now = System.currentTimeMillis()
        store.save(
            TransferCheckpointStore.Record(
                fileId = manifest.fileId,
                manifest = manifest,
                peerFingerprintHex = binding.peerFingerprint.joinToString("") { "%02x".format(it) },
                sessionId = binding.sessionId,
                nextChunkIndex = fromChunkIndex,
                receivedRanges = outboundRanges(fromChunkIndex, manifest.chunkCount),
                updatedAtMs = now,
                expiresAtMs = now + TransferCheckpointStore.DEFAULT_TTL_MS,
                direction = TransferCheckpointStore.Record.Direction.OUTBOUND,
                outboundSourcePath = sourcePath,
            ),
        )
    }

    private fun restoreOutboundCheckpoints(peerFingerprint: ByteArray) {
        val store = checkpointStore ?: return
        val binding = sessionBinding ?: return
        for (record in store.loadActiveForPeer(peerFingerprint, TransferCheckpointStore.Record.Direction.OUTBOUND)) {
            if (record.sessionId != binding.sessionId) continue
            val path = record.outboundSourcePath
            if (path == null || !File(path).isFile) {
                store.delete(record.fileId)
                continue
            }
            val source = FileTransferByteSource(File(path))
            if (source.sizeBytes != record.manifest.sizeBytes) {
                store.delete(record.fileId)
                continue
            }
            registered[record.fileId] = RegisteredTransfer(record.manifest, source)
        }
    }

    private fun outboundRanges(fromChunkIndex: Int, chunkCount: Int): List<IntRange> =
        if (fromChunkIndex <= 0 || fromChunkIndex >= chunkCount) {
            emptyList()
        } else {
            listOf(0 until fromChunkIndex)
        }

    private fun reportProgress(manifest: TransferManifest, completedChunks: Int, sentChunks: Int) {
        onProgress(
            TransferProgress.from(
                manifest,
                completedChunks = completedChunks,
                sentChunks = sentChunks,
            ),
        )
    }

    private fun isTerminal(fileId: String): Boolean = fileId in tombstones

    private fun recordTombstone(fileId: String, outcome: TerminalOutcome) {
        tombstones[fileId] = outcome
        while (tombstones.size > limits.maxCompletedEntries) {
            val oldest = tombstones.keys.first()
            tombstones.remove(oldest)
        }
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

private enum class TerminalOutcome {
    COMPLETED,
    CANCELLED,
}

private data class ReceiverTombstone(
    val manifest: TransferManifest,
    val outcome: TerminalOutcome,
)

class TransferReceiver(
    private val storage: TransferStorage = FileTransferStorage(
        File(System.getProperty("java.io.tmpdir") ?: ".", "hyphen-transfer"),
    ),
    private val limits: TransferResourceLimits = TransferResourceLimits(),
    private val checkpointStore: TransferCheckpointStore? = null,
    private val onProgress: (TransferProgress) -> Unit = {},
) {
    private val states = mutableMapOf<String, TransferState>()
    private val tombstones = linkedMapOf<String, ReceiverTombstone>()
    private var frozen = false
    private var sessionBinding: TransferSender.TransferSessionBinding? = null

    @Synchronized
    fun bindSession(peerFingerprint: ByteArray, sessionId: String) {
        // A new session re-arms a receiver that was frozen by recycleSession; the
        // receiver is a long-lived singleton, so without this it would reject every
        // transfer after the first disconnect.
        frozen = false
        sessionBinding = TransferSender.TransferSessionBinding(peerFingerprint.copyOf(), sessionId)
        checkpointStore?.purgeExpired()
        restorePersistedInboundCheckpoints(peerFingerprint)
    }

    @Synchronized
    fun invalidatePeerCheckpoints(peerFingerprint: ByteArray) {
        checkpointStore?.invalidatePeer(peerFingerprint)
    }

    private fun activeStagingBytes(): Long =
        states.values.sumOf { it.manifest.sizeBytes }

    private fun ensureReceiveQuota(manifest: TransferManifest) {
        if (frozen) throw IllegalArgumentException("plugin/transfer-cancelled")
        if (states.size >= limits.maxConcurrentTransfers) {
            throw IllegalArgumentException("plugin/transfer-quota-exceeded")
        }
        if (tombstones.size >= limits.maxCompletedEntries) {
            throw IllegalArgumentException("plugin/transfer-quota-exceeded")
        }
        if (activeStagingBytes() + manifest.sizeBytes > limits.maxStagingBytes) {
            throw IllegalArgumentException("plugin/disk-full")
        }
    }

    @Synchronized
    fun checkpoint(fileId: String): TransferResumeInfo? {
        states[fileId]?.checkpoint()?.let { return it }
        val tombstone = tombstones[fileId] ?: return null
        return when (tombstone.outcome) {
            TerminalOutcome.COMPLETED ->
                TransferResumeInfo(fileId, tombstone.manifest.chunkCount)
            TerminalOutcome.CANCELLED -> null
        }
    }

    /** Drops active partial receives when the transport session ends. */
    @Synchronized
    fun recycleSession() {
        frozen = true
        states.values.forEach { it.close() }
        states.clear()
    }

    @Synchronized
    fun handle(envelope: Envelope): TransferEvent {
        if (envelope.capability != TransferProtocol.CAPABILITY) return TransferEvent.Ignored
        return when (envelope.type) {
            TransferProtocol.TYPE_MANIFEST -> {
                val manifest = TransferManifest.fromJson(envelope.payload)
                states[manifest.fileId]?.let { existing ->
                    require(existing.manifest == manifest) { "manifest does not match active transfer" }
                    onProgress(existing.progress())
                    completeIfReady(existing)?.let { return TransferEvent.Completed(it) }
                    return TransferEvent.Ignored
                }
                tombstones[manifest.fileId]?.let { existing ->
                    require(existing.manifest == manifest) { "manifest does not match terminal transfer" }
                    return TransferEvent.Ignored
                }
                ensureReceiveQuota(manifest)
                val state = TransferState(manifest, storage.prepare(manifest))
                states[manifest.fileId] = state
                persistInboundCheckpoint(state)
                onProgress(TransferProgress.from(manifest, completedChunks = 0))
                completeIfReady(state)?.let(TransferEvent::Completed) ?: TransferEvent.Ignored
            }
            TransferProtocol.TYPE_CHUNK -> {
                val chunk = TransferChunk.fromJson(envelope.payload)
                if (frozen) throw IllegalArgumentException("plugin/transfer-cancelled")
                val state = states[chunk.fileId] ?: throw IllegalArgumentException("unknown fileId")
                state.accept(chunk)
                onProgress(state.progress())
                persistInboundCheckpoint(state)
                completeIfReady(state)?.let(TransferEvent::Completed) ?: TransferEvent.Ignored
            }
            TransferProtocol.TYPE_RESUME_REQUEST -> {
                val request = TransferResumeRequest.fromJson(envelope.payload)
                TransferEvent.ResumeRequested(
                    states[request.fileId]?.checkpoint()
                        ?: checkpoint(request.fileId)
                        ?: TransferResumeInfo(request.fileId, nextChunkIndex = 0, needsManifest = true),
                )
            }
            TransferProtocol.TYPE_CANCEL -> {
                val cancel = TransferCancel.fromJson(envelope.payload)
                val state = states.remove(cancel.fileId)
                state?.close()
                if (cancel.discard) {
                    storage.discard(cancel.fileId)
                    checkpointStore?.delete(cancel.fileId)
                }
                state?.let {
                    recordTombstone(
                        cancel.fileId,
                        ReceiverTombstone(it.manifest, TerminalOutcome.CANCELLED),
                    )
                }
                TransferEvent.Cancelled(cancel)
            }
            else -> TransferEvent.Ignored
        }
    }

    private fun completeIfReady(state: TransferState): TransferCompleted? {
        val file = state.fileIfComplete() ?: return null
        state.close()
        require(file.length() == state.manifest.sizeBytes) { "size mismatch" }
        val digest = file.inputStream().use { sha256Hex(it) }
        require(digest == state.manifest.sha256) { "file sha256 mismatch" }
        states.remove(state.manifest.fileId)
        checkpointStore?.delete(state.manifest.fileId)
        val done = TransferCompleted(state.manifest, file, digest)
        recordTombstone(
            state.manifest.fileId,
            ReceiverTombstone(state.manifest, TerminalOutcome.COMPLETED),
        )
        return done
    }

    private fun recordTombstone(fileId: String, tombstone: ReceiverTombstone) {
        tombstones[fileId] = tombstone
        while (tombstones.size > limits.maxCompletedEntries) {
            val oldest = tombstones.keys.first()
            tombstones.remove(oldest)
        }
    }

    private fun persistInboundCheckpoint(state: TransferState) {
        val store = checkpointStore ?: return
        val binding = sessionBinding ?: return
        val now = System.currentTimeMillis()
        store.save(
            TransferCheckpointStore.Record(
                fileId = state.manifest.fileId,
                manifest = state.manifest,
                peerFingerprintHex = binding.peerFingerprint.joinToString("") { "%02x".format(it) },
                sessionId = binding.sessionId,
                nextChunkIndex = state.checkpoint().nextChunkIndex,
                // Persist only the contiguous prefix (O(1)); resume drives from
                // nextChunkIndex, so storing the full received bitmap here would
                // re-introduce an O(n) scan on every chunk.
                receivedRanges = state.contiguousReceivedRanges(),
                updatedAtMs = now,
                expiresAtMs = now + TransferCheckpointStore.DEFAULT_TTL_MS,
            ),
        )
    }

    private fun restorePersistedInboundCheckpoints(peerFingerprint: ByteArray) {
        val store = checkpointStore ?: return
        val fileStorage = storage as? FileTransferStorage
        for (record in store.loadActiveForPeer(peerFingerprint, TransferCheckpointStore.Record.Direction.INBOUND)) {
            if (states.containsKey(record.fileId) || tombstones.containsKey(record.fileId)) continue
            val partFile = fileStorage?.partFile(record.fileId)
            if (partFile == null || !partFile.isFile) {
                store.delete(record.fileId)
                continue
            }
            // Fail closed: restored checkpoints must respect the same concurrency
            // and staging-byte ceilings as live manifests. Over-quota records stay
            // on disk for a later session or TTL purge rather than reloading.
            if (states.size >= limits.maxConcurrentTransfers) continue
            if (activeStagingBytes() + record.manifest.sizeBytes > limits.maxStagingBytes) continue
            runCatching {
                val state = TransferState.restore(record.manifest, partFile, record.receivedRanges)
                states[record.fileId] = state
                onProgress(state.progress())
            }.onFailure {
                store.delete(record.fileId)
                storage.discard(record.fileId)
            }
        }
    }
}

private class TransferState private constructor(
    val manifest: TransferManifest,
    private val file: File,
    private val received: BooleanArray,
) {
    private var handle: RandomAccessFile? = null
    var receivedCount: Int = 0
        private set
    var highestContiguousIndex: Int = -1
        private set
    var receivedBytes: Long = 0
        private set

    init {
        recomputeDerivedState()
    }

    constructor(manifest: TransferManifest, file: File) : this(
        manifest,
        file,
        BooleanArray(manifest.chunkCount),
    )

    companion object {
        fun restore(
            manifest: TransferManifest,
            file: File,
            ranges: List<IntRange>,
        ): TransferState {
            val received = BooleanArray(manifest.chunkCount)
            for (range in ranges) {
                for (index in range) {
                    require(index in received.indices) { "received range out of bounds" }
                    received[index] = true
                }
            }
            return TransferState(manifest, file, received)
        }
    }

    fun receivedRanges(): List<IntRange> = compactReceivedRanges(received)

    /** O(1) contiguous-prefix range used for durable checkpoints. */
    fun contiguousReceivedRanges(): List<IntRange> =
        if (highestContiguousIndex < 0) emptyList() else listOf(0 until highestContiguousIndex + 1)

    private fun recomputeDerivedState() {
        receivedCount = 0
        receivedBytes = 0
        highestContiguousIndex = -1
        for (index in received.indices) {
            if (!received[index]) continue
            receivedCount += 1
            receivedBytes += manifest.expectedChunkBytes(index).toLong()
        }
        var next = 0
        while (next < received.size && received[next]) {
            highestContiguousIndex = next
            next += 1
        }
    }

    fun accept(chunk: TransferChunk) {
        require(chunk.chunkIndex < received.size) { "chunkIndex out of range" }
        require(chunk.data.size == manifest.expectedChunkBytes(chunk.chunkIndex)) { "chunk size mismatch" }
        if (received[chunk.chunkIndex]) return
        val raf = handle ?: RandomAccessFile(file, "rw").also { handle = it }
        raf.seek(chunk.chunkIndex.toLong() * manifest.chunkSizeBytes.toLong())
        raf.write(chunk.data)
        received[chunk.chunkIndex] = true
        receivedCount += 1
        receivedBytes += chunk.data.size.toLong()
        if (chunk.chunkIndex == highestContiguousIndex + 1) {
            highestContiguousIndex = chunk.chunkIndex
            while (highestContiguousIndex + 1 < received.size && received[highestContiguousIndex + 1]) {
                highestContiguousIndex += 1
            }
        }
    }

    /** Releases the long-lived write handle so the file can be read back and the descriptor is not leaked. */
    fun close() {
        handle?.let { runCatching { it.close() } }
        handle = null
    }

    fun fileIfComplete(): File? =
        if (receivedCount == manifest.chunkCount) file else null

    fun checkpoint(): TransferResumeInfo =
        TransferResumeInfo(manifest.fileId, highestContiguousIndex + 1)

    fun progress(): TransferProgress =
        TransferProgress.from(
            manifest,
            completedChunks = highestContiguousIndex + 1,
            receivedChunks = receivedCount,
        )
}

private fun compactReceivedRanges(received: BooleanArray): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    var index = 0
    while (index < received.size) {
        if (!received[index]) {
            index += 1
            continue
        }
        val start = index
        while (index < received.size && received[index]) index += 1
        ranges.add(start until index)
    }
    return ranges
}

private fun ByteArray.toHexLower(): String = joinToString("") { "%02x".format(it) }

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

private fun optionalBool(payload: Json.Obj, field: String): Boolean? =
    when (val value = payload[field]) {
        null -> null
        is Json.Bool -> value.value
        else -> throw IllegalArgumentException("$field must be boolean")
    }

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

/** Reads up to [maxBytes] sequentially from an already-positioned stream. */
private fun readNextChunk(input: InputStream, maxBytes: Int): ByteArray {
    val buffer = ByteArray(maxBytes)
    var total = 0
    while (total < maxBytes) {
        val read = input.read(buffer, total, maxBytes - total)
        if (read == -1) break
        total += read
    }
    return if (total == maxBytes) buffer else buffer.copyOf(total)
}
