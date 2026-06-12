package dev.hyphen.android.text

import dev.hyphen.android.transport.Envelope

data class TextLinkConfirmationRequest(
    val messageId: String,
    val message: TextLinkMessage,
)

class TextLinkReceiver {
    val pending: List<TextLinkConfirmationRequest>
        get() = pendingRequests.toList()

    private val pendingRequests = mutableListOf<TextLinkConfirmationRequest>()

    fun handle(envelope: Envelope): TextLinkConfirmationRequest? {
        if (envelope.type != TextLinkMessage.TYPE_SEND) return null
        require(envelope.capability == TextLinkMessage.CAPABILITY) {
            "unsupported text/link capability: ${envelope.capability}"
        }
        val request = TextLinkConfirmationRequest(
            messageId = envelope.messageId,
            message = TextLinkMessage.fromJson(envelope.payload),
        )
        pendingRequests += request
        return request
    }
}
