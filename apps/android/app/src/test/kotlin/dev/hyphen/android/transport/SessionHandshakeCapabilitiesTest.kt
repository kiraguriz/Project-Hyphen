package dev.hyphen.android.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionHandshakeCapabilitiesTest {

    @Test
    fun `notification option accessors reflect negotiated values`() {
        val advertised = SessionHandshake.NegotiatedCapabilities.advertised()
        assertTrue(advertised.notificationReplyEnabled())
        assertTrue(advertised.notificationDismissEnabled())

        val disabled = SessionHandshake.NegotiatedCapabilities(
            mapOf(
                SessionHandshake.CAPABILITY_NOTIFICATIONS to Json.obj(
                    "reply" to Json.Str("off"),
                    "dismiss" to Json.Bool(false),
                ),
            ),
        )
        assertFalse(disabled.notificationReplyEnabled())
        assertFalse(disabled.notificationDismissEnabled())

        val empty = SessionHandshake.NegotiatedCapabilities.empty()
        assertFalse(empty.notificationReplyEnabled())
        assertFalse(empty.notificationDismissEnabled())
    }
}
