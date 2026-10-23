package com.vovoscan.core.common.i18n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalesTest {
    @Test
    fun theLocaleComesFromTheAccountThenTheDeviceChoiceThenTheDevice() {
        assertEquals("fr", UiLocales.resolve("fr", "en", listOf("en-US")))
        assertEquals("en", UiLocales.resolve(null, "en", listOf("fr-FR")))
        assertEquals("fr", UiLocales.resolve(null, null, listOf("ee-TG", "fr-BE", "en")))
        assertEquals("fr", UiLocales.resolve("de", null, listOf("fr_CA")))
        assertEquals("en", UiLocales.resolve(null, null, listOf("ja-JP")))
        assertEquals("fr-TG", UiLocales.formattingTag("fr", "TG"))
        assertEquals("fr", UiLocales.formattingTag("fr", null))
    }

    @Test
    fun theRegionComesFromTheFirstDeviceLanguageThatHasOne() {
        assertEquals("TG", Regions.fromDevice(listOf("fr", "fr-TG", "en-US")))
        assertEquals("CA", Regions.fromDevice(listOf("fr_CA")))
        assertEquals("BJ", Regions.fromDevice(listOf("zh-Hans-BJ")))
        assertNull(Regions.fromDevice(listOf("en", "fr")))
        assertNull(Regions.fromDevice(listOf("en-XX")))
        assertEquals(249, Regions.CODES.size)
        assertEquals(Regions.CODES.sorted(), Regions.CODES)
        assertTrue(Regions.valid("TG"))
        assertFalse(Regions.valid("tg"))
    }

    @Test
    fun theMinimumAgeTableIsTheSameEverywhere() {
        assertEquals(15, Regions.minimumAge("FR"))
        assertEquals(16, Regions.minimumAge("DE"))
        assertEquals(14, Regions.minimumAge("IT"))
        assertEquals(13, Regions.minimumAge("us"))
        assertEquals(16, Regions.minimumAge("TG"))
        assertEquals(16, Regions.minimumAge(null))
    }

    @Test
    fun frenchPluralsPutZeroAndOneTogether() {
        assertEquals("one", PluralRules.category("fr", 0))
        assertEquals("one", PluralRules.category("fr", 1.9))
        assertEquals("other", PluralRules.category("fr", 2))
        assertEquals("many", PluralRules.category("fr", 1_000_000))
        assertEquals("other", PluralRules.category("en", 0))
        assertEquals("one", PluralRules.category("en-GB", 1))
        assertEquals("other", PluralRules.category("en", 1.5))
    }
}
