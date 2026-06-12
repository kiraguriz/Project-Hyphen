package dev.hyphen.android.notifications

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

data class NotificationAccessStatus(
    val enabled: Boolean,
    val componentName: String,
)

interface NotificationAccessProbe {
    fun enabledListenerComponents(): String?
}

class NotificationAccessController(
    private val probe: NotificationAccessProbe,
    private val listenerComponentName: String,
) {
    fun status(): NotificationAccessStatus =
        NotificationAccessStatus(
            enabled = isComponentEnabled(
                enabledComponents = probe.enabledListenerComponents(),
                componentName = listenerComponentName,
            ),
            componentName = listenerComponentName,
        )

    companion object {
        fun isComponentEnabled(enabledComponents: String?, componentName: String): Boolean {
            if (enabledComponents.isNullOrBlank()) return false
            return enabledComponents
                .split(':')
                .map { it.trim() }
                .any { it == componentName }
        }

        fun forContext(context: Context): NotificationAccessController =
            NotificationAccessController(
                probe = AndroidNotificationAccessProbe(context),
                listenerComponentName = listenerComponent(context).flattenToString(),
            )

        fun settingsIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

        private fun listenerComponent(context: Context): ComponentName =
            ComponentName(context, HyphenNotificationListenerService::class.java)
    }
}

class AndroidNotificationAccessProbe(private val context: Context) : NotificationAccessProbe {
    override fun enabledListenerComponents(): String? =
        Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        )
}
