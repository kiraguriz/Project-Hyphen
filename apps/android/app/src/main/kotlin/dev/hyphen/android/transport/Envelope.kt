package dev.hyphen.android.transport

/**
 * Protocol v0 envelope (HYP-M2-012, protocol doc §3) — model + strict
 * codec matching `protocol/schema/envelope.schema.json` exactly: unknown
 * fields rejected, patterns enforced, `sessionId` nullable (null only in
 * hello per session logic — structurally nullable here, like the schema).
 */
data class Envelope(
    val protocol: String = PROTOCOL_ID,
    val messageId: String,
    val sessionId: String?,
    val type: String,
    val capability: String? = null,
    val seq: Long,
    val ackOf: String? = null,
    val sentAtUnixMs: Long,
    val requiresAck: Boolean,
    val payload: Json.Obj = Json.Obj.EMPTY,
    val trace: Json.Obj? = null,
) {
    fun encode(): ByteArray {
        val entries = LinkedHashMap<String, Json>()
        entries["protocol"] = Json.Str(protocol)
        entries["messageId"] = Json.Str(messageId)
        entries["sessionId"] = sessionId?.let { Json.Str(it) } ?: Json.Null
        entries["type"] = Json.Str(type)
        capability?.let { entries["capability"] = Json.Str(it) }
        entries["seq"] = Json.Num(seq.toString())
        ackOf?.let { entries["ackOf"] = Json.Str(it) }
        entries["sentAtUnixMs"] = Json.Num(sentAtUnixMs.toString())
        entries["requiresAck"] = Json.Bool(requiresAck)
        entries["payload"] = payload
        trace?.let { entries["trace"] = it }
        return Json.Obj(entries).encode().toByteArray(Charsets.UTF_8)
    }

    companion object {
        /** Protocol identifier this implementation speaks. */
        const val PROTOCOL_ID = "hyphen/0.3"

        const val TYPE_HEARTBEAT = "heartbeat"
        const val TYPE_ACK = "ack"
        const val TYPE_HELLO = "hello"
        const val TYPE_ERROR = "error"

        private val KNOWN_FIELDS = setOf(
            "protocol", "messageId", "sessionId", "type", "capability",
            "seq", "ackOf", "sentAtUnixMs", "requiresAck", "payload", "trace",
        )
        private val PROTOCOL_SHAPE = Regex("^hyphen/0\\.[0-9]+$")
        private val TYPE_SHAPE = Regex("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)*$")
        private val SESSION_SHAPE = Regex("^s_[A-Za-z0-9_-]{2,64}$")
        private val CAPABILITY_SHAPE = Regex("^[a-z][a-z0-9]*\\.v[0-9]+$")

        fun decode(bytes: ByteArray): Envelope {
            val root = try {
                Json.parse(bytes.toString(Charsets.UTF_8))
            } catch (e: JsonParseException) {
                throw EnvelopeException("not JSON: ${e.message}")
            }
            val obj = root as? Json.Obj ?: throw EnvelopeException("envelope must be an object")

            val unknown = obj.entries.keys - KNOWN_FIELDS
            if (unknown.isNotEmpty()) throw EnvelopeException("unknown fields: $unknown")

            val protocol = string(obj, "protocol")
            if (!PROTOCOL_SHAPE.matches(protocol)) throw EnvelopeException("bad protocol '$protocol'")

            val messageId = string(obj, "messageId")
            if (!Ulid.isValid(messageId)) throw EnvelopeException("messageId is not a ULID")

            val sessionId = when (val raw = obj["sessionId"] ?: throw EnvelopeException("sessionId missing")) {
                is Json.Null -> null
                is Json.Str ->
                    raw.value.takeIf { SESSION_SHAPE.matches(it) }
                        ?: throw EnvelopeException("bad sessionId")
                else -> throw EnvelopeException("sessionId must be string or null")
            }

            val type = string(obj, "type")
            if (!TYPE_SHAPE.matches(type)) throw EnvelopeException("bad type '$type'")

            val capability = optionalString(obj, "capability")?.also {
                if (!CAPABILITY_SHAPE.matches(it)) throw EnvelopeException("bad capability '$it'")
            }

            val seq = long(obj, "seq")
            if (seq < 1) throw EnvelopeException("seq must be >= 1")

            val ackOf = when (val raw = obj["ackOf"]) {
                null, is Json.Null -> null
                is Json.Str ->
                    raw.value.takeIf(Ulid::isValid) ?: throw EnvelopeException("ackOf is not a ULID")
                else -> throw EnvelopeException("ackOf must be string or null")
            }

            val sentAtUnixMs = long(obj, "sentAtUnixMs")
            if (sentAtUnixMs < 0) throw EnvelopeException("sentAtUnixMs must be >= 0")

            val requiresAck = (obj["requiresAck"] as? Json.Bool
                ?: throw EnvelopeException("requiresAck missing or not boolean")).value

            val payload = obj["payload"] as? Json.Obj
                ?: throw EnvelopeException("payload missing or not an object")

            val trace = when (val raw = obj["trace"]) {
                null -> null
                is Json.Obj -> validateTrace(raw)
                else -> throw EnvelopeException("trace must be an object")
            }

            return Envelope(
                protocol, messageId, sessionId, type, capability,
                seq, ackOf, sentAtUnixMs, requiresAck, payload, trace,
            )
        }

        private fun validateTrace(trace: Json.Obj): Json.Obj {
            val unknown = trace.entries.keys - setOf("localOnly", "spanId")
            if (unknown.isNotEmpty()) throw EnvelopeException("unknown trace fields: $unknown")
            val localOnly = trace["localOnly"] as? Json.Bool
                ?: throw EnvelopeException("trace.localOnly missing or not boolean")
            if (!localOnly.value) throw EnvelopeException("trace.localOnly must be true")
            when (val spanId = trace["spanId"]) {
                null -> Unit
                is Json.Str -> {
                    if (!ProtocolTrace.isValidSpanId(spanId.value)) {
                        throw EnvelopeException("trace.spanId must be a ULID")
                    }
                }
                else -> throw EnvelopeException("trace.spanId must be a string")
            }
            return trace
        }

        private fun string(obj: Json.Obj, field: String): String =
            (obj[field] as? Json.Str ?: throw EnvelopeException("$field missing or not a string")).value

        private fun optionalString(obj: Json.Obj, field: String): String? =
            when (val raw = obj[field]) {
                null -> null
                is Json.Str -> raw.value
                else -> throw EnvelopeException("$field must be a string")
            }

        private fun long(obj: Json.Obj, field: String): Long =
            (obj[field] as? Json.Num ?: throw EnvelopeException("$field missing or not a number"))
                .asLong() ?: throw EnvelopeException("$field is not an integer")
    }
}

class ProtocolTrace private constructor(val spanId: String) {
    fun toJson(): Json.Obj =
        Json.obj(
            "localOnly" to Json.Bool(true),
            "spanId" to Json.Str(spanId),
        )

    companion object {
        fun local(spanId: String = Ulid.generate()): ProtocolTrace {
            require(isValidSpanId(spanId)) { "spanId must be a ULID" }
            return ProtocolTrace(spanId)
        }

        fun isValidSpanId(spanId: String): Boolean = Ulid.isValid(spanId)
    }
}

/** Maps to `protocol/invalid-envelope` in the error taxonomy. */
class EnvelopeException(message: String) : Exception(message)
