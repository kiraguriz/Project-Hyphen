package dev.hyphen.android.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionHandshakeCapabilitiesTest {

    private fun negotiate(kindA: String, dirA: String, kindB: String, dirB: String): String =
        SessionHandshake.NegotiatedCapabilities.negotiateTextDirection(kindA, dirA, kindB, dirB)

    @Test
    fun `text direction negotiates from the android frame and never fabricates bidirectional`() {
        // Both bidirectional -> bidirectional (the v0 built-in case).
        assertEquals("bidirectional", negotiate("android", "bidirectional", "macos", "bidirectional"))
        // A one-directional peer is NOT silently upgraded to bidirectional.
        assertEquals("send-only", negotiate("android", "send-only", "macos", "bidirectional"))
        assertEquals("receive-only", negotiate("android", "receive-only", "macos", "bidirectional"))
        // Frame is the android endpoint: a mac that only sends => android receives only.
        assertEquals("receive-only", negotiate("android", "bidirectional", "macos", "send-only"))
        // Frame independence: swapping argument order yields the same android-frame value.
        assertEquals("send-only", negotiate("macos", "bidirectional", "android", "send-only"))
        // No compatible flow -> none.
        assertEquals("none", negotiate("android", "send-only", "macos", "send-only"))
        assertEquals("none", negotiate("android", "receive-only", "macos", "receive-only"))
    }

    @Test
    fun `resolveTextDirection finalizes the android frame and drops text when incompatible`() {
        val advertised = SessionHandshake.NegotiatedCapabilities.advertised()
        val sendOnlyPeer = SessionHandshake.NegotiatedCapabilities(
            mapOf(SessionHandshake.CAPABILITY_TEXT to Json.obj("direction" to Json.Str("send-only"))),
        )
        // Responder (mac) intersect carry-through would leave "bidirectional";
        // resolve replaces it with the real android-frame value.
        val negotiated = advertised.intersect(sendOnlyPeer).resolveTextDirection(
            localKind = "macos",
            localDir = "bidirectional",
            peerKind = "android",
            peerDir = "send-only",
        )
        assertTrue(negotiated.contains(SessionHandshake.CAPABILITY_TEXT))
        assertEquals("send-only", negotiated.textDirection())

        // Two incompatible single directions drop the text capability entirely.
        val dropped = advertised.intersect(sendOnlyPeer).resolveTextDirection(
            localKind = "macos",
            localDir = "receive-only",
            peerKind = "android",
            peerDir = "receive-only",
        )
        assertFalse(dropped.contains(SessionHandshake.CAPABILITY_TEXT))
        assertEquals(null, dropped.textDirection())
    }

    @Test
    fun `intersect carries a one-directional left operand through instead of fabricating bidirectional`() {
        val resolvedServer = SessionHandshake.NegotiatedCapabilities(
            mapOf(SessionHandshake.CAPABILITY_TEXT to Json.obj("direction" to Json.Str("send-only"))),
        )
        // Mirrors the client path: serverNegotiated.intersect(clientAdvertised).
        val clientView = resolvedServer.intersect(SessionHandshake.NegotiatedCapabilities.advertised())
        assertEquals("send-only", clientView.textDirection())
    }

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
