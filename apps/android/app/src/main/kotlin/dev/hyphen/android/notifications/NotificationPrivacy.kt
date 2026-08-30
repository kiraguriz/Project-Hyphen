package dev.hyphen.android.notifications

import dev.hyphen.android.transport.Json

enum class NotificationPrivacyMode(val wire: String) {
    SHOW_FULL("full"),
    HIDE_BODY("hideBody"),
    EXISTS_ONLY("existsOnly"),
    ;

    companion object {
        fun fromWire(value: String): NotificationPrivacyMode? =
            entries.firstOrNull { it.wire == value }

        /** Return the more restrictive of two source-side disclosure modes. */
        fun mostRestrictive(
            first: NotificationPrivacyMode,
            second: NotificationPrivacyMode,
        ): NotificationPrivacyMode =
            if (first.ordinal >= second.ordinal) first else second
    }
}

/**
 * Per-app notification privacy applied at the SOURCE (before a payload crosses
 * the LAN), so hidden content never leaves the phone. The Mac pushes this policy
 * over `notification.privacy.policy` once both peers negotiate
 * `notifications.v1.privacyPolicy`. Until Android applies that policy, negotiated
 * sessions fail closed with `existsOnly` at the source; legacy peers without the
 * option keep `SHOW_FULL` and rely on the macOS receiver scrubber (defense-in-depth).
 *
 * Filtering rules mirror the macOS receiver:
 * - SHOW_FULL: unchanged.
 * - HIDE_BODY: keep package + title for routing/recognition; drop body and reply
 *   actions (a reply action label can itself leak content).
 * - disabled mirroring: suppress posted/updated/snapshot payloads; the sender
 *   first removes keys it had already mirrored.
 * - EXISTS_ONLY: keep only routing/identity fields (sbnKey, packageName,
 *   clearable, ongoing) needed for update/remove; drop title, body, category, and
 *   reply actions.
 */
data class NotificationPrivacyPolicy(
    val isMirroringEnabled: Boolean = true,
    val defaultMode: NotificationPrivacyMode = NotificationPrivacyMode.SHOW_FULL,
    val perPackageModes: Map<String, NotificationPrivacyMode> = emptyMap(),
) {
    fun modeFor(packageName: String): NotificationPrivacyMode =
        perPackageModes[packageName] ?: defaultMode

    fun apply(payload: NormalizedNotificationPayload): NormalizedNotificationPayload =
        when (modeFor(payload.packageName)) {
            NotificationPrivacyMode.SHOW_FULL -> payload
            NotificationPrivacyMode.HIDE_BODY -> payload.copy(text = null, replyActions = emptyList())
            NotificationPrivacyMode.EXISTS_ONLY -> payload.copy(
                title = null,
                text = null,
                category = null,
                replyActions = emptyList(),
            )
        }

    /**
     * Compose the Mac-selected policy with the phone owner's global privacy
     * floor. A remote peer may make a package more private, but can never make
     * it less private than the mode selected on this Android device.
     */
    fun restrictedBy(localFloor: NotificationPrivacyMode): NotificationPrivacyPolicy =
        NotificationPrivacyPolicy(
            isMirroringEnabled = isMirroringEnabled,
            defaultMode = NotificationPrivacyMode.mostRestrictive(defaultMode, localFloor),
            perPackageModes = perPackageModes.mapValues { (_, mode) ->
                NotificationPrivacyMode.mostRestrictive(mode, localFloor)
            },
        )
}

/** Minimal content-free acknowledgement for a successfully applied policy. */
object NotificationPrivacyPolicyResult {
    fun payload(regarding: String, localFloor: NotificationPrivacyMode): Json.Obj {
        require(regarding.isNotBlank()) { "regarding must not be blank" }
        return Json.obj(
            "regarding" to Json.Str(regarding),
            "applied" to Json.Bool(true),
            "localFloor" to Json.Str(localFloor.wire),
        )
    }
}

/**
 * Parses an inbound `notification.privacy.policy` payload (Mac→Android) into a
 * [NotificationPrivacyPolicy]. Strict: a missing/invalid mode or malformed
 * per-package entry throws [IllegalArgumentException], which the session turns
 * into a `protocol/invalid-envelope` error rather than silently mis-filtering.
 *
 * Wire shape: `{ "mirroringEnabled": bool, "defaultMode": "<mode>", "perPackageModes": [ { "packageName": "..", "mode": "<mode>" } ] }`.
 * `mirroringEnabled` is optional and defaults true for earlier senders.
 */
object NotificationPrivacyPolicyHandler {
    fun parse(payload: Json.Obj): NotificationPrivacyPolicy {
        val allowedFields = setOf("mirroringEnabled", "defaultMode", "perPackageModes")
        val unknownFields = payload.entries.keys - allowedFields
        require(unknownFields.isEmpty()) { "unknown policy fields: ${unknownFields.sorted()}" }
        val isMirroringEnabled = if (!payload.entries.containsKey("mirroringEnabled")) {
            true
        } else {
            (payload["mirroringEnabled"] as? Json.Bool)?.value
                ?: throw IllegalArgumentException("mirroringEnabled must be a boolean")
        }
        val defaultMode = mode(payload["defaultMode"], "defaultMode")
        val perPackage = linkedMapOf<String, NotificationPrivacyMode>()
        when (val raw = payload["perPackageModes"]) {
            null -> Unit
            is Json.Arr -> for (item in raw.items) {
                val obj = item as? Json.Obj
                    ?: throw IllegalArgumentException("perPackageModes entry must be an object")
                val unknownEntryFields = obj.entries.keys - setOf("packageName", "mode")
                require(unknownEntryFields.isEmpty()) {
                    "unknown perPackageModes fields: ${unknownEntryFields.sorted()}"
                }
                val pkg = (obj["packageName"] as? Json.Str)?.value
                    ?: throw IllegalArgumentException("perPackageModes.packageName must be a string")
                require(pkg.isNotBlank()) { "perPackageModes.packageName must not be blank" }
                perPackage[pkg] = mode(obj["mode"], "perPackageModes.mode")
            }
            else -> throw IllegalArgumentException("perPackageModes must be an array")
        }
        return NotificationPrivacyPolicy(isMirroringEnabled, defaultMode, perPackage)
    }

    private fun mode(value: Json?, field: String): NotificationPrivacyMode {
        val raw = (value as? Json.Str)?.value
            ?: throw IllegalArgumentException("$field must be a string")
        return NotificationPrivacyMode.fromWire(raw)
            ?: throw IllegalArgumentException("$field invalid: $raw")
    }
}
