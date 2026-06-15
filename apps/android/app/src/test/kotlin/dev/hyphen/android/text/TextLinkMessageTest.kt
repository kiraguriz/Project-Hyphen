package dev.hyphen.android.text

import dev.hyphen.android.transport.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextLinkMessageTest {

    @Test
    fun `text payload uses protocol wire names`() {
        val payload = TextLinkMessage.text("hello").toJson()

        assertEquals(Json.Str("text"), payload["kind"])
        assertEquals(Json.Str("hello"), payload["value"])
    }

    @Test
    fun `url payload uses protocol wire names`() {
        val payload = TextLinkMessage.url("https://example.com/a").toJson()

        assertEquals(Json.Str("url"), payload["kind"])
        assertEquals(Json.Str("https://example.com/a"), payload["value"])
    }

    @Test
    fun `blank values are rejected`() {
        val error = runCatching { TextLinkMessage.text("  ") }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `url rejects unsafe schemes`() {
        val error = runCatching { TextLinkMessage.url("javascript:alert(1)") }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `url accepts mixed-case http schemes`() {
        assertEquals(TextLinkKind.URL, TextLinkMessage.url("HTTP://example.com").kind)
        assertEquals(TextLinkKind.URL, TextLinkMessage.url("hTtPs://example.com/a").kind)
        assertTrue(TextLinkMessage.isAllowedUrlScheme("HtTpS"))
    }

    @Test
    fun `url rejects malformed http urls`() {
        val values = listOf(
            "https://",
            "http:///path",
            "https:example.com",
            "http://exa mple.com",
            "http://@",
            "http://:80",
            "http://user@:80",
        )

        values.forEach { value ->
            val error = runCatching { TextLinkMessage.url(value) }.exceptionOrNull()
            assertTrue("$value should be rejected", error is IllegalArgumentException)
        }
    }

    @Test
    fun `url classification only promotes valid http and https urls`() {
        assertEquals(TextLinkKind.URL, TextLinkMessage.classify("https://example.com/a?b=c#frag"))
        assertEquals(TextLinkKind.URL, TextLinkMessage.classify("HtTp://example.com"))
        assertEquals(TextLinkKind.TEXT, TextLinkMessage.classify("ftp://example.com/file"))
        assertEquals(TextLinkKind.TEXT, TextLinkMessage.classify("mailto:user@example.com"))
        assertEquals(TextLinkKind.TEXT, TextLinkMessage.classify("https:example.com"))
    }

    @Test
    fun `user input classification trims without changing text fallback`() {
        val link = TextLinkMessage.fromUserInput("  HTTPS://example.com/a  ")
        val text = TextLinkMessage.fromUserInput("  ftp://example.com/file  ")

        assertEquals(TextLinkKind.URL, link.kind)
        assertEquals("HTTPS://example.com/a", link.value)
        assertEquals(TextLinkKind.TEXT, text.kind)
        assertEquals("ftp://example.com/file", text.value)
        assertFalse(TextLinkMessage.isAllowedUrlScheme("file"))
    }

    @Test
    fun `open url guard requires valid url and parsed http scheme`() {
        assertTrue(TextLinkMessage.isAllowedOpenUrl("https://example.com/a", "https"))
        assertTrue(TextLinkMessage.isAllowedOpenUrl("HTTP://example.com/a", "HtTp"))
        assertFalse(TextLinkMessage.isAllowedOpenUrl("https://example.com/a", "file"))
        assertFalse(TextLinkMessage.isAllowedOpenUrl("https://example.com/a", null))
        assertFalse(TextLinkMessage.isAllowedOpenUrl("http://@", "http"))
        assertFalse(TextLinkMessage.isAllowedOpenUrl("http://:80", "http"))
        assertFalse(TextLinkMessage.isAllowedOpenUrl("http://user@:80", "http"))
    }

    @Test
    fun `payload length is capped`() {
        val tooLong = "x".repeat(TextLinkMessage.MAX_VALUE_LENGTH + 1)
        val error = runCatching { TextLinkMessage.text(tooLong) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }
}
