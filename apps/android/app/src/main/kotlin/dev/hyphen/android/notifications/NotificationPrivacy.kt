package dev.hyphen.android.notifications

enum class NotificationPrivacyMode {
    SHOW_FULL,
    HIDE_BODY,
}

class NotificationPrivacyFilter(private val mode: NotificationPrivacyMode = NotificationPrivacyMode.SHOW_FULL) {
    fun apply(payload: NormalizedNotificationPayload): NormalizedNotificationPayload =
        when (mode) {
            NotificationPrivacyMode.SHOW_FULL -> payload
            NotificationPrivacyMode.HIDE_BODY -> payload.copy(text = null)
        }
}
