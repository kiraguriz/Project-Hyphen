package dev.hyphen.android.notifications

import android.service.notification.NotificationListenerService
import dev.hyphen.android.transport.Envelope
import dev.hyphen.android.transport.Json

fun interface NotificationCanceller {
    fun cancel(sbnKey: String): Boolean
}

class AndroidNotificationCanceller(
    private val service: NotificationListenerService,
) : NotificationCanceller {
    override fun cancel(sbnKey: String): Boolean {
        service.cancelNotification(sbnKey)
        return true
    }
}

class NotificationDismissRequestHandler(
    private val canceller: NotificationCanceller,
    private val isActiveNotification: (String) -> Boolean,
    private val outbox: NotificationOutbox,
) {
    fun handle(envelope: Envelope): String? {
        if (envelope.type != NotificationProtocol.TYPE_DISMISS_REQUEST) return null
        if (envelope.capability != NotificationProtocol.CAPABILITY) {
            throw IllegalArgumentException("unsupported notification capability")
        }
        val sbnKey = string(envelope.payload, "sbnKey")
        val payload = try {
            if (!isActiveNotification(sbnKey)) {
                resultError(sbnKey, "plugin/notification-key-not-found")
            } else if (canceller.cancel(sbnKey)) {
                // Success means Android accepted the cancel request, not that the peer observed delivery.
                Json.obj(
                    "sbnKey" to Json.Str(sbnKey),
                    "success" to Json.Bool(true),
                )
            } else {
                resultError(sbnKey, "permission/notifications-denied")
            }
        } catch (_: Exception) {
            resultError(sbnKey, "plugin/notification-key-not-found")
        }
        return outbox.send(
            type = NotificationProtocol.TYPE_DISMISS_RESULT,
            capability = NotificationProtocol.CAPABILITY,
            requiresAck = true,
            payload = payload,
        )
    }

    private fun resultError(sbnKey: String, errorCode: String): Json.Obj =
        Json.obj(
            "sbnKey" to Json.Str(sbnKey),
            "success" to Json.Bool(false),
            "errorCode" to Json.Str(errorCode),
        )

    private fun string(payload: Json.Obj, field: String): String {
        val value = (payload[field] as? Json.Str)?.value
            ?: throw IllegalArgumentException("$field must be string")
        require(value.isNotBlank()) { "$field must not be blank" }
        return value
    }
}
