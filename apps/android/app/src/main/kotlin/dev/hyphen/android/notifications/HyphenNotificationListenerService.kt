package dev.hyphen.android.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class HyphenNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        HyphenNotificationListenerRuntime.onConnected(
            notificationCanceller = AndroidNotificationCanceller(this),
            notificationReplier = AndroidNotificationReplier(this),
            activeNotifications
                ?.map { NormalizedNotificationPayload.fromStatusBarNotification(it) }
                ?: emptyList(),
        )
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        HyphenNotificationListenerRuntime.onDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn != null) HyphenNotificationListenerRuntime.onNotificationPosted(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn != null) HyphenNotificationListenerRuntime.onNotificationRemoved(sbn)
    }

    override fun onDestroy() {
        HyphenNotificationListenerRuntime.onDestroyed()
        super.onDestroy()
    }
}
