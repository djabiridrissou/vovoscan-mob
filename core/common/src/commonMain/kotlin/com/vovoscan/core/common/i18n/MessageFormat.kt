package com.vovoscan.core.common.i18n

/**
 * The ICU MessageFormat subset of the shared catalogues (i18n/SPEC.md §5): `{name}`, `{n, number}`,
 * `{n, plural, =0 {…} one {# chapter} other {# chapters}}`, `{kind, select, book {…} other {…}}`,
 * `{when, date, short|medium|long|full}` and `{when, time, short}` (epoch milliseconds).
 * Apostrophes follow ICU's default: `''` is one apostrophe, `'{` … `'` quotes syntax, any other `'` is literal
 * (French copy uses the typographic ’ anyway). A malformed pattern is returned as it is, never thrown.
 */
object MessageFormat {
    fun format(
        pattern: String,
        locale: String,
        args: Map<String, Any?> = emptyMap(),
        formats: Formats = PlatformFormats,
    ): String =
        try {
            Parser(pattern).parseMessage(end = null).render(Context(locale, args, formats, hash = null))
        } catch (_: IllegalArgumentException) {
            pattern
        }

    /** Locale-aware number and date formatting; the platforms' ICU in the apps, a fixed one in tests. */
    interface Formats {
        fun number(
            value: Number,
            tag: String,
        ): String

        fun date(
            epochMillis: Long,
            style: String,
            tag: String,
        ): String

        fun time(
            epochMillis: Long,
            style: String,
            tag: String,
        ): String
    }

    private class Context(
        val tag: String,
        val args: Map<String, Any?>,
        val formats: Formats,
        val hash: Number?,
    )

    private sealed interface Node {
        fun render(context: Context): String
    }

    private class Message(
        val parts: List<Node>,
    ) : Node {
        override fun render(context: Context): String = parts.joinToString("") { it.render(context) }
    }

    private class Text(
        val value: String,
    ) : Node {
        override fun render(context: Context): String = value
    }

    /** `#` inside a plural branch: the number, formatted. */
    private object Hash : Node {
        override fun render(context: Context): String = context.hash?.let { context.formats.number(it, context.tag) } ?: "#"
    }

    private class Argument(
        val name: String,
        val type: String?,
        val style: String?,
    ) : Node {
        override fun render(context: Context): String {
            val value = context.args[name] ?: return "{$name}"
            return when (type) {
                "number" -> (value as? Number)?.let { context.formats.number(it, context.tag) } ?: value.toString()
                "date" -> (value as? Number)?.let { context.formats.date(it.toLong(), style ?: "medium", context.tag) } ?: value.toString()
                "time" -> (value as? Number)?.let { context.formats.time(it.toLong(), style ?: "short", context.tag) } ?: value.toString()
                else -> value.toString()
            }
        }
    }

    private class Choice(
        val name: String,
        val plural: Boolean,
        val branches: Map<String, Message>,
    ) : Node {
        override fun render(context: Context): String {
            val value = context.args[name]
            if (!plural) {
                val branch = branches[value?.toString()] ?: branches["other"] ?: return ""
                return branch.render(context)
            }
            val number = value as? Number ?: (value?.toString()?.toDoubleOrNull()) ?: return branches["other"]?.render(context).orEmpty()
            val exact = branches["=" + exactKey(number)]
            val branch = exact ?: branches[PluralRules.category(context.tag, number)] ?: branches["other"] ?: return ""
            return branch.render(Context(context.tag, context.args, context.formats, number))
        }

        private fun exactKey(number: Number): String {
            val d = number.toDouble()
            return if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()
        }
    }

    private class Parser(
        private val s: String,
    ) {
        private var i = 0

        /** Until `end` (a closing brace of a branch) or the end of the pattern. */
        fun parseMessage(
            end: Char?,
            inPlural: Boolean = false,
        ): Message {
            val parts = mutableListOf<Node>()
            val text = StringBuilder()

            fun flush() {
                if (text.isNotEmpty()) parts += Text(text.toString()).also { text.clear() }
            }
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '\'' -> quoted(text)
                    c == '{' -> {
                        flush()
                        parts += argument()
                    }
                    c == '}' -> {
                        require(end == '}') { "unbalanced }" }
                        flush()
                        return Message(parts)
                    }
                    c == '#' && inPlural -> {
                        flush()
                        parts += Hash
                        i++
                    }
                    else -> {
                        text.append(c)
                        i++
                    }
                }
            }
            require(end == null) { "unclosed {" }
            flush()
            return Message(parts)
        }

        private fun quoted(text: StringBuilder) {
            val next = s.getOrNull(i + 1)
            when (next) {
                '\'' -> {
                    text.append('\'')
                    i += 2
                }
                '{', '}', '#' -> {
                    val close = s.indexOf('\'', i + 1)
                    if (close < 0) {
                        text.append(s, i + 1, s.length)
                        i = s.length
                    } else {
                        text.append(s, i + 1, close)
                        i = close + 1
                    }
                }
                else -> {
                    text.append('\'')
                    i++
                }
            }
        }

        private fun argument(): Node {
            i++ // {
            val name = word()
            skipSpaces()
            if (peek() == '}') {
                i++
                return Argument(name, null, null)
            }
            expect(',')
            val type = word()
            skipSpaces()
            return when (type) {
                "plural", "select" -> {
                    expect(',')
                    val branches = linkedMapOf<String, Message>()
                    while (true) {
                        skipSpaces()
                        if (peek() == '}') {
                            i++
                            break
                        }
                        val key = word()
                        skipSpaces()
                        expect('{', skip = false)
                        i++
                        branches[key] = parseMessage(end = '}', inPlural = type == "plural")
                        i++ // }
                    }
                    require("other" in branches) { "no other branch" }
                    Choice(name, type == "plural", branches)
                }
                else -> {
                    var style: String? = null
                    if (peek() == ',') {
                        i++
                        style = word()
                        skipSpaces()
                    }
                    expect('}')
                    Argument(name, type, style)
                }
            }
        }

        private fun word(): String {
            skipSpaces()
            val start = i
            while (i < s.length && s[i] !in " \t\n,{}") i++
            require(i > start) { "name expected" }
            return s.substring(start, i)
        }

        private fun skipSpaces() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun peek(): Char? = s.getOrNull(i)

        private fun expect(
            c: Char,
            skip: Boolean = true,
        ) {
            skipSpaces()
            require(peek() == c) { "$c expected" }
            if (skip) i++
        }
    }
}
