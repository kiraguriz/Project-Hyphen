package dev.hyphen.android.notifications

import dev.hyphen.android.transport.SessionHandshake

object NotificationCapabilityGate {
    fun shouldBindOutbox(capabilities: SessionHandshake.NegotiatedCapabilities): Boolean =
        capabilities.contains(NotificationProtocol.CAPABILITY)

    fun allowReplyActions(capabilities: SessionHandshake.NegotiatedCapabilities): Boolean =
        capabilities.notificationReplyEnabled()

    fun allowsInboundRequest(
        type: String,
        capabilities: SessionHandshake.NegotiatedCapabilities?,
    ): Boolean =
        when (type) {
            NotificationProtocol.TYPE_DISMISS_REQUEST -> capabilities?.notificationDismissEnabled() == true
            NotificationProtocol.TYPE_REPLY_REQUEST -> capabilities?.notificationReplyEnabled() == true
            NotificationProtocol.TYPE_PRIVACY_POLICY -> capabilities?.notificationPrivacyPolicyEnabled() == true
            else -> true
        }
}
