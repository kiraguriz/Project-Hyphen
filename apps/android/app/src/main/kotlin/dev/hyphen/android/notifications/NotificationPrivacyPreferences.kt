package dev.hyphen.android.notifications

import android.content.Context

/** Persists only the phone owner's global privacy floor, never notification data. */
object NotificationPrivacyPreferences {
    private const val PREFS = "dev.hyphen.android.notification-privacy"
    private const val KEY_LOCAL_FLOOR = "local_floor"

    fun localFloor(context: Context): NotificationPrivacyMode {
        val wire = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LOCAL_FLOOR, null)
        return wire?.let(NotificationPrivacyMode::fromWire) ?: NotificationPrivacyMode.SHOW_FULL
    }

    fun setLocalFloor(context: Context, mode: NotificationPrivacyMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LOCAL_FLOOR, mode.wire)
            .apply()
    }
}
