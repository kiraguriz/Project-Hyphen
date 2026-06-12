package dev.hyphen.android.text

import dev.hyphen.android.transport.Json
import dev.hyphen.android.transport.ProtocolSession

interface TextLinkOutbox {
    fun send(
        type: String,
        capability: String,
        requiresAck: Boolean,
        payload: Json.Obj,
    ): String
}

class ProtocolSessionTextLinkOutbox(private val session: ProtocolSession) : TextLinkOutbox {
    override fun send(
        type: String,
        capability: String,
        requiresAck: Boolean,
        payload: Json.Obj,
    ): String =
        session.send(
            type = type,
            payload = payload,
            requiresAck = requiresAck,
            capability = capability,
        )
}

class TextLinkSender(private val outbox: TextLinkOutbox) {
    fun send(message: TextLinkMessage): String =
        outbox.send(
            type = TextLinkMessage.TYPE_SEND,
            capability = TextLinkMessage.CAPABILITY,
            requiresAck = true,
            payload = message.toJson(),
        )
}
