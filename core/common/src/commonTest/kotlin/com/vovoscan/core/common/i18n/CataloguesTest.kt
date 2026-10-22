package com.vovoscan.core.common.i18n

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** The catalogues (i18n/<locale>.json): the same keys and placeholders everywhere, valid patterns, French typography. */
class CataloguesTest {
    @AfterTest
    fun reset() = I18n.use("en", null)

    private val reference = Catalogues.messages(UiLocales.DEFAULT)

    private val placeholder = Regex("\\{\\s*([A-Za-z0-9_]+)\\s*[,}]")

    @Test
    fun everyLocaleHasExactlyTheReferenceKeys() {
        assertTrue(reference.size > 50, "the English catalogue is loaded")
        UiLocales.ALL.forEach { locale ->
            val messages = Catalogues.messages(locale)
            val missing = reference.keys - messages.keys
            val extra = messages.keys - reference.keys
            if (missing.isNotEmpty() || extra.isNotEmpty()) fail("$locale: missing $missing, extra $extra")
        }
    }

    @Test
    fun placeholdersAreTheSameInEveryLocale() {
        UiLocales.ALL.forEach { locale ->
            Catalogues.messages(locale).forEach { (key, value) ->
                val expected = placeholder.findAll(reference.getValue(key)).map { it.groupValues[1] }.toSet()
                val found = placeholder.findAll(value).map { it.groupValues[1] }.toSet()
                assertEquals(expected, found, "$locale $key")
            }
        }
    }

    @Test
    fun keysAreNamespacedInLowerCamelCase() {
        val key = Regex("^[a-z][A-Za-z0-9]*(\\.[A-Za-z0-9_]+)+$")
        reference.keys.forEach { assertTrue(key.matches(it), "key $it") }
    }

    @Test
    fun everyPatternFormats() {
        UiLocales.ALL.forEach { locale ->
            Catalogues.messages(locale).forEach { (key, value) ->
                // A malformed pattern comes back as it is: one with a brace in it would be shown raw.
                val out = MessageFormat.format(value, locale, emptyMap())
                if (value.contains(',') && value.contains('{') && out == value && value.contains("plural")) fail("$locale $key")
            }
        }
    }

    @Test
    fun frenchIsTypesetAsFrench() {
        Catalogues.messages("fr").forEach { (key, value) ->
            val text = value.replace(Regex("\\{[^{}]*\\}"), "")
            assertTrue('\'' !in text, "fr $key: typographic apostrophe ’, not '")
            assertTrue('"' !in text && '“' !in text, "fr $key: « » quotes")
            Regex("(.)([:;?!])").findAll(text).forEach { m ->
                val before = m.groupValues[1]
                // "https://", "10:30" and the like are not punctuation.
                if (before[0].isLetterOrDigit() && m.groupValues[2] == ":" && text.contains("://")) return@forEach
                assertEquals(" ", before, "fr $key: narrow no-break space before ${m.groupValues[2]} in \"$value\"")
            }
        }
    }

    @Test
    fun aKeyMissingInALocaleFallsBackToEnglishThenToTheKey() {
        I18n.use("fr")
        assertEquals("nope.missing", t("nope.missing"))
        assertEquals("fr", I18n.locale.value)
        I18n.use("xx")
        assertEquals("en", I18n.locale.value)
    }
}
