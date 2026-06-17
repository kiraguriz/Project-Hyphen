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
        assertTrue(advertised.notificationPrivacyPolicyEnabled())

        val disabled = SessionHandshake.NegotiatedCapabilities(
            mapOf(
                SessionHandshake.CAPABILITY_NOTIFICATIONS to Json.obj(
                    "reply" to Json.Str("off"),
                    "dismiss" to Json.Bool(false),
                    "privacyPolicy" to Json.Bool(false),
                ),
            ),
        )
        assertFalse(disabled.notificationReplyEnabled())
        assertFalse(disabled.notificationDismissEnabled())
        assertFalse(disabled.notificationPrivacyPolicyEnabled())

        val empty = SessionHandshake.NegotiatedCapabilities.empty()
        assertFalse(empty.notificationReplyEnabled())
        assertFalse(empty.notificationDismissEnabled())
        assertFalse(empty.notificationPrivacyPolicyEnabled())
    }

    @Test
    fun `privacy policy negotiates true only when both peers advertise it`() {
        val supports = SessionHandshake.NegotiatedCapabilities.advertised()
        val legacy = SessionHandshake.NegotiatedCapabilities(
            mapOf(
                SessionHandshake.CAPABILITY_NOTIFICATIONS to Json.obj(
                    "reply" to Json.Str("beta"),
                    "dismiss" to Json.Bool(true),
                ),
            ),
        )

        assertTrue(supports.intersect(supports).notificationPrivacyPolicyEnabled())
        assertFalse(supports.intersect(legacy).notificationPrivacyPolicyEnabled())
        assertFalse(legacy.intersect(supports).notificationPrivacyPolicyEnabled())
    }
}
