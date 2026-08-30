package dev.hyphen.android.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.drawable.Icon
import dev.hyphen.android.MainActivity
import dev.hyphen.android.R

internal object CompanionForegroundNotifications {
    const val CHANNEL_ID = "hyphen_companion"
    const val CONNECTION_NOTIFICATION_ID = 1401
    const val TRANSFER_NOTIFICATION_ID = 1402

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.fgs_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.fgs_channel_description)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    fun notificationPermissionMissing(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    fun canDisplay(context: Context): Boolean {
        ensureChannel(context)
        if (notificationPermissionMissing(context)) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return false
        return manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun connection(
        context: Context,
        state: CompanionNotificationState,
    ): Notification {
        val text = when (state) {
            CompanionNotificationState.CONNECTED -> R.string.fgs_connection_connected
            CompanionNotificationState.DEGRADED -> R.string.fgs_connection_degraded
            CompanionNotificationState.PAUSED -> R.string.fgs_connection_paused
            CompanionNotificationState.RETRY_REQUIRED -> R.string.fgs_connection_retry_required
            CompanionNotificationState.CONNECTING,
            CompanionNotificationState.TRANSFERRING,
            -> R.string.fgs_connection_connecting
        }
        return baseBuilder(context)
            .setContentTitle(context.getString(R.string.fgs_connection_title))
            .setContentText(context.getString(text))
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_hyphen_status),
                    context.getString(R.string.fgs_disconnect_action),
                    disconnectIntent(context),
                ).build(),
            )
            .build()
    }

    fun transfer(context: Context): Notification =
        baseBuilder(context)
            .setContentTitle(context.getString(R.string.fgs_transfer_title))
            .setContentText(context.getString(R.string.fgs_transfer_active))
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()

    fun transferRetryRequired(context: Context): Notification =
        baseBuilder(context)
            .setContentTitle(context.getString(R.string.fgs_transfer_stopped_title))
            .setContentText(context.getString(R.string.fgs_transfer_stopped_body))
            .setOngoing(false)
            .setAutoCancel(true)
            .build()

    private fun baseBuilder(context: Context): Notification.Builder =
        Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_hyphen_status)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent(context))

    private fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            1400,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun disconnectIntent(context: Context): PendingIntent =
        PendingIntent.getService(
            context,
            1401,
            Intent(context, HyphenConnectionService::class.java)
                .setAction(HyphenConnectionService.ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
