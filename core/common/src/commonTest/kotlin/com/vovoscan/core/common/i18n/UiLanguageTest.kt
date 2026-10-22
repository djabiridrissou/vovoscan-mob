package com.vovoscan.core.common.i18n

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class UiLanguageTest {
    @AfterTest
    fun reset() = I18n.use("en", null)

    @Test
    fun theDeviceLanguageAndRegionApplyWhenNothingWasChosen() {
        UiLanguage(DevicePreferences.InMemory()) { listOf("de-DE", "fr-TG", "en-US") }.start()
        assertEquals("fr", I18n.locale.value)
        assertEquals("DE", I18n.region)
        assertEquals("fr-DE", I18n.formattingTag)
    }

    @Test
    fun aChoiceOnTheDeviceWinsOverTheDeviceLanguagesAndSurvivesARestart() {
        val prefs = DevicePreferences.InMemory()
        UiLanguage(prefs) { listOf("en-US") }.choose("fr", "TG")
        I18n.use("en", null)
        UiLanguage(prefs) { listOf("en-US") }.start()
        assertEquals("fr", I18n.locale.value)
        assertEquals("fr-TG", I18n.formattingTag)
    }

    @Test
    fun theAccountWinsAndBecomesTheDevicesChoice() {
        val prefs = DevicePreferences.InMemory()
        val language = UiLanguage(prefs) { listOf("fr-FR") }
        language.start()
        language.applyAccount("en", "BJ")
        assertEquals("en", I18n.locale.value)
        assertEquals("en-BJ", I18n.formattingTag)
        I18n.use("fr", null)
        UiLanguage(prefs) { listOf("fr-FR") }.start()
        assertEquals("en", I18n.locale.value)
    }

    @Test
    fun anAccountWithoutRegionKeepsTheDevicesOne() {
        val language = UiLanguage(DevicePreferences.InMemory()) { listOf("fr-CI") }
        language.applyAccount("fr", null)
        assertEquals("fr-CI", I18n.formattingTag)
    }

    @Test
    fun anUnsupportedChoiceIsIgnored() {
        val prefs = DevicePreferences.InMemory()
        UiLanguage(prefs) { listOf("en") }.choose("de")
        assertEquals(null, prefs.get("ui_locale"))
    }

    @Test
    fun boldRunsAreReadFromTheCatalogueTags() {
        assertEquals(
            listOf(RichRun("Your own keys", true), RichRun(" (Anthropic): free.", false)),
            RichText.runs("<b>Your own keys</b> (Anthropic): free."),
        )
        assertEquals("a <b b", RichText.plain("a <b b"))
    }
}
