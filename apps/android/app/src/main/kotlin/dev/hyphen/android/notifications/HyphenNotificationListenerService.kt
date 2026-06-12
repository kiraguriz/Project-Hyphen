package dev.hyphen.android.notifications

import android.service.notification.NotificationListenerService

class HyphenNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        HyphenNotificationListenerRuntime.onConnected()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        HyphenNotificationListenerRuntime.onDisconnected()
    }

    override fun onDestroy() {
        HyphenNotificationListenerRuntime.onDestroyed()
        super.onDestroy()
    }
}
