package dev.hyphen.android.notifications

import dev.hyphen.android.transport.Envelope
import dev.hyphen.android.transport.Json
import dev.hyphen.android.transport.Ulid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

private class FakeNotificationReplier(
    private val result: NotificationReplyAttempt = NotificationReplyAttempt.Sent,
) : NotificationReplier {
    data class Reply(val sbnKey: String, val actionIndex: Int, val actionId: String?, val text: String)

    val replies = mutableListOf<Reply>()

    override fun reply(sbnKey: String, actionIndex: Int, actionId: String?, text: String): NotificationReplyAttempt {
        replies += Reply(sbnKey, actionIndex, actionId, text)
        return result
    }
}

class NotificationReplyRequestHandlerTest {

    @Test
    fun `reply request sends remote input text and returns success result`() {
        val replier = FakeNotificationReplier()
        val outbox = RecordingNotificationOutbox()
        val id = NotificationReplyRequestHandler(replier, outbox).handle(
            request("0|com.chat|7|thread-123|10101", actionIndex = 2, text = "On my way"),
        )

        assertEquals("01JZ0000000000000000000000", id)
        assertEquals(
            listOf(FakeNotificationReplier.Reply("0|com.chat|7|thread-123|10101", 2, null, "On my way")),
            replier.replies,
        )
        val sent = outbox.envelopes.single()
        assertEquals(NotificationProtocol.TYPE_REPLY_RESULT, sent.type)
        assertEquals(NotificationProtocol.CAPABILITY, sent.capability)
        assertEquals(true, sent.requiresAck)
        assertEquals(Json.Str("0|com.chat|7|thread-123|10101"), sent.payload["sbnKey"])
        assertEquals(Json.Bool(true), sent.payload["success"])
    }

    @Test
    fun `reply request forwards stable action id when present`() {
        val replier = FakeNotificationReplier()
        NotificationReplyRequestHandler(replier, RecordingNotificationOutbox()).handle(
            request(
                "0|com.chat|7|thread-123|10101",
                actionIndex = 2,
                actionId = "reply:1:reply:android.reply",
                text = "On my way",
            ),
        )

        assertEquals(
            listOf(
                FakeNotificationReplier.Reply(
                    "0|com.chat|7|thread-123|10101",
                    2,
                    "reply:1:reply:android.reply",
                    "On my way",
                ),
            ),
            replier.replies,
        )
    }

    @Test
    fun `unavailable listener returns explicit notification permission error`() {
        val outbox = RecordingNotificationOutbox()
        NotificationReplyRequestHandler(
            FakeNotificationReplier(NotificationReplyAttempt.Failed("permission/notifications-denied")),
            outbox,
        ).handle(request("0|com.chat|7|thread-123|10101", actionIndex = 0, text = "hi"))

        val sent = outbox.envelopes.single()
        assertEquals(Json.Bool(false), sent.payload["success"])
        assertEquals(Json.Str("permission/notifications-denied"), sent.payload["errorCode"])
    }

    @Test
    fun `missing notification key and unavailable action return explicit plugin errors`() {
        val missingKeyOutbox = RecordingNotificationOutbox()
        NotificationReplyRequestHandler(
            FakeNotificationReplier(NotificationReplyAttempt.Failed("plugin/notification-key-not-found")),
            missingKeyOutbox,
        ).handle(request("0|com.chat|7|thread-123|10101", actionIndex = 0, text = "hi"))

        assertEquals(Json.Str("plugin/notification-key-not-found"), missingKeyOutbox.envelopes.single().payload["errorCode"])

        val unavailableOutbox = RecordingNotificationOutbox()
        NotificationReplyRequestHandler(
            FakeNotificationReplier(NotificationReplyAttempt.Failed("plugin/reply-unavailable")),
            unavailableOutbox,
        ).handle(request("0|com.chat|7|thread-123|10101", actionIndex = 1, text = "hi"))

        assertEquals(Json.Str("plugin/reply-unavailable"), unavailableOutbox.envelopes.single().payload["errorCode"])
    }

    @Test
    fun `non reply envelopes are ignored`() {
        val outbox = RecordingNotificationOutbox()
        val result = NotificationReplyRequestHandler(FakeNotificationReplier(), outbox).handle(
            request("key", actionIndex = 0, text = "hi").copy(type = NotificationProtocol.TYPE_POSTED),
        )

        assertNull(result)
        assertEquals(0, outbox.envelopes.size)
    }

    @Test
    fun `bad reply capability action index and text are rejected`() {
        val handler = NotificationReplyRequestHandler(FakeNotificationReplier(), RecordingNotificationOutbox())
        assertThrows(IllegalArgumentException::class.java) {
            handler.handle(request("key", actionIndex = 0, text = "hi").copy(capability = "text.v1"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            handler.handle(request(" ", actionIndex = 0, text = "hi"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            handler.handle(request("key", actionIndex = -1, text = "hi"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            handler.handle(request("key", actionIndex = 0, text = " "))
        }
        assertThrows(IllegalArgumentException::class.java) {
            handler.handle(request("key", actionIndex = 0, actionId = " ", text = "hi"))
        }
    }

    private fun request(sbnKey: String, actionIndex: Int, text: String): Envelope =
        request(sbnKey = sbnKey, actionIndex = actionIndex, actionId = null, text = text)

    private fun request(sbnKey: String, actionIndex: Int, actionId: String?, text: String): Envelope {
        val payload = linkedMapOf<String, Json>(
            "sbnKey" to Json.Str(sbnKey),
            "actionIndex" to Json.Num(actionIndex.toString()),
            "text" to Json.Str(text),
        )
        actionId?.let { payload["actionId"] = Json.Str(it) }
        return Envelope(
            messageId = Ulid.generate(),
            sessionId = "s_test1",
            type = NotificationProtocol.TYPE_REPLY_REQUEST,
            capability = NotificationProtocol.CAPABILITY,
            seq = 2,
            sentAtUnixMs = 1_781_020_800_000,
            requiresAck = true,
            payload = Json.Obj(payload),
        )
    }
}
