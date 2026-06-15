package dev.hyphen.android.text

import dev.hyphen.android.transport.Json
import java.net.URI

enum class TextLinkKind(val wireName: String) {
    TEXT("text"),
    URL("url"),
}

data class TextLinkMessage(
    val kind: TextLinkKind,
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "text/link value must not be blank" }
        require(value.length <= MAX_VALUE_LENGTH) { "text/link value too long" }
        if (kind == TextLinkKind.URL) {
            require(isAllowedUrl(value)) { "url must use http or https" }
        }
    }

    fun toJson(): Json.Obj =
        Json.obj(
            "kind" to Json.Str(kind.wireName),
            "value" to Json.Str(value),
        )

    companion object {
        const val CAPABILITY = "text.v1"
        const val TYPE_SEND = "text.send"
        const val MAX_VALUE_LENGTH = 8 * 1024

        fun text(value: String): TextLinkMessage = TextLinkMessage(TextLinkKind.TEXT, value)

        fun url(value: String): TextLinkMessage = TextLinkMessage(TextLinkKind.URL, value)

        fun fromUserInput(value: String): TextLinkMessage {
            val trimmed = value.trim()
            return when (classify(trimmed)) {
                TextLinkKind.URL -> url(trimmed)
                TextLinkKind.TEXT -> text(trimmed)
            }
        }

        fun classify(value: String): TextLinkKind =
            if (isAllowedUrl(value)) TextLinkKind.URL else TextLinkKind.TEXT

        fun isAllowedUrlScheme(scheme: String?): Boolean =
            scheme?.lowercase() == "http" || scheme?.lowercase() == "https"

        fun isAllowedUrl(value: String): Boolean {
            val uri = runCatching { URI(value) }.getOrNull() ?: return false
            return isAllowedUrlScheme(uri.scheme) &&
                !uri.isOpaque &&
                !uri.rawAuthority.isNullOrBlank() &&
                !uri.host.isNullOrBlank()
        }

        fun isAllowedOpenUrl(value: String, parsedScheme: String?): Boolean =
            isAllowedUrl(value) && isAllowedUrlScheme(parsedScheme)

        fun fromJson(payload: Json.Obj): TextLinkMessage {
            val rawKind = (payload["kind"] as? Json.Str)?.value
                ?: throw IllegalArgumentException("kind must be text or url")
            val value = (payload["value"] as? Json.Str)?.value
                ?: throw IllegalArgumentException("value must be string")
            val kind = TextLinkKind.entries.firstOrNull { it.wireName == rawKind }
                ?: throw IllegalArgumentException("kind must be text or url")
            return TextLinkMessage(kind, value)
        }
    }
}
