package dev.hyphen.android.notifications

import android.app.Notification
import android.service.notification.StatusBarNotification
import dev.hyphen.android.transport.Json

object NotificationProtocol {
    const val CAPABILITY = "notifications.v1"
    const val TYPE_POSTED = "notification.posted"
    const val TYPE_UPDATED = "notification.updated"
    const val TYPE_REMOVED = "notification.removed"
    const val TYPE_DISMISS_REQUEST = "notification.dismiss.request"
    const val TYPE_DISMISS_RESULT = "notification.dismiss.result"
}

/**
 * Raw fields extracted from StatusBarNotification. postTime stays at this
 * boundary so tests can prove it never enters Hyphen notification identity.
 */
data class NotificationPayloadSource(
    val sbnKey: String,
    val packageName: String,
    val title: String? = null,
    val text: String? = null,
    val category: String? = null,
    val isClearable: Boolean = false,
    val isOngoing: Boolean = false,
    val postTimeUnixMs: Long? = null,
)

data class NormalizedNotificationPayload(
    val sbnKey: String,
    val packageName: String,
    val title: String? = null,
    val text: String? = null,
    val category: String? = null,
    val isClearable: Boolean = false,
    val isOngoing: Boolean = false,
) {
    init {
        require(sbnKey.isNotBlank()) { "sbnKey must not be blank" }
        require(packageName.isNotBlank()) { "packageName must not be blank" }
    }

    fun toJson(): Json.Obj {
        val entries = linkedMapOf<String, Json>(
            "sbnKey" to Json.Str(sbnKey),
            "packageName" to Json.Str(packageName),
            "clearable" to Json.Bool(isClearable),
            "ongoing" to Json.Bool(isOngoing),
        )
        title?.let { entries["title"] = Json.Str(it) }
        text?.let { entries["text"] = Json.Str(it) }
        category?.let { entries["category"] = Json.Str(it) }
        return Json.Obj(entries)
    }

    companion object {
        fun from(source: NotificationPayloadSource): NormalizedNotificationPayload =
            NormalizedNotificationPayload(
                sbnKey = source.sbnKey,
                packageName = source.packageName,
                title = normalizeText(source.title),
                text = normalizeText(source.text),
                category = normalizeText(source.category),
                isClearable = source.isClearable,
                isOngoing = source.isOngoing,
            )

        fun fromStatusBarNotification(sbn: StatusBarNotification): NormalizedNotificationPayload {
            val notification = sbn.notification
            val extras = notification.extras
            return from(
                NotificationPayloadSource(
                    sbnKey = sbn.key,
                    packageName = sbn.packageName,
                    title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
                    text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
                    category = notification.category,
                    isClearable = sbn.isClearable,
                    isOngoing = sbn.isOngoing,
                    postTimeUnixMs = sbn.postTime,
                ),
            )
        }

        private fun normalizeText(value: String?): String? =
            value?.trim()?.takeIf { it.isNotEmpty() }
    }
}
