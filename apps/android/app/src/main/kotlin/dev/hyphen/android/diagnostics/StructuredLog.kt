package dev.hyphen.android.diagnostics

import dev.hyphen.android.transport.Envelope
import dev.hyphen.android.transport.HeartbeatMonitor
import dev.hyphen.android.transport.ProtocolSession
import dev.hyphen.android.transport.ProtocolTrace
import java.util.ArrayDeque

enum class DiagnosticLogLevel {
    INFO,
    WARNING,
    ERROR,
}

data class StructuredLogEvent(
    val timestampUnixMs: Long,
    val level: DiagnosticLogLevel,
    val category: String,
    val code: String,
    val attributes: Map<String, String> = emptyMap(),
    val traceId: String? = null,
)

class LocalStructuredLogStore(
    private val maxEntries: Int = 500,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val events = ArrayDeque<StructuredLogEvent>()

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    @Synchronized
    fun recordFailure(
        code: String,
        component: String,
        operation: String,
        traceId: String? = null,
    ): StructuredLogEvent {
        val safeCode = validateCode(code)
        val safeTraceId = traceId?.also {
            require(ProtocolTrace.isValidSpanId(it)) { "traceId must be a ULID" }
        }
        val event = StructuredLogEvent(
            timestampUnixMs = clock(),
            level = DiagnosticLogLevel.ERROR,
            category = safeCode.substringBefore('/'),
            code = safeCode,
            attributes = mapOf(
                "component" to validateToken("component", component),
                "operation" to validateToken("operation", operation),
            ),
            traceId = safeTraceId,
        )
        append(event)
        return event
    }

    @Synchronized
    fun snapshot(): List<StructuredLogEvent> = events.toList()

    @Synchronized
    fun clear() {
        events.clear()
    }

    private fun append(event: StructuredLogEvent) {
        while (events.size >= maxEntries) {
            events.removeFirst()
        }
        events.addLast(event)
    }

    private fun validateCode(code: String): String {
        require(codePattern.matches(code)) { "invalid diagnostic code" }
        val category = code.substringBefore('/')
        require(category in allowedCategories) { "unknown diagnostic category" }
        return code
    }

    private fun validateToken(field: String, value: String): String {
        require(tokenPattern.matches(value)) { "$field must be a safe token" }
        return value
    }

    private companion object {
        val allowedCategories = setOf("protocol", "transport", "trust", "permission", "plugin")
        val codePattern = Regex("^[a-z]+/[a-z0-9-]{1,64}$")
        val tokenPattern = Regex("^[a-z0-9_.-]{1,64}$")
    }
}

class DiagnosticProtocolSessionListener(
    private val logs: LocalStructuredLogStore,
    private val delegate: ProtocolSession.Listener,
) : ProtocolSession.Listener {
    override fun onEnvelope(envelope: Envelope) = delegate.onEnvelope(envelope)

    override fun onLiveness(state: HeartbeatMonitor.State) = delegate.onLiveness(state)

    override fun onAck(messageId: String) = delegate.onAck(messageId)

    override fun onAckTimeout(messageId: String) {
        record("protocol/ack-timeout", "ack-timeout")
        delegate.onAckTimeout(messageId)
    }

    override fun onProtocolError(code: String, detail: String) {
        record(code, "protocol-error")
        delegate.onProtocolError(code, detail)
    }

    override fun onClosed() = delegate.onClosed()

    private fun record(code: String, operation: String) {
        runCatching {
            logs.recordFailure(
                code = code,
                component = "protocol-session",
                operation = operation,
            )
        }
    }
}
