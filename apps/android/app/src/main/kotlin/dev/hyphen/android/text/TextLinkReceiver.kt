package dev.hyphen.android.text

import dev.hyphen.android.transport.Envelope

data class TextLinkConfirmationRequest(
    val messageId: String,
    val message: TextLinkMessage,
)

class TextLinkReceiver(
    private val maxPending: Int = 64,
) {
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
        if (pendingRequests.size >= maxPending) {
            pendingRequests.removeAt(0)
        }
        pendingRequests += request
        return request
    }

    fun resolve(messageId: String): Boolean =
        pendingRequests.removeAll { it.messageId == messageId }
}
