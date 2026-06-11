package dev.hyphen.android.transport

/**
 * Minimal strict JSON (RFC 8259) value model, parser, and encoder
 * (HYP-M2-012). Hand-rolled because org.json is a stub in JVM unit tests
 * and a JSON library would be a new dependency; these ~170 auditable
 * lines cover exactly what protocol envelopes need. Strictness mirrors
 * the protocol's safety posture: duplicate keys rejected, depth capped,
 * trailing garbage rejected, raw control characters in strings rejected.
 */
sealed class Json {

    object Null : Json()
    data class Bool(val value: Boolean) : Json()

    /** Numbers keep their raw text so longs never round-trip through Double. */
    data class Num(val raw: String) : Json() {
        fun asLong(): Long? = raw.toLongOrNull()
    }

    data class Str(val value: String) : Json()
    data class Arr(val items: List<Json>) : Json()

    data class Obj(val entries: Map<String, Json>) : Json() {
        operator fun get(key: String): Json? = entries[key]
        companion object {
            val EMPTY = Obj(emptyMap())
        }
    }

    fun encode(): String = StringBuilder().also { encodeTo(it) }.toString()

    private fun encodeTo(sb: StringBuilder) {
        when (this) {
            is Null -> sb.append("null")
            is Bool -> sb.append(if (value) "true" else "false")
            is Num -> sb.append(raw)
            is Str -> encodeString(value, sb)
            is Arr -> {
                sb.append('[')
                items.forEachIndexed { i, item ->
                    if (i > 0) sb.append(',')
                    item.encodeTo(sb)
                }
                sb.append(']')
            }
            is Obj -> {
                sb.append('{')
                var first = true
                for ((key, value) in entries) {
                    if (!first) sb.append(',')
                    first = false
                    encodeString(key, sb)
                    sb.append(':')
                    value.encodeTo(sb)
                }
                sb.append('}')
            }
        }
    }

    companion object {
        const val MAX_DEPTH = 64

        fun parse(text: String): Json = Parser(text).parseDocument()

        fun obj(vararg pairs: Pair<String, Json>): Obj = Obj(linkedMapOf(*pairs))

        private fun encodeString(value: String, sb: StringBuilder) {
            sb.append('"')
            for (ch in value) {
                when {
                    ch == '"' -> sb.append("\\\"")
                    ch == '\\' -> sb.append("\\\\")
                    ch == '\n' -> sb.append("\\n")
                    ch == '\r' -> sb.append("\\r")
                    ch == '\t' -> sb.append("\\t")
                    ch < ' ' -> sb.append("\\u%04x".format(ch.code))
                    else -> sb.append(ch)
                }
            }
            sb.append('"')
        }
    }

    private class Parser(private val text: String) {
        private var pos = 0

        fun parseDocument(): Json {
            val value = parseValue(0)
            skipWhitespace()
            if (pos != text.length) fail("trailing characters at $pos")
            return value
        }

        private fun parseValue(depth: Int): Json {
            if (depth > MAX_DEPTH) fail("nesting deeper than $MAX_DEPTH")
            skipWhitespace()
            if (pos >= text.length) fail("unexpected end of input")
            return when (text[pos]) {
                '{' -> parseObject(depth)
                '[' -> parseArray(depth)
                '"' -> Str(parseString())
                't' -> literal("true", Bool(true))
                'f' -> literal("false", Bool(false))
                'n' -> literal("null", Null)
                else -> parseNumber()
            }
        }

        private fun parseObject(depth: Int): Obj {
            expect('{')
            val entries = LinkedHashMap<String, Json>()
            skipWhitespace()
            if (peek() == '}') { pos++; return Obj(entries) }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("expected object key at $pos")
                val key = parseString()
                if (entries.containsKey(key)) fail("duplicate key '$key'")
                skipWhitespace()
                expect(':')
                entries[key] = parseValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> { pos++; return Obj(entries) }
                    else -> fail("expected ',' or '}' at $pos")
                }
            }
        }

        private fun parseArray(depth: Int): Arr {
            expect('[')
            val items = mutableListOf<Json>()
            skipWhitespace()
            if (peek() == ']') { pos++; return Arr(items) }
            while (true) {
                items.add(parseValue(depth + 1))
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> { pos++; return Arr(items) }
                    else -> fail("expected ',' or ']' at $pos")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (pos >= text.length) fail("unterminated string")
                when (val ch = text[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= text.length) fail("unterminated escape")
                        when (val esc = text[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) fail("truncated \\u escape")
                                val hex = text.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16) ?: fail("bad \\u escape '$hex'")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> fail("bad escape '\\$esc'")
                        }
                    }
                    else -> {
                        if (ch < ' ') fail("raw control character in string")
                        sb.append(ch)
                    }
                }
            }
        }

        private fun parseNumber(): Num {
            val start = pos
            if (peek() == '-') pos++
            when {
                peek() == '0' -> pos++
                peek() in '1'..'9' -> while (pos < text.length && text[pos] in '0'..'9') pos++
                else -> fail("invalid number at $start")
            }
            if (pos < text.length && text[pos] == '.') {
                pos++
                if (pos >= text.length || text[pos] !in '0'..'9') fail("invalid fraction")
                while (pos < text.length && text[pos] in '0'..'9') pos++
            }
            if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
                pos++
                if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
                if (pos >= text.length || text[pos] !in '0'..'9') fail("invalid exponent")
                while (pos < text.length && text[pos] in '0'..'9') pos++
            }
            return Num(text.substring(start, pos))
        }

        private fun literal(word: String, value: Json): Json {
            if (!text.startsWith(word, pos)) fail("invalid literal at $pos")
            pos += word.length
            return value
        }

        private fun skipWhitespace() {
            while (pos < text.length && text[pos] in " \t\n\r") pos++
        }

        private fun peek(): Char =
            if (pos < text.length) text[pos] else fail("unexpected end of input")

        private fun expect(ch: Char) {
            if (pos >= text.length || text[pos] != ch) fail("expected '$ch' at $pos")
            pos++
        }

        private fun fail(message: String): Nothing = throw JsonParseException(message)
    }
}

class JsonParseException(message: String) : Exception(message)
