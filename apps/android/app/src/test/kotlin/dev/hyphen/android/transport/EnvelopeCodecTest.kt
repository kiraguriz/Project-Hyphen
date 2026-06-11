package dev.hyphen.android.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvelopeCodecTest {

    private fun envelope(
        sessionId: String? = "s_test1",
        type: String = "heartbeat",
        ackOf: String? = null,
    ) = Envelope(
        messageId = Ulid.generate(),
        sessionId = sessionId,
        type = type,
        seq = 1,
        ackOf = ackOf,
        sentAtUnixMs = 1_781_107_200_000,
        requiresAck = false,
    )

    @Test
    fun `ulid shape and uniqueness`() {
        val a = Ulid.generate()
        val b = Ulid.generate()
        assertTrue(Ulid.isValid(a))
        assertTrue(Ulid.isValid(b))
        assertTrue(a != b)
        // Time prefix orders across distinct milliseconds.
        assertTrue(Ulid.generate(1_000) < Ulid.generate(2_000))
    }

    @Test
    fun `roundtrip preserves every field`() {
        val original = Envelope(
            messageId = Ulid.generate(),
            sessionId = "s_abc-123",
            type = "notification.updated",
            capability = "notifications.v1",
            seq = 42,
            ackOf = Ulid.generate(),
            sentAtUnixMs = 1_781_107_200_000,
            requiresAck = true,
            payload = Json.obj("key" to Json.Str("0|com.app|7|tag|10101")),
            trace = Json.obj("localOnly" to Json.Bool(true)),
        )
        assertEquals(original, Envelope.decode(original.encode()))
    }

    @Test
    fun `null sessionId survives roundtrip`() {
        val hello = envelope(sessionId = null, type = "hello")
        assertNull(Envelope.decode(hello.encode()).sessionId)
    }

    @Test
    fun `unknown fields are rejected`() {
        val json = String(envelope().encode(), Charsets.UTF_8)
            .replaceFirst("{", """{"smuggled":1,""")
        assertThrows(EnvelopeException::class.java) {
            Envelope.decode(json.toByteArray())
        }
    }

    @Test
    fun `structural violations are rejected`() {
        val base = envelope()
        val cases = mapOf(
            "bad messageId" to base.copy(messageId = "not-a-ulid-aaaaaaaaaaaaaaa"),
            "seq zero" to base.copy(seq = 0),
            "bad type" to base.copy(type = "Heart_Beat"),
            "bad capability" to base.copy(capability = "Notifications.V1"),
            "bad sessionId" to base.copy(sessionId = "nope"),
        )
        for ((label, broken) in cases) {
            assertThrows(label, EnvelopeException::class.java) {
                Envelope.decode(broken.encode())
            }
        }
        assertThrows("not even JSON", EnvelopeException::class.java) {
            Envelope.decode("hello".toByteArray())
        }
        assertThrows("missing required field", EnvelopeException::class.java) {
            Envelope.decode("""{"protocol":"hyphen/0.3"}""".toByteArray())
        }
    }

    @Test
    fun `trace is validated strictly`() {
        val withBadTrace = String(
            envelope().encode(), Charsets.UTF_8
        ).replaceFirst("\"payload\"", """"trace":{"localOnly":true,"extra":1},"payload"""")
        assertThrows(EnvelopeException::class.java) {
            Envelope.decode(withBadTrace.toByteArray())
        }
    }
}
