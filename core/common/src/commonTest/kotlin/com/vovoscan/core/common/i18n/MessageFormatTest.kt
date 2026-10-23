package com.vovoscan.core.common.i18n

import kotlin.test.Test
import kotlin.test.assertEquals

class MessageFormatTest {
    /** Plain digits and ISO dates: the format logic, apart from the platforms' ICU. */
    private val plain =
        object : MessageFormat.Formats {
            override fun number(
                value: Number,
                tag: String,
            ) = if (value.toDouble() % 1.0 == 0.0) value.toLong().toString() else value.toString()

            override fun date(
                epochMillis: Long,
                style: String,
                tag: String,
            ) = "date($epochMillis,$style,$tag)"

            override fun time(
                epochMillis: Long,
                style: String,
                tag: String,
            ) = "time($epochMillis,$style)"
        }

    private fun f(
        pattern: String,
        locale: String = "en",
        vararg args: Pair<String, Any?>,
    ) = MessageFormat.format(pattern, locale, args.toMap(), plain)

    @Test
    fun argumentsAndMissingOnes() {
        assertEquals("Hello Ama.", f("Hello {name}.", "en", "name" to "Ama"))
        assertEquals("Hello {name}.", f("Hello {name}."))
    }

    @Test
    fun pluralsFollowEachLocale() {
        val en = "{count, plural, =0 {No chapter} one {# chapter} other {# chapters}}"
        assertEquals("No chapter", f(en, "en", "count" to 0))
        assertEquals("1 chapter", f(en, "en", "count" to 1))
        assertEquals("2 chapters", f(en, "en", "count" to 2))
        val fr = "{count, plural, one {# chapitre} other {# chapitres}}"
        assertEquals("0 chapitre", f(fr, "fr", "count" to 0))
        assertEquals("1 chapitre", f(fr, "fr", "count" to 1))
        assertEquals("1.5 chapitre", f(fr, "fr", "count" to 1.5))
        assertEquals("2 chapitres", f(fr, "fr-TG", "count" to 2))
    }

    @Test
    fun selectNestedArgumentsAndFormats() {
        val p = "{kind, select, book {Part {n}} other {Chapter {n}}} · {when, date, long} · {n, number}"
        assertEquals("Part 3 · date(0,long,en) · 3", f(p, "en", "kind" to "book", "n" to 3, "when" to 0L))
        assertEquals("Chapter 3 · date(0,long,en) · 3", f(p, "en", "kind" to "comic", "n" to 3, "when" to 0L))
    }

    @Test
    fun apostrophesFollowIcu() {
        assertEquals("It's {literal}", f("It's '{literal}'"))
        assertEquals("one ' quote", f("one '' quote"))
        assertEquals("l’appareil", f("l’appareil", "fr"))
    }

    @Test
    fun aMalformedPatternIsShownAsItIs() {
        assertEquals("{count, plural, one {x}", f("{count, plural, one {x}"))
        assertEquals("oops }", f("oops }"))
    }
}
