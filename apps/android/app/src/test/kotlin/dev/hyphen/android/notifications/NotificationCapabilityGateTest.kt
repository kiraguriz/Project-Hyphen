package dev.hyphen.android.notifications

import dev.hyphen.android.transport.Json
import dev.hyphen.android.transport.SessionHandshake
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationCapabilityGateTest {

    @Test
    fun `empty notification capability disables outbox and inbound actions`() {
        val capabilities = SessionHandshake.NegotiatedCapabilities.empty()

        assertFalse(NotificationCapabilityGate.shouldBindOutbox(capabilities))
        assertFalse(NotificationCapabilityGate.allowReplyActions(capabilities))
        assertFalse(NotificationCapabilityGate.allowsInboundRequest(NotificationProtocol.TYPE_DISMISS_REQUEST, capabilities))
        assertFalse(NotificationCapabilityGate.allowsInboundRequest(NotificationProtocol.TYPE_REPLY_REQUEST, capabilities))
        assertFalse(NotificationCapabilityGate.allowsInboundRequest(NotificationProtocol.TYPE_PRIVACY_POLICY, capabilities))
    }

    @Test
    fun `disabled notification options reject only their action requests`() {
        val capabilities = SessionHandshake.NegotiatedCapabilities(
            mapOf(
                NotificationProtocol.CAPABILITY to Json.obj(
                    "reply" to Json.Str("off"),
                    "dismiss" to Json.Bool(false),
                    "privacyPolicy" to Json.Bool(false),
                ),
            ),
        )

        assertTrue(NotificationCapabilityGate.shouldBindOutbox(capabilities))
        assertFalse(NotificationCapabilityGate.allowReplyActions(capabilities))
        assertFalse(NotificationCapabilityGate.allowsInboundRequest(NotificationProtocol.TYPE_DISMISS_REQUEST, capabilities))
        assertFalse(NotificationCapabilityGate.allowsInboundRequest(NotificationProtocol.TYPE_REPLY_REQUEST, capabilities))
        assertFalse(NotificationCapabilityGate.allowsInboundRequest(NotificationProtocol.TYPE_PRIVACY_POLICY, capabilities))
        assertTrue(NotificationCapabilityGate.allowsInboundRequest(NotificationProtocol.TYPE_POSTED, capabilities))
    }

    @Test
    fun `advertised notification options allow inbound actions`() {
        val capabilities = SessionHandshake.NegotiatedCapabilities.advertised()

        assertTrue(NotificationCapabilityGate.shouldBindOutbox(capabilities))
        assertTrue(NotificationCapabilityGate.allowReplyActions(capabilities))
        assertTrue(NotificationCapabilityGate.allowsInboundRequest(NotificationProtocol.TYPE_DISMISS_REQUEST, capabilities))
        assertTrue(NotificationCapabilityGate.allowsInboundRequest(NotificationProtocol.TYPE_REPLY_REQUEST, capabilities))
        assertTrue(NotificationCapabilityGate.allowsInboundRequest(NotificationProtocol.TYPE_PRIVACY_POLICY, capabilities))
    }
}
