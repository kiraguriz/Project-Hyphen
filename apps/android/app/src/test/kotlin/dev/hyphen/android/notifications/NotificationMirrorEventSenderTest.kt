package dev.hyphen.android.notifications

import dev.hyphen.android.transport.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

data class SentNotificationEnvelope(
    val type: String,
    val capability: String,
    val requiresAck: Boolean,
    val payload: Json.Obj,
)

class RecordingNotificationOutbox : NotificationOutbox {
    val envelopes: MutableList<SentNotificationEnvelope> = Collections.synchronizedList(mutableListOf())

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
    fun `reply actions are omitted when negotiated reply is disabled`() {
        val outbox = RecordingNotificationOutbox()
        val sender = NotificationMirrorEventSender(outbox, allowReplyActions = false)

        sender.sendPostedOrUpdated(
            payload(
                sbnKey = "0|com.chat|7|thread-123|10101",
                text = "reply-capable",
                replyActions = listOf(NotificationReplyAction(2, "Reply", "reply:1:reply:android.reply")),
            ),
        )

        assertFalse(outbox.envelopes.single().payload.entries.containsKey("replyActions"))
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

    @Test
    fun `exists only policy sends presence only and omits all content from the wire`() {
        val outbox = RecordingNotificationOutbox()
        val sender = NotificationMirrorEventSender(outbox)
        sender.setPrivacyPolicy(NotificationPrivacyPolicy(defaultMode = NotificationPrivacyMode.EXISTS_ONLY))

        sender.sendPostedOrUpdated(
            payload(
                sbnKey = "0|com.example|7|thread-123|10101",
                text = "secret body",
                replyActions = listOf(NotificationReplyAction(2, "Reply", "reply:1:reply:android.reply")),
            ),
        )

        val sent = outbox.envelopes.single()
        // Routing/identity stays so update/remove still correlate.
        assertEquals(Json.Str("0|com.example|7|thread-123|10101"), sent.payload["sbnKey"])
        assertEquals(Json.Str("com.example"), sent.payload["packageName"])
        // Everything visible is stripped before the payload crosses the LAN.
        assertFalse(sent.payload.entries.containsKey("title"))
        assertFalse(sent.payload.entries.containsKey("text"))
        assertFalse(sent.payload.entries.containsKey("category"))
        assertFalse(sent.payload.entries.containsKey("replyActions"))
        val encoded = sent.payload.encode()
        assertFalse(encoded.contains("secret body"))
        assertFalse(encoded.contains("Example"))
        assertFalse(encoded.contains("Reply"))
    }

    @Test
    fun `per-package policy overrides the default mode at the send boundary`() {
        val outbox = RecordingNotificationOutbox()
        val sender = NotificationMirrorEventSender(outbox)
        sender.setPrivacyPolicy(
            NotificationPrivacyPolicy(
                defaultMode = NotificationPrivacyMode.EXISTS_ONLY,
                perPackageModes = mapOf("com.example" to NotificationPrivacyMode.SHOW_FULL),
            ),
        )

        sender.sendPostedOrUpdated(payload("0|com.example|7|thread-123|10101", "full content"))

        val sent = outbox.envelopes.single()
        assertEquals(Json.Str("Example"), sent.payload["title"])
        assertEquals(Json.Str("full content"), sent.payload["text"])
    }

    @Test
    fun `notification storm keeps active keys bounded and repeats become updates`() {
        val outbox = RecordingNotificationOutbox()
        val sender = NotificationMirrorEventSender(outbox)
        val keys = (0 until 25).map { index -> "0|com.chat|$index|thread-$index|10101" }

        repeat(1_000) { index ->
            sender.sendPostedOrUpdated(payload(keys[index % keys.size], "message-$index"))
        }
        keys.take(10).forEach(sender::sendRemoved)

        assertEquals(1_010, outbox.envelopes.size)
        assertEquals(25, outbox.envelopes.count { it.type == NotificationProtocol.TYPE_POSTED })
        assertEquals(975, outbox.envelopes.count { it.type == NotificationProtocol.TYPE_UPDATED })
        assertEquals(10, outbox.envelopes.count { it.type == NotificationProtocol.TYPE_REMOVED })
        assertEquals(keys.drop(10).toSet(), sender.activeKeys())
        assertTrue(outbox.envelopes.all { it.capability == NotificationProtocol.CAPABILITY })
        assertTrue(outbox.envelopes.all { it.requiresAck })
    }

    private fun payload(
        sbnKey: String,
        text: String,
        replyActions: List<NotificationReplyAction> = emptyList(),
    ): NormalizedNotificationPayload =
        NormalizedNotificationPayload(
            sbnKey = sbnKey,
            packageName = "com.example",
            title = "Example",
            text = text,
            category = "msg",
            isClearable = true,
            replyActions = replyActions,
        )
}
