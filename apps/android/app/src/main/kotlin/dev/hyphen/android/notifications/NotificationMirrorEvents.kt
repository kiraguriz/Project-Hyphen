package dev.hyphen.android.notifications

import dev.hyphen.android.transport.Json
import dev.hyphen.android.transport.ProtocolSession

interface NotificationOutbox {
    fun send(
        type: String,
        capability: String,
        requiresAck: Boolean,
        payload: Json.Obj,
    ): String
}

class ProtocolSessionNotificationOutbox(private val session: ProtocolSession) : NotificationOutbox {
    override fun send(
        type: String,
        capability: String,
        requiresAck: Boolean,
        payload: Json.Obj,
    ): String =
        session.send(
            type = type,
            capability = capability,
            requiresAck = requiresAck,
            payload = payload,
        )
}

class NotificationMirrorEventSender(
    private val outbox: NotificationOutbox,
    initialPrivacyMode: NotificationPrivacyMode = NotificationPrivacyMode.SHOW_FULL,
    initialActiveKeys: Set<String> = emptySet(),
    private val allowReplyActions: Boolean = true,
) {
    private val activeKeys = initialActiveKeys.toCollection(linkedSetOf())
    @Volatile
    private var privacyFilter = NotificationPrivacyFilter(initialPrivacyMode)

    fun setPrivacyMode(mode: NotificationPrivacyMode) {
        privacyFilter = NotificationPrivacyFilter(mode)
    }

    @Synchronized
    fun sendPostedOrUpdated(payload: NormalizedNotificationPayload): String {
        val filteredPayload = outboundPayload(payload)
        val type = if (activeKeys.add(payload.sbnKey)) {
            NotificationProtocol.TYPE_POSTED
        } else {
            NotificationProtocol.TYPE_UPDATED
        }
        return outbox.send(
            type = type,
            capability = NotificationProtocol.CAPABILITY,
            requiresAck = true,
            payload = filteredPayload.toJson(),
        )
    }

    @Synchronized
    fun sendSnapshot(payload: NormalizedNotificationPayload): String {
        activeKeys += payload.sbnKey
        return outbox.send(
            type = NotificationProtocol.TYPE_POSTED,
            capability = NotificationProtocol.CAPABILITY,
            requiresAck = true,
            payload = outboundPayload(payload).toJson(),
        )
    }

    @Synchronized
    fun sendRemoved(sbnKey: String): String {
        require(sbnKey.isNotBlank()) { "sbnKey must not be blank" }
        activeKeys.remove(sbnKey)
        return outbox.send(
            type = NotificationProtocol.TYPE_REMOVED,
            capability = NotificationProtocol.CAPABILITY,
            requiresAck = true,
            payload = Json.obj("sbnKey" to Json.Str(sbnKey)),
        )
    }

    @Synchronized
    fun activeKeys(): Set<String> = activeKeys.toSet()

    private fun outboundPayload(payload: NormalizedNotificationPayload): NormalizedNotificationPayload {
        val filteredPayload = privacyFilter.apply(payload)
        return if (allowReplyActions) filteredPayload else filteredPayload.copy(replyActions = emptyList())
    }
}
