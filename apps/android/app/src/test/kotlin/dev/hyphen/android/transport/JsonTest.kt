package dev.hyphen.android.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTest {

    @Test
    fun `parses and re-encodes a nested document`() {
        val text = """{"a":1,"b":[true,null,"x"],"c":{"d":-2.5e3},"e":""}"""
        val value = Json.parse(text)
        assertEquals(text, value.encode())
    }

    @Test
    fun `string escapes round-trip`() {
        val original = Json.obj("s" to Json.Str("a\"b\\c\nd\te\u0001f/克"))
        val reparsed = Json.parse(original.encode()) as Json.Obj
        assertEquals("a\"b\\c\nd\te\u0001f/克", (reparsed["s"] as Json.Str).value)
    }

    @Test
    fun `unicode escapes decode`() {
        // The JSON text itself carries backslash-u00e9 / backslash-u514b.
        val parsed = Json.parse("{\"s\":\"h\\u00e9\\u514b\"}") as Json.Obj
        assertEquals("hé克", (parsed["s"] as Json.Str).value)
    }

    @Test
    fun `longs survive without double round-trip`() {
        val parsed = Json.parse("""{"ms":1781020800000}""") as Json.Obj
        assertEquals(1_781_020_800_000L, (parsed["ms"] as Json.Num).asLong())
    }

    @Test
    fun `strict rejections`() {
        val bad = listOf(
            """{"a":1,"a":2}""" to "duplicate key",
            """{"a":1} extra""" to "trailing garbage",
            """{"a":01}""" to "leading zero",
            """{"a":}""" to "missing value",
            """{"a" 1}""" to "missing colon",
            """{'a':1}""" to "single quotes",
            "{\"a\":\"\u0001\"}" to "raw control char",
            """{"a":tru}""" to "bad literal",
            """[1,2""" to "unterminated array",
            """"unterminated""" to "unterminated string",
        )
        for ((text, label) in bad) {
            assertThrows("$label must be rejected", JsonParseException::class.java) {
                Json.parse(text)
            }
        }
    }

    @Test
    fun `depth cap holds`() {
        val deep = "[".repeat(80) + "]".repeat(80)
        assertThrows(JsonParseException::class.java) { Json.parse(deep) }
        val okay = "[".repeat(40) + "]".repeat(40)
        assertTrue(Json.parse(okay) is Json.Arr)
    }
}
