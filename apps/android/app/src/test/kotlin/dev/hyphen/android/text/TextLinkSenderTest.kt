package dev.hyphen.android.text

import dev.hyphen.android.transport.Json
import org.junit.Assert.assertEquals
import org.junit.Test

private class RecordingTextLinkOutbox : TextLinkOutbox {
    var type: String? = null
    var capability: String? = null
    var requiresAck: Boolean? = null
    var payload: Json.Obj? = null

    override fun send(
        type: String,
        capability: String,
        requiresAck: Boolean,
        payload: Json.Obj,
    ): String {
        this.type = type
        this.capability = capability
        this.requiresAck = requiresAck
        this.payload = payload
        return "01JZ0000000000000000000000"
    }
}

class TextLinkSenderTest {

    @Test
    fun `sender passes encoded payload to the outbox`() {
        val outbox = RecordingTextLinkOutbox()
        val id = TextLinkSender(outbox).send(TextLinkMessage.text("from Android"))

        assertEquals("01JZ0000000000000000000000", id)
        assertEquals(TextLinkMessage.TYPE_SEND, outbox.type)
        assertEquals(TextLinkMessage.CAPABILITY, outbox.capability)
        assertEquals(true, outbox.requiresAck)
        assertEquals(Json.Str("text"), outbox.payload?.get("kind"))
        assertEquals(Json.Str("from Android"), outbox.payload?.get("value"))
    }
}
