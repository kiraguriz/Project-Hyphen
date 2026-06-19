package dev.hyphen.android.transfer

import dev.hyphen.android.transport.Json
import java.io.File

/**
 * Peer-bound durable transfer checkpoints (dimension 02 P2). Stores manifest,
 * temp `.part` identity, contiguous resume index, compact received ranges,
 * session binding, and expiry under app-private storage. Process restart can
 * reload valid partial receives; [invalidatePeer] clears checkpoints on trust
 * revoke/reset.
 */
class TransferCheckpointStore(
    private val root: File,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = DEFAULT_TTL_MS,
) {
    data class Record(
        val fileId: String,
        val manifest: TransferManifest,
        val peerFingerprintHex: String,
        val sessionId: String,
        val nextChunkIndex: Int,
        val receivedRanges: List<IntRange>,
        val updatedAtMs: Long,
        val expiresAtMs: Long,
        val direction: Direction = Direction.INBOUND,
        val outboundSourcePath: String? = null,
    ) {
        enum class Direction { INBOUND, OUTBOUND }
    }

    init {
        require(root.exists() || root.mkdirs()) { "transfer checkpoint storage unavailable" }
    }

    @Synchronized
    fun save(record: Record) {
        File(root, fileName(record.fileId)).writeText(encode(record))
    }

    @Synchronized
    fun load(fileId: String): Record? {
        val file = File(root, fileName(fileId))
        if (!file.isFile) return null
        return runCatching { decode(file.readText()) }
            .getOrNull()
            ?.takeUnless { isExpired(it) }
    }

    @Synchronized
    fun loadActiveForPeer(peerFingerprint: ByteArray, direction: Record.Direction? = null): List<Record> {
        val hex = peerFingerprint.toHexLower()
        return root.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.name.endsWith(".json") }
            .mapNotNull { runCatching { decode(it.readText()) }.getOrNull() }
            .filter { it.peerFingerprintHex == hex }
            .filter { direction == null || it.direction == direction }
            .filterNot { isExpired(it) }
            .toList()
    }

    @Synchronized
    fun delete(fileId: String) {
        File(root, fileName(fileId)).delete()
    }

    @Synchronized
    fun invalidatePeer(peerFingerprint: ByteArray) {
        val hex = peerFingerprint.toHexLower()
        root.listFiles()?.forEach { file ->
            if (!file.isFile || !file.name.endsWith(".json")) return@forEach
            val record = runCatching { decode(file.readText()) }.getOrNull() ?: return@forEach
            if (record.peerFingerprintHex == hex) {
                file.delete()
            }
        }
    }

    @Synchronized
    fun purgeExpired() {
        root.listFiles()?.forEach { file ->
            if (!file.isFile || !file.name.endsWith(".json")) return@forEach
            val record = runCatching { decode(file.readText()) }.getOrNull()
            if (record == null || isExpired(record)) {
                file.delete()
            }
        }
    }

    private fun isExpired(record: Record): Boolean = nowMs() >= record.expiresAtMs

    private fun fileName(fileId: String): String = "$fileId.json"

    private fun encode(record: Record): String {
        val ranges = Json.Arr(
            record.receivedRanges.map { range ->
                Json.Arr(listOf(Json.Num(range.first.toString()), Json.Num((range.last + 1).toString())))
            },
        )
        val fields = linkedMapOf(
            "version" to Json.Num("1"),
            "fileId" to Json.Str(record.fileId),
            "manifest" to record.manifest.toJson(),
            "peerFingerprintHex" to Json.Str(record.peerFingerprintHex),
            "sessionId" to Json.Str(record.sessionId),
            "nextChunkIndex" to Json.Num(record.nextChunkIndex.toString()),
            "receivedRanges" to ranges,
            "updatedAtMs" to Json.Num(record.updatedAtMs.toString()),
            "expiresAtMs" to Json.Num(record.expiresAtMs.toString()),
            "direction" to Json.Str(record.direction.name.lowercase()),
        )
        if (record.outboundSourcePath != null) {
            fields["outboundSourcePath"] = Json.Str(record.outboundSourcePath)
        }
        return Json.Obj(fields).encode()
    }

    private fun decode(text: String): Record {
        val obj = Json.parse(text) as Json.Obj
        require(int(obj, "version") == 1) { "unsupported checkpoint version" }
        val direction = when (string(obj, "direction")) {
            "outbound" -> Record.Direction.OUTBOUND
            else -> Record.Direction.INBOUND
        }
        val ranges = (obj["receivedRanges"] as Json.Arr).items.map { item ->
            val pair = item as Json.Arr
            val start = int(pair.items[0] as Json.Num)
            val endExclusive = int(pair.items[1] as Json.Num)
            require(endExclusive > start) { "invalid received range" }
            start until endExclusive
        }
        return Record(
            fileId = string(obj, "fileId"),
            manifest = TransferManifest.fromJson(obj["manifest"] as Json.Obj),
            peerFingerprintHex = string(obj, "peerFingerprintHex"),
            sessionId = string(obj, "sessionId"),
            nextChunkIndex = int(obj, "nextChunkIndex"),
            receivedRanges = ranges,
            updatedAtMs = long(obj, "updatedAtMs"),
            expiresAtMs = long(obj, "expiresAtMs"),
            direction = direction,
            outboundSourcePath = (obj["outboundSourcePath"] as? Json.Str)?.value,
        )
    }

    companion object {
        const val DEFAULT_TTL_MS = 7L * 24 * 60 * 60 * 1000

        fun defaultRoot(context: android.content.Context): File =
            File(context.filesDir, "transfer-checkpoints")
    }
}

private fun ByteArray.toHexLower(): String = joinToString("") { "%02x".format(it) }

private fun string(payload: Json.Obj, field: String): String =
    (payload[field] as? Json.Str)?.value ?: throw IllegalArgumentException("$field must be string")

private fun long(payload: Json.Obj, field: String): Long =
    (payload[field] as? Json.Num)?.asLong() ?: throw IllegalArgumentException("$field must be integer")

private fun int(payload: Json.Obj, field: String): Int =
    long(payload, field).also {
        require(it in Int.MIN_VALUE..Int.MAX_VALUE) { "$field out of int range" }
    }.toInt()

private fun int(num: Json.Num): Int =
    num.asLong()?.toInt() ?: throw IllegalArgumentException("invalid integer")
