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
    private var privacyPolicy = NotificationPrivacyPolicy(defaultMode = initialPrivacyMode)

    /** Convenience for the default-only case (e.g. the local debug toggle). */
    fun setPrivacyMode(mode: NotificationPrivacyMode) {
        privacyPolicy = NotificationPrivacyPolicy(defaultMode = mode)
    }

    /**
     * Apply the full per-app policy pushed by the Mac and return keys whose
     * already-mirrored rows must be removed. The lifecycle owner queues those
     * controls on the same bounded dispatcher as every other notification event.
     */
    @Synchronized
    fun setPrivacyPolicy(policy: NotificationPrivacyPolicy): Set<String> {
        privacyPolicy = policy
        if (policy.isMirroringEnabled) return emptySet()
        return activeKeys.toSet().also { activeKeys.clear() }
    }

    @Synchronized
    fun sendPostedOrUpdated(payload: NormalizedNotificationPayload): String? {
        if (!privacyPolicy.isMirroringEnabled) return null
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
    fun sendSnapshot(payload: NormalizedNotificationPayload): String? {
        if (!privacyPolicy.isMirroringEnabled) return null
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
        val filteredPayload = privacyPolicy.apply(payload)
        return if (allowReplyActions) filteredPayload else filteredPayload.copy(replyActions = emptyList())
    }
}
