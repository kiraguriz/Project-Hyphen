package dev.hyphen.android.notifications

import dev.hyphen.android.transport.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NotificationPrivacyPolicyHandlerTest {

    @Test
    fun `parses default and per-package modes from the wire shape`() {
        val payload = Json.obj(
            "mirroringEnabled" to Json.Bool(false),
            "defaultMode" to Json.Str("existsOnly"),
            "perPackageModes" to Json.Arr(
                listOf(
                    Json.obj("packageName" to Json.Str("com.google.android.gm"), "mode" to Json.Str("full")),
                    Json.obj("packageName" to Json.Str("org.telegram.messenger"), "mode" to Json.Str("hideBody")),
                ),
            ),
        )

        val policy = NotificationPrivacyPolicyHandler.parse(payload)

        assertEquals(false, policy.isMirroringEnabled)
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
        assertEquals(true, policy.isMirroringEnabled)
    }

    @Test
    fun `explicit null mirroring enabled is rejected instead of defaulting`() {
        assertThrows(IllegalArgumentException::class.java) {
            NotificationPrivacyPolicyHandler.parse(
                Json.obj(
                    "mirroringEnabled" to Json.Null,
                    "defaultMode" to Json.Str("hideBody"),
                ),
            )
        }
    }

    @Test
    fun `explicit null per-package modes is rejected instead of treated as omitted`() {
        assertThrows(IllegalArgumentException::class.java) {
            NotificationPrivacyPolicyHandler.parse(
                Json.obj(
                    "defaultMode" to Json.Str("hideBody"),
                    "perPackageModes" to Json.Null,
                ),
            )
        }
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

    @Test
    fun `rejects unknown policy fields at the schema-backed boundary`() {
        assertThrows(IllegalArgumentException::class.java) {
            NotificationPrivacyPolicyHandler.parse(
                Json.obj(
                    "defaultMode" to Json.Str("existsOnly"),
                    "unexpected" to Json.Str("value"),
                ),
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            NotificationPrivacyPolicyHandler.parse(
                Json.obj(
                    "defaultMode" to Json.Str("existsOnly"),
                    "perPackageModes" to Json.Arr(
                        listOf(
                            Json.obj(
                                "packageName" to Json.Str("com.example"),
                                "mode" to Json.Str("full"),
                                "unexpected" to Json.Bool(true),
                            ),
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun `local privacy floor can only make a remote policy more restrictive`() {
        val remote = NotificationPrivacyPolicy(
            defaultMode = NotificationPrivacyMode.SHOW_FULL,
            perPackageModes = mapOf(
                "com.full" to NotificationPrivacyMode.SHOW_FULL,
                "com.hidden" to NotificationPrivacyMode.HIDE_BODY,
                "com.exists" to NotificationPrivacyMode.EXISTS_ONLY,
            ),
        )

        val effective = remote.restrictedBy(NotificationPrivacyMode.HIDE_BODY)

        assertEquals(NotificationPrivacyMode.HIDE_BODY, effective.defaultMode)
        assertEquals(NotificationPrivacyMode.HIDE_BODY, effective.modeFor("com.full"))
        assertEquals(NotificationPrivacyMode.HIDE_BODY, effective.modeFor("com.hidden"))
        assertEquals(NotificationPrivacyMode.EXISTS_ONLY, effective.modeFor("com.exists"))
        assertEquals(NotificationPrivacyMode.HIDE_BODY, effective.modeFor("com.unknown"))
    }

    @Test
    fun `privacy result payload names the request and phone floor without content`() {
        val payload = NotificationPrivacyPolicyResult.payload(
            regarding = "01JZ0000000000000000000000",
            localFloor = NotificationPrivacyMode.EXISTS_ONLY,
        )

        assertEquals(Json.Str("01JZ0000000000000000000000"), payload["regarding"])
        assertEquals(Json.Bool(true), payload["applied"])
        assertEquals(Json.Str("existsOnly"), payload["localFloor"])
        assertEquals(setOf("regarding", "applied", "localFloor"), payload.entries.keys)
    }
}
