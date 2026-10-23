package com.vovoscan.core.common.i18n

/** Reads a flat JSON object of strings (a catalogue file); anything else is refused. */
internal object FlatJson {
    fun parse(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = 0

        fun spaces() {
            while (i < text.length && text[i].isWhitespace()) i++
        }

        fun expect(c: Char) {
            spaces()
            require(text.getOrNull(i) == c) { "'$c' expected at $i" }
            i++
        }

        fun string(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                require(i < text.length) { "unterminated string" }
                when (val c = text[i++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = text[i++]) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                sb.append(text.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }
        expect('{')
        spaces()
        if (text.getOrNull(i) == '}') return out
        while (true) {
            val key = string()
            expect(':')
            spaces()
            out[key] = string()
            spaces()
            when (text.getOrNull(i)) {
                ',' -> i++
                '}' -> return out
                else -> throw IllegalArgumentException("',' or '}' expected at $i")
            }
        }
    }
}
