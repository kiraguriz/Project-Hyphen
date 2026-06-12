package dev.hyphen.android.text

import dev.hyphen.android.transport.Json
import org.junit.Assert.assertEquals
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
    fun `payload length is capped`() {
        val tooLong = "x".repeat(TextLinkMessage.MAX_VALUE_LENGTH + 1)
        val error = runCatching { TextLinkMessage.text(tooLong) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }
}
