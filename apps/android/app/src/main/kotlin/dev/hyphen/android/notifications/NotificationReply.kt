package dev.hyphen.android.notifications

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import dev.hyphen.android.transport.Envelope
import dev.hyphen.android.transport.Json

sealed class NotificationReplyAttempt {
    data object Sent : NotificationReplyAttempt()
    data class Failed(val errorCode: String) : NotificationReplyAttempt()
}

fun interface NotificationReplier {
    fun reply(sbnKey: String, actionIndex: Int, text: String): NotificationReplyAttempt
}

class AndroidNotificationReplier(
    private val service: NotificationListenerService,
) : NotificationReplier {
    override fun reply(sbnKey: String, actionIndex: Int, text: String): NotificationReplyAttempt {
        val sbn = service.activeNotifications?.firstOrNull { it.key == sbnKey }
            ?: return NotificationReplyAttempt.Failed("plugin/notification-key-not-found")
        val action = sbn.notification.actions?.getOrNull(actionIndex)
            ?: return NotificationReplyAttempt.Failed("plugin/reply-unavailable")
        val actionIntent = action.actionIntent
            ?: return NotificationReplyAttempt.Failed("plugin/reply-unavailable")
        val remoteInputs = action.remoteInputs?.takeIf { it.isNotEmpty() }
            ?: return NotificationReplyAttempt.Failed("plugin/reply-unavailable")

        val intent = Intent()
        val results = Bundle()
        remoteInputs.forEach { remoteInput ->
            results.putCharSequence(remoteInput.resultKey, text)
        }
        RemoteInput.addResultsToIntent(remoteInputs, intent, results)
        return try {
            actionIntent.send(service, 0, intent)
            NotificationReplyAttempt.Sent
        } catch (_: PendingIntent.CanceledException) {
            NotificationReplyAttempt.Failed("plugin/reply-unavailable")
        }
    }
}

class NotificationReplyRequestHandler(
    private val replier: NotificationReplier,
    private val outbox: NotificationOutbox,
) {
    fun handle(envelope: Envelope): String? {
        if (envelope.type != NotificationProtocol.TYPE_REPLY_REQUEST) return null
        if (envelope.capability != NotificationProtocol.CAPABILITY) {
            throw IllegalArgumentException("unsupported notification capability")
        }
        val sbnKey = string(envelope.payload, "sbnKey")
        val actionIndex = actionIndex(envelope.payload)
        val text = string(envelope.payload, "text")

        val payload = when (val attempt = replier.reply(sbnKey, actionIndex, text)) {
            NotificationReplyAttempt.Sent -> Json.obj(
                "sbnKey" to Json.Str(sbnKey),
                "success" to Json.Bool(true),
            )
            is NotificationReplyAttempt.Failed -> resultError(sbnKey, attempt.errorCode)
        }
        return outbox.send(
            type = NotificationProtocol.TYPE_REPLY_RESULT,
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

    private fun actionIndex(payload: Json.Obj): Int {
        val value = (payload["actionIndex"] as? Json.Num)?.asLong()
            ?: throw IllegalArgumentException("actionIndex must be number")
        require(value in 0..Int.MAX_VALUE) { "actionIndex must be non-negative integer" }
        return value.toInt()
    }

    private fun string(payload: Json.Obj, field: String): String {
        val value = (payload[field] as? Json.Str)?.value
            ?: throw IllegalArgumentException("$field must be string")
        require(value.isNotBlank()) { "$field must not be blank" }
        return value
    }
}
