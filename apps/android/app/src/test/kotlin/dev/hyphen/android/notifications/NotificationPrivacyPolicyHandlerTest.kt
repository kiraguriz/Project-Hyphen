package dev.hyphen.android.notifications

import dev.hyphen.android.transport.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NotificationPrivacyPolicyHandlerTest {

    @Test
    fun `parses default and per-package modes from the wire shape`() {
        val payload = Json.obj(
            "defaultMode" to Json.Str("existsOnly"),
            "perPackageModes" to Json.Arr(
                listOf(
                    Json.obj("packageName" to Json.Str("com.google.android.gm"), "mode" to Json.Str("full")),
                    Json.obj("packageName" to Json.Str("org.telegram.messenger"), "mode" to Json.Str("hideBody")),
                ),
            ),
        )

        val policy = NotificationPrivacyPolicyHandler.parse(payload)

        assertEquals(NotificationPrivacyMode.EXISTS_ONLY, policy.defaultMode)
        assertEquals(NotificationPrivacyMode.SHOW_FULL, policy.modeFor("com.google.android.gm"))
        assertEquals(NotificationPrivacyMode.HIDE_BODY, policy.modeFor("org.telegram.messenger"))
        // An unconfigured package falls back to the default.
        assertEquals(NotificationPrivacyMode.EXISTS_ONLY, policy.modeFor("com.unknown"))
    }

    @Test
    fun `omitted per-package modes parse to a default-only policy`() {
        val policy = NotificationPrivacyPolicyHandler.parse(Json.obj("defaultMode" to Json.Str("hideBody")))

        assertEquals(NotificationPrivacyMode.HIDE_BODY, policy.defaultMode)
        assertEquals(emptyMap<String, NotificationPrivacyMode>(), policy.perPackageModes)
    }

    @Test
    fun `rejects an invalid default mode`() {
        assertThrows(IllegalArgumentException::class.java) {
            NotificationPrivacyPolicyHandler.parse(Json.obj("defaultMode" to Json.Str("invisible")))
        }
    }

    @Test
    fun `rejects a missing default mode`() {
        assertThrows(IllegalArgumentException::class.java) {
            NotificationPrivacyPolicyHandler.parse(Json.obj())
        }
    }

    @Test
    fun `rejects a per-package entry with an invalid mode`() {
        val payload = Json.obj(
            "defaultMode" to Json.Str("full"),
            "perPackageModes" to Json.Arr(
                listOf(Json.obj("packageName" to Json.Str("com.x"), "mode" to Json.Str("nope"))),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            NotificationPrivacyPolicyHandler.parse(payload)
        }
    }

    @Test
    fun `rejects a per-package entry with a blank package name`() {
        val payload = Json.obj(
            "defaultMode" to Json.Str("full"),
            "perPackageModes" to Json.Arr(
                listOf(Json.obj("packageName" to Json.Str("  "), "mode" to Json.Str("full"))),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            NotificationPrivacyPolicyHandler.parse(payload)
        }
    }

    @Test
    fun `rejects per-package modes that are not an array`() {
        val payload = Json.obj(
            "defaultMode" to Json.Str("full"),
            "perPackageModes" to Json.Str("oops"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            NotificationPrivacyPolicyHandler.parse(payload)
        }
    }
}
