package dev.hyphen.android.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeNotificationAccessProbe(
    private val enabledComponents: String?,
) : NotificationAccessProbe {
    override fun enabledListenerComponents(): String? = enabledComponents
}

class NotificationAccessControllerTest {

    @Test
    fun `blank settings mean notification listener is disabled`() {
        val controller = NotificationAccessController(
            probe = FakeNotificationAccessProbe(""),
            listenerComponentName = "dev.hyphen.android/.notifications.HyphenNotificationListenerService",
        )

        assertFalse(controller.status().enabled)
    }

    @Test
    fun `colon separated listener settings match exact component`() {
        val component = "dev.hyphen.android/.notifications.HyphenNotificationListenerService"
        val controller = NotificationAccessController(
            probe = FakeNotificationAccessProbe(
                "com.example/.OtherListener:$component:com.example/.Second",
            ),
            listenerComponentName = component,
        )

        assertTrue(controller.status().enabled)
        assertEquals(component, controller.status().componentName)
    }

    @Test
    fun `component substrings do not count as enabled`() {
        val component = "dev.hyphen.android/.notifications.HyphenNotificationListenerService"
        val controller = NotificationAccessController(
            probe = FakeNotificationAccessProbe("${component}Backup"),
            listenerComponentName = component,
        )

        assertFalse(controller.status().enabled)
    }

    @Test
    fun `parser trims settings entries before matching`() {
        assertTrue(
            NotificationAccessController.isComponentEnabled(
                enabledComponents = " other/.Listener : dev.hyphen/.Listener ",
                componentName = "dev.hyphen/.Listener",
            ),
        )
    }
}
