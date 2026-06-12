package dev.hyphen.android.notifications

import dev.hyphen.android.transport.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

data class SentNotificationEnvelope(
    val type: String,
    val capability: String,
    val requiresAck: Boolean,
    val payload: Json.Obj,
)

class RecordingNotificationOutbox : NotificationOutbox {
    val envelopes = mutableListOf<SentNotificationEnvelope>()

    override fun send(
        type: String,
        capability: String,
        requiresAck: Boolean,
        payload: Json.Obj,
    ): String {
        envelopes += SentNotificationEnvelope(type, capability, requiresAck, payload)
        return "01JZ0000000000000000000000"
    }
}

class NotificationMirrorEventSenderTest {

    @Test
    fun `first key posts and repeated key updates existing record`() {
        val outbox = RecordingNotificationOutbox()
        val sender = NotificationMirrorEventSender(outbox)
        val payload = payload("0|com.chat|7|thread-123|10101", "hello")

        sender.sendPostedOrUpdated(payload)
        sender.sendPostedOrUpdated(payload.copy(text = "hello again"))

        assertEquals(2, outbox.envelopes.size)
        assertEquals(NotificationProtocol.TYPE_POSTED, outbox.envelopes[0].type)
        assertEquals(NotificationProtocol.TYPE_UPDATED, outbox.envelopes[1].type)
        assertEquals(NotificationProtocol.CAPABILITY, outbox.envelopes[0].capability)
        assertEquals(true, outbox.envelopes[0].requiresAck)
        assertEquals(Json.Str("0|com.chat|7|thread-123|10101"), outbox.envelopes[1].payload["sbnKey"])
        assertEquals(Json.Str("hello again"), outbox.envelopes[1].payload["text"])
        assertEquals(setOf("0|com.chat|7|thread-123|10101"), sender.activeKeys())
    }

    @Test
    fun `removed event carries only the stable sbn key and clears active state`() {
        val outbox = RecordingNotificationOutbox()
        val sender = NotificationMirrorEventSender(outbox)

        sender.sendPostedOrUpdated(payload("0|com.mail|42|null|10101", "new mail"))
        sender.sendRemoved("0|com.mail|42|null|10101")

        val removed = outbox.envelopes.last()
        assertEquals(NotificationProtocol.TYPE_REMOVED, removed.type)
        assertEquals(NotificationProtocol.CAPABILITY, removed.capability)
        assertEquals(true, removed.requiresAck)
        assertEquals(setOf("sbnKey"), removed.payload.entries.keys)
        assertEquals(Json.Str("0|com.mail|42|null|10101"), removed.payload["sbnKey"])
        assertTrue(sender.activeKeys().isEmpty())
    }

    @Test
    fun `removed unknown key is still sent so stale macOS records can close`() {
        val outbox = RecordingNotificationOutbox()
        val sender = NotificationMirrorEventSender(outbox)

        sender.sendRemoved("0|com.chat|99|null|10101")

        assertEquals(NotificationProtocol.TYPE_REMOVED, outbox.envelopes.single().type)
        assertTrue(sender.activeKeys().isEmpty())
    }

    @Test
    fun `hidden body mode strips notification text before sending`() {
        val outbox = RecordingNotificationOutbox()
        val sender = NotificationMirrorEventSender(outbox, NotificationPrivacyMode.HIDE_BODY)

        sender.sendPostedOrUpdated(payload("0|com.chat|7|thread-123|10101", "secret body"))

        val sent = outbox.envelopes.single()
        assertEquals(NotificationProtocol.TYPE_POSTED, sent.type)
        assertEquals(Json.Str("Example"), sent.payload["title"])
        assertFalse(sent.payload.entries.containsKey("text"))
        assertFalse(sent.payload.encode().contains("secret body"))
    }

    @Test
    fun `changing privacy mode preserves active key update behavior`() {
        val outbox = RecordingNotificationOutbox()
        val sender = NotificationMirrorEventSender(outbox)

        sender.sendPostedOrUpdated(payload("0|com.chat|7|thread-123|10101", "first body"))
        sender.setPrivacyMode(NotificationPrivacyMode.HIDE_BODY)
        sender.sendPostedOrUpdated(payload("0|com.chat|7|thread-123|10101", "second body"))

        val updated = outbox.envelopes.last()
        assertEquals(NotificationProtocol.TYPE_UPDATED, updated.type)
        assertFalse(updated.payload.entries.containsKey("text"))
        assertFalse(updated.payload.encode().contains("second body"))
        assertEquals(setOf("0|com.chat|7|thread-123|10101"), sender.activeKeys())
    }

    private fun payload(sbnKey: String, text: String): NormalizedNotificationPayload =
        NormalizedNotificationPayload(
            sbnKey = sbnKey,
            packageName = "com.example",
            title = "Example",
            text = text,
            category = "msg",
            isClearable = true,
        )
}
