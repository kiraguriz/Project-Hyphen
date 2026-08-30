package dev.hyphen.android.service

import android.content.Context

object CompanionConnectionPreferences {
    private const val PREFS = "dev.hyphen.android.companion"
    private const val KEY_KEEP_CONNECTED = "keep_connected"
    private const val KEY_TRANSFER_RETRY_REQUIRED = "transfer_retry_required"
    private const val KEY_TRANSFER_ACTIVE = "transfer_active"

    fun keepConnected(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_KEEP_CONNECTED, false)

    fun setKeepConnected(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_KEEP_CONNECTED, enabled)
            .apply()
    }

    fun transferRetryRequired(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_TRANSFER_RETRY_REQUIRED, false)

    fun setTransferRetryRequired(context: Context, required: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_TRANSFER_RETRY_REQUIRED, required)
            .apply()
    }

    fun transferActive(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_TRANSFER_ACTIVE, false)

    fun setTransferActive(context: Context, active: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_TRANSFER_ACTIVE, active)
            .apply()
    }
}
