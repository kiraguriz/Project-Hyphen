package dev.hyphen.android.diagnostics

import dev.hyphen.android.transport.ProtocolSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class StructuredLogTest {
    @Test
    fun `protocol errors record codes but omit sensitive detail`() {
        val logs = LocalStructuredLogStore(clock = { 1234L })
        val forwarded = mutableListOf<String>()
        val listener = DiagnosticProtocolSessionListener(
            logs = logs,
            delegate = object : ProtocolSession.Listener {
                override fun onProtocolError(code: String, detail: String) {
                    forwarded += detail
                }
            },
        )
        val sensitiveDetail = "body=secret notification /Users/alice/private.txt https://secret.example"

        listener.onProtocolError("transport/frame-too-large", sensitiveDetail)

        val event = logs.snapshot().single()
        assertEquals(1234L, event.timestampUnixMs)
        assertEquals(DiagnosticLogLevel.ERROR, event.level)
        assertEquals("transport", event.category)
        assertEquals("transport/frame-too-large", event.code)
        assertEquals(
            mapOf("component" to "protocol-session", "operation" to "protocol-error"),
            event.attributes,
        )
        assertEquals(listOf(sensitiveDetail), forwarded)

        val rendered = event.toString()
        assertFalse(rendered.contains("secret notification"))
        assertFalse(rendered.contains("/Users/alice"))
        assertFalse(rendered.contains("https://secret.example"))
    }

    @Test
    fun `ack timeout records taxonomy failure code`() {
        val logs = LocalStructuredLogStore(clock = { 42L })
        val forwarded = mutableListOf<String>()
        val listener = DiagnosticProtocolSessionListener(
            logs = logs,
            delegate = object : ProtocolSession.Listener {
                override fun onAckTimeout(messageId: String) {
                    forwarded += messageId
                }
            },
        )

        listener.onAckTimeout("01JZ0000000000000000000000")

        val event = logs.snapshot().single()
        assertEquals("protocol", event.category)
        assertEquals("protocol/ack-timeout", event.code)
        assertEquals("ack-timeout", event.attributes["operation"])
        assertEquals(listOf("01JZ0000000000000000000000"), forwarded)
    }

    @Test
    fun `store keeps bounded local history`() {
        val logs = LocalStructuredLogStore(maxEntries = 2, clock = { 1L })

        logs.recordFailure("protocol/invalid-envelope", "protocol-session", "decode")
        logs.recordFailure("transport/connection-lost", "protocol-session", "read")
        logs.recordFailure("plugin/checksum-mismatch", "transfer-receiver", "verify")

        assertEquals(
            listOf("transport/connection-lost", "plugin/checksum-mismatch"),
            logs.snapshot().map { it.code },
        )
    }

    @Test
    fun `unsafe codes and metadata are rejected`() {
        val logs = LocalStructuredLogStore(clock = { 1L })

        assertThrows(IllegalArgumentException::class.java) {
            logs.recordFailure("diagnostics/private", "protocol-session", "write")
        }
        assertThrows(IllegalArgumentException::class.java) {
            logs.recordFailure("transport/frame-too-large", "protocol session", "write")
        }
        assertThrows(IllegalArgumentException::class.java) {
            logs.recordFailure("transport/frame-too-large", "protocol-session", "/Users/alice/private.txt")
        }
    }
}
