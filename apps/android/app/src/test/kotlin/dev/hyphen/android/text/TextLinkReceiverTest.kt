package dev.hyphen.android.text

import dev.hyphen.android.transport.Envelope
import dev.hyphen.android.transport.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextLinkReceiverTest {
    private fun envelope(
        type: String = TextLinkMessage.TYPE_SEND,
        capability: String? = TextLinkMessage.CAPABILITY,
        payload: Json.Obj,
    ): Envelope =
        Envelope(
            messageId = "01JZ0000000000000000000000",
            sessionId = "s_test1",
            type = type,
            capability = capability,
            seq = 2,
            sentAtUnixMs = 1_781_020_800_000,
            requiresAck = true,
            payload = payload,
        )

    @Test
    fun validTextEnvelopeCreatesPendingConfirmation() {
        val receiver = TextLinkReceiver()
        val request = receiver.handle(
            envelope(payload = Json.obj("kind" to Json.Str("text"), "value" to Json.Str("hello from Mac"))),
        )

        assertEquals(TextLinkKind.TEXT, request?.message?.kind)
        assertEquals("hello from Mac", request?.message?.value)
        assertEquals(listOf(request), receiver.pending)
    }

    @Test
    fun validUrlEnvelopeCreatesPendingConfirmation() {
        val receiver = TextLinkReceiver()
        val request = receiver.handle(
            envelope(payload = Json.obj("kind" to Json.Str("url"), "value" to Json.Str("https://example.com/a"))),
        )

        assertEquals(TextLinkKind.URL, request?.message?.kind)
        assertEquals("https://example.com/a", request?.message?.value)
    }

    @Test
    fun otherEnvelopeTypesAreIgnored() {
        val receiver = TextLinkReceiver()
        val request = receiver.handle(envelope(type = "heartbeat", capability = null, payload = Json.Obj.EMPTY))

        assertNull(request)
        assertTrue(receiver.pending.isEmpty())
    }

    @Test
    fun wrongCapabilityIsRejected() {
        val receiver = TextLinkReceiver()

        val error = runCatching {
            receiver.handle(
                envelope(
                    capability = "notifications.v1",
                    payload = Json.obj("kind" to Json.Str("text"), "value" to Json.Str("hi")),
                ),
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun malformedPayloadIsRejected() {
        val receiver = TextLinkReceiver()

        val error = runCatching {
            receiver.handle(envelope(payload = Json.obj("kind" to Json.Str("url"), "value" to Json.Str("ftp://x"))))
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun resolvedConfirmationIsRemovedFromPending() {
        val receiver = TextLinkReceiver()
        val request = receiver.handle(
            envelope(payload = Json.obj("kind" to Json.Str("text"), "value" to Json.Str("hello"))),
        )!!

        assertTrue(receiver.resolve(request.messageId))
        assertTrue(receiver.pending.isEmpty())
    }

    @Test
    fun pendingConfirmationsAreBounded() {
        val receiver = TextLinkReceiver(maxPending = 2)
        repeat(2) { index ->
            receiver.handle(
                envelope(
                    payload = Json.obj("kind" to Json.Str("text"), "value" to Json.Str("hello-$index")),
                ).copy(messageId = "01JZ000000000000000000000$index"),
            )
        }

        val overflow = runCatching {
            receiver.handle(
                envelope(
                    payload = Json.obj("kind" to Json.Str("text"), "value" to Json.Str("hello-2")),
                ).copy(messageId = "01JZ0000000000000000000002"),
            )
        }.exceptionOrNull()

        assertEquals(2, receiver.pending.size)
        assertEquals("hello-0", receiver.pending.first().message.value)
        assertTrue(overflow is TextConfirmationQueueFullException)
    }

    @Test
    fun `presentation queue deduplicates replay and shows one at a time`() {
        val receiver = TextLinkReceiver()
        val first = receiver.handle(
            envelope(payload = Json.obj("kind" to Json.Str("text"), "value" to Json.Str("first"))),
        )!!
        val second = first.copy(messageId = "01JZ0000000000000000000001", message = first.message.copy(value = "second"))
        val queue = TextConfirmationQueue()
        queue.enqueue(first)
        queue.enqueue(first)
        queue.enqueue(second)

        assertEquals(first, queue.activateNext())
        assertEquals(first, queue.activateNext())
        queue.finishActive(first.messageId)
        assertEquals(second, queue.activateNext())
        assertEquals(1, queue.size())
    }

    @Test
    fun `presentation queue is bounded and preserves the active confirmation`() {
        val queue = TextConfirmationQueue(maxPending = 2)
        val first = TextLinkConfirmationRequest(
            "first",
            TextLinkMessage(TextLinkKind.TEXT, "first body"),
        )
        val second = first.copy(messageId = "second", message = first.message.copy(value = "second body"))
        val third = first.copy(messageId = "third", message = first.message.copy(value = "third body"))
        queue.enqueue(first)
        queue.enqueue(second)
        assertEquals(first, queue.activateNext())

        queue.enqueue(third)

        assertEquals(2, queue.size())
        assertEquals(first, queue.activateNext())
        queue.finishActive(first.messageId)
        assertEquals(second, queue.activateNext())
    }
}
