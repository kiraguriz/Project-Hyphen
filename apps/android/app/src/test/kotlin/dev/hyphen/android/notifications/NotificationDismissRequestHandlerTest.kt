package dev.hyphen.android.notifications

import dev.hyphen.android.transport.Envelope
import dev.hyphen.android.transport.Json
import dev.hyphen.android.transport.Ulid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

private class FakeNotificationCanceller(
    private val result: Boolean = true,
    private val throwsOnCancel: Boolean = false,
) : NotificationCanceller {
    val cancelled = mutableListOf<String>()

    override fun cancel(sbnKey: String): Boolean {
        cancelled += sbnKey
        if (throwsOnCancel) throw IllegalArgumentException("missing key")
        return result
    }
}

class NotificationDismissRequestHandlerTest {

    @Before
    fun resetRuntime() {
        HyphenNotificationListenerRuntime.resetForTests()
    }

    @Test
    fun `dismiss request cancels Android key and returns success result`() {
        val canceller = FakeNotificationCanceller()
        val outbox = RecordingNotificationOutbox()
        val id = NotificationDismissRequestHandler(canceller, activeKeys("0|com.chat|7|thread-123|10101"), outbox).handle(
            request("0|com.chat|7|thread-123|10101"),
        )

        assertEquals("01JZ0000000000000000000000", id)
        assertEquals(listOf("0|com.chat|7|thread-123|10101"), canceller.cancelled)
        val sent = outbox.envelopes.single()
        assertEquals(NotificationProtocol.TYPE_DISMISS_RESULT, sent.type)
        assertEquals(NotificationProtocol.CAPABILITY, sent.capability)
        assertEquals(true, sent.requiresAck)
        assertEquals(Json.Str("0|com.chat|7|thread-123|10101"), sent.payload["sbnKey"])
        assertEquals(Json.Bool(true), sent.payload["success"])
    }

    @Test
    fun `unavailable listener returns explicit notification permission error`() {
        val outbox = RecordingNotificationOutbox()
        NotificationDismissRequestHandler(
            FakeNotificationCanceller(result = false),
            activeKeys("0|com.chat|7|thread-123|10101"),
            outbox,
        ).handle(
            request("0|com.chat|7|thread-123|10101"),
        )

        val sent = outbox.envelopes.single()
        assertEquals(Json.Bool(false), sent.payload["success"])
        assertEquals(Json.Str("permission/notifications-denied"), sent.payload["errorCode"])
    }

    @Test
    fun `inactive key returns explicit missing-key error without calling canceller`() {
        val canceller = FakeNotificationCanceller()
        val outbox = RecordingNotificationOutbox()
        NotificationDismissRequestHandler(canceller, activeKeys(), outbox).handle(
            request("0|com.chat|7|thread-123|10101"),
        )

        val sent = outbox.envelopes.single()
        assertEquals(emptyList<String>(), canceller.cancelled)
        assertEquals(Json.Bool(false), sent.payload["success"])
        assertEquals(Json.Str("plugin/notification-key-not-found"), sent.payload["errorCode"])
    }

    @Test
    fun `disconnected stale active key does not call runtime canceller`() {
        val canceller = FakeNotificationCanceller()
        val outbox = RecordingNotificationOutbox()
        HyphenNotificationListenerRuntime.onConnected(
            listOf(payload("0|com.chat|7|thread-123|10101")),
        )
        HyphenNotificationListenerRuntime.setCanceller(canceller)
        HyphenNotificationListenerRuntime.onDisconnected()

        NotificationDismissRequestHandler(
            HyphenNotificationListenerRuntime.notificationCanceller(),
            HyphenNotificationListenerRuntime::isNotificationActive,
            outbox,
        ).handle(
            request("0|com.chat|7|thread-123|10101"),
        )

        val sent = outbox.envelopes.single()
        assertEquals(emptyList<String>(), canceller.cancelled)
        assertEquals(Json.Bool(false), sent.payload["success"])
        assertEquals(Json.Str("plugin/notification-key-not-found"), sent.payload["errorCode"])
    }

    @Test
    fun `destroyed stale active key does not call runtime canceller`() {
        val canceller = FakeNotificationCanceller()
        val outbox = RecordingNotificationOutbox()
        HyphenNotificationListenerRuntime.onConnected(
            listOf(payload("0|com.chat|7|thread-123|10101")),
        )
        HyphenNotificationListenerRuntime.setCanceller(canceller)
        HyphenNotificationListenerRuntime.onDestroyed()

        NotificationDismissRequestHandler(
            HyphenNotificationListenerRuntime.notificationCanceller(),
            HyphenNotificationListenerRuntime::isNotificationActive,
            outbox,
        ).handle(
            request("0|com.chat|7|thread-123|10101"),
        )

        val sent = outbox.envelopes.single()
        assertEquals(emptyList<String>(), canceller.cancelled)
        assertEquals(Json.Bool(false), sent.payload["success"])
        assertEquals(Json.Str("plugin/notification-key-not-found"), sent.payload["errorCode"])
    }

    @Test
    fun `cancel failure returns explicit missing-key error`() {
        val outbox = RecordingNotificationOutbox()
        NotificationDismissRequestHandler(
            FakeNotificationCanceller(throwsOnCancel = true),
            activeKeys("0|com.chat|7|thread-123|10101"),
            outbox,
        ).handle(
            request("0|com.chat|7|thread-123|10101"),
        )

        val sent = outbox.envelopes.single()
        assertEquals(Json.Bool(false), sent.payload["success"])
        assertEquals(Json.Str("plugin/notification-key-not-found"), sent.payload["errorCode"])
    }

    @Test
    fun `non dismiss envelopes are ignored`() {
        val outbox = RecordingNotificationOutbox()
        val result = NotificationDismissRequestHandler(FakeNotificationCanceller(), activeKeys("key"), outbox).handle(
            request("key").copy(type = NotificationProtocol.TYPE_POSTED),
        )

        assertNull(result)
        assertEquals(0, outbox.envelopes.size)
    }

    @Test
    fun `bad dismiss capability and blank key are rejected`() {
        val handler = NotificationDismissRequestHandler(
            FakeNotificationCanceller(),
            activeKeys("key"),
            RecordingNotificationOutbox(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            handler.handle(request("key").copy(capability = "text.v1"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            handler.handle(request(" "))
        }
    }

    private fun request(sbnKey: String): Envelope =
        Envelope(
            messageId = Ulid.generate(),
            sessionId = "s_test1",
            type = NotificationProtocol.TYPE_DISMISS_REQUEST,
            capability = NotificationProtocol.CAPABILITY,
            seq = 2,
            sentAtUnixMs = 1_781_020_800_000,
            requiresAck = true,
            payload = Json.obj("sbnKey" to Json.Str(sbnKey)),
        )

    private fun activeKeys(vararg keys: String): (String) -> Boolean {
        val active = keys.toSet()
        return { key -> key in active }
    }

    private fun payload(sbnKey: String): NormalizedNotificationPayload =
        NormalizedNotificationPayload(
            sbnKey = sbnKey,
            packageName = "com.example",
            title = "Example",
            text = "body",
            category = "msg",
            isClearable = true,
        )
}
