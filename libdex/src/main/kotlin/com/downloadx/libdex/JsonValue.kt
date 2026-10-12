package com.downloadx.libdex

/**
 * Minimal, allocation-conscious JSON parser.
 *
 * libdex has no external dependencies (DownloadX builds with plain kotlinc),
 * so this parser exists solely to read recovered config blobs. It is
 * deliberately tolerant: unknown fields are allowed everywhere and kept in
 * [JsonValue.Object.raw].
 *
 * Correctness rules:
 *  - Strings honour backslash escapes (including `\/`, which Stark's configs
 *    use heavily). Unpaired control characters terminate the string with an error.
 *  - Numbers are parsed as Double, exposed as Long when integral.
 *  - No trailing garbage: the document must be one value, optionally followed
 *    only by whitespace.
 */
sealed class JsonValue {
    object Null : JsonValue()
    data class Bool(val value: Boolean) : JsonValue()
    data class Num(val value: Double) : JsonValue() {
        val asLong: Long get() = if (value == Math.floor(value)) value.toLong() else throw NumberFormatException("not integral: $value")
    }
    data class Str(val value: String) : JsonValue()
    data class Arr(val items: List<JsonValue>) : JsonValue()
    data class Object(val fields: LinkedHashMap<String, JsonValue>) : JsonValue() {
        fun str(key: String): String? = (fields[key] as? Str)?.value
        fun bool(key: String): Boolean? = (fields[key] as? Bool)?.value
        fun num(key: String): Double? = (fields[key] as? Num)?.value
    }

    companion object {
        fun parse(text: String): JsonValue {
            val p = Parser(text)
            val v = p.parseValue()
            p.skipWs()
            if (!p.atEnd()) throw JsonError("trailing content at offset ${p.pos}")
            return v
        }
    }
}

class JsonError(message: String) : RuntimeException(message)

private class Parser(private val s: String) {
    var pos = 0

    fun atEnd(): Boolean = pos >= s.length

    fun skipWs() {
        while (pos < s.length && s[pos].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) pos++
    }

    fun parseValue(): JsonValue {
        skipWs()
        if (atEnd()) throw JsonError("unexpected end of input")
        return when (val c = s[pos]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonValue.Str(parseString())
            't' -> { expect("true"); JsonValue.Bool(true) }
            'f' -> { expect("false"); JsonValue.Bool(false) }
            'n' -> { expect("null"); JsonValue.Null }
            else -> if (c == '-' || c in '0'..'9') parseNumber() else throw JsonError("unexpected char '$c' at $pos")
        }
    }

    private fun expect(word: String) {
        if (!s.startsWith(word, pos)) throw JsonError("expected '$word' at $pos")
        pos += word.length
    }

    private fun parseObject(): JsonValue.Object {
        pos++ // consume '{'
        val fields = LinkedHashMap<String, JsonValue>()
        skipWs()
        if (!atEnd() && s[pos] == '}') { pos++; return JsonValue.Object(fields) }
        while (true) {
            skipWs()
            if (atEnd() || s[pos] != '"') throw JsonError("expected object key at $pos")
            val key = parseString()
            skipWs()
            if (atEnd() || s[pos] != ':') throw JsonError("expected ':' at $pos")
            pos++
            fields[key] = parseValue()
            skipWs()
            if (atEnd()) throw JsonError("unterminated object")
            when (s[pos]) {
                ',' -> { pos++; }
                '}' -> { pos++; return JsonValue.Object(fields) }
                else -> throw JsonError("expected ',' or '}' at $pos")
            }
        }
    }

    private fun parseArray(): JsonValue.Arr {
        pos++ // consume '['
        val items = ArrayList<JsonValue>()
        skipWs()
        if (!atEnd() && s[pos] == ']') { pos++; return JsonValue.Arr(items) }
        while (true) {
            items.add(parseValue())
            skipWs()
            if (atEnd()) throw JsonError("unterminated array")
            when (s[pos]) {
                ',' -> { pos++; }
                ']' -> { pos++; return JsonValue.Arr(items) }
                else -> throw JsonError("expected ',' or ']' at $pos")
            }
        }
    }

    private fun parseString(): String {
        pos++ // consume '"'
        val sb = StringBuilder()
        while (true) {
            if (atEnd()) throw JsonError("unterminated string")
            when (val c = s[pos]) {
                '"' -> { pos++; return sb.toString() }
                '\\' -> {
                    pos++
                    if (atEnd()) throw JsonError("dangling escape")
                    when (val e = s[pos]) {
                        '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                        'b' -> sb.append('\b'); 'f' -> sb.append(''); 'n' -> sb.append('\n')
                        'r' -> sb.append('\r'); 't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 >= s.length) throw JsonError("bad unicode escape")
                            sb.append(s.substring(pos + 1, pos + 5).toInt(16).toChar())
                            pos += 4
                        }
                        else -> throw JsonError("bad escape '\\$e'")
                    }
                    pos++
                }
                else -> {
                    if (c.code < 0x20) throw JsonError("raw control char in string at $pos")
                    sb.append(c); pos++
                }
            }
        }
    }

    private fun parseNumber(): JsonValue.Num {
        val start = pos
        if (s[pos] == '-') pos++
        while (pos < s.length && s[pos] in '0'..'9') pos++
        if (pos < s.length && s[pos] == '.') {
            pos++
            while (pos < s.length && s[pos] in '0'..'9') pos++
        }
        if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
            pos++
            if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
            while (pos < s.length && s[pos] in '0'..'9') pos++
        }
        return JsonValue.Num(s.substring(start, pos).toDouble())
    }
}
