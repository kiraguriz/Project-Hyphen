package dev.hyphen.android.text

import dev.hyphen.android.transport.Envelope

data class TextLinkConfirmationRequest(
    val messageId: String,
    val message: TextLinkMessage,
)

class TextConfirmationQueueFullException : IllegalStateException("text confirmation queue is full")

class TextLinkReceiver(
    private val maxPending: Int = 64,
) {
    val pending: List<TextLinkConfirmationRequest>
        get() = synchronized(lock) { pendingRequests.toList() }

    private val lock = Any()
    private val pendingRequests = mutableListOf<TextLinkConfirmationRequest>()
    private var activeEpoch: Any? = LEGACY_EPOCH

    /** Re-arms this process-owned queue for one transport generation. */
    fun activate(epoch: Any) = synchronized(lock) {
        if (activeEpoch != epoch) pendingRequests.clear()
        activeEpoch = epoch
    }

    /** Clears confirmations that can no longer be attributed to a live peer. */
    fun retire(epoch: Any) = synchronized(lock) {
        if (activeEpoch == epoch) {
            activeEpoch = null
            pendingRequests.clear()
        }
    }

    fun handle(envelope: Envelope): TextLinkConfirmationRequest? = handle(LEGACY_EPOCH, envelope)

    fun handle(epoch: Any, envelope: Envelope): TextLinkConfirmationRequest? {
        if (envelope.type != TextLinkMessage.TYPE_SEND) return null
        require(envelope.capability == TextLinkMessage.CAPABILITY) {
            "unsupported text/link capability: ${envelope.capability}"
        }
        val request = TextLinkConfirmationRequest(
            messageId = envelope.messageId,
            message = TextLinkMessage.fromJson(envelope.payload),
        )
        synchronized(lock) {
            if (activeEpoch != epoch) return null
            // Network retries must not create two confirmation dialogs for the
            // same protocol message.
            pendingRequests.firstOrNull { it.messageId == request.messageId }?.let { return it }
            if (pendingRequests.size >= maxPending) {
                throw TextConfirmationQueueFullException()
            }
            pendingRequests += request
        }
        return request
    }

    fun resolve(messageId: String): Boolean =
        synchronized(lock) { pendingRequests.removeAll { it.messageId == messageId } }

    private companion object {
        val LEGACY_EPOCH = Any()
    }
}

/** Activity-local presentation queue; protocol ownership remains in the receiver. */
class TextConfirmationQueue(
    private val maxPending: Int = 64,
) {
    init {
        require(maxPending > 0) { "maxPending must be positive" }
    }

    private val requests = linkedMapOf<String, TextLinkConfirmationRequest>()
    private var activeId: String? = null

    @Synchronized
    fun enqueue(request: TextLinkConfirmationRequest) {
        if (request.messageId in requests) return
        if (requests.size >= maxPending) {
            return
        }
        requests.putIfAbsent(request.messageId, request)
    }

    @Synchronized
    fun activateNext(): TextLinkConfirmationRequest? {
        activeId?.let(requests::get)?.let { return it }
        val next = requests.values.firstOrNull() ?: return null
        activeId = next.messageId
        return next
    }

    @Synchronized
    fun finishActive(messageId: String) {
        if (activeId == messageId) activeId = null
        requests.remove(messageId)
    }

    @Synchronized
    fun size(): Int = requests.size
}
