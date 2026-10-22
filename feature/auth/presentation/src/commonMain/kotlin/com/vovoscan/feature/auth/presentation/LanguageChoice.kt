package com.vovoscan.feature.auth.presentation

import com.vovoscan.core.common.Clock
import com.vovoscan.core.common.i18n.DeviceLocale
import com.vovoscan.core.common.i18n.I18n
import com.vovoscan.core.common.i18n.LocalFormat
import com.vovoscan.core.common.i18n.PlatformFormats
import com.vovoscan.core.common.i18n.Regions
import com.vovoscan.core.common.i18n.UiLocales
import com.vovoscan.core.common.i18n.t

/** A region in the picker, named in the UI language ("Togo"); [code] null is "Not set". */
data class RegionRow(
    val code: String?,
    val label: String,
    val selected: Boolean,
)

/**
 * The language-and-region block shared by the tutorial's `language` step and Settings › Preferences: the UI languages
 * (each in its own words), the searchable regions and a live sample of how dates and numbers look.
 */
data class LanguageChoiceUi(
    val locales: List<UiLocaleOption> = emptyList(),
    val regionLabel: String = "",
    val regionQuery: String = "",
    val regions: List<RegionRow> = emptyList(),
    /** "Preview: 22 octobre 2026 · 14:03 · 1 234,5". */
    val preview: String = "",
    /** "This device’s (Africa/Lome)". */
    val timeZone: String = "",
    val saving: Boolean = false,
) {
    val title: String get() = t("prefs.language.title")
    val interfaceLabel: String get() = t("locale.interface")
    val interfaceHint: String get() = t("locale.interfaceHint")
    val regionTitle: String get() = t("locale.region")
    val regionHint: String get() = t("locale.regionHint")
    val timeZoneTitle: String get() = t("locale.timeZone")
}

internal object LanguageChoice {
    fun build(
        region: String?,
        query: String,
        clock: Clock,
        zone: String?,
        saving: Boolean = false,
    ): LanguageChoiceUi {
        val needle = query.trim().lowercase()
        val named = Regions.CODES.map { it to PlatformFormats.regionName(it, I18n.formattingTag) }
        val rows =
            listOf(RegionRow(null, t("locale.regionNone"), region == null)).filter { needle.isEmpty() } +
                named
                    .filter { (code, name) -> needle.isEmpty() || name.lowercase().contains(needle) || code.lowercase() == needle }
                    .sortedBy { (_, name) -> name.lowercase() }
                    .map { (code, name) -> RegionRow(code, name, code == region) }
        val now = clock.nowMillis()
        return LanguageChoiceUi(
            locales = UiLocales.ALL.map { UiLocaleOption(it, UiLocales.NATIVE_NAMES[it] ?: it, it == I18n.locale.value) },
            regionLabel = region?.let { PlatformFormats.regionName(it, I18n.formattingTag) } ?: t("locale.regionNone"),
            regionQuery = query,
            regions = rows,
            preview =
                t(
                    "locale.preview",
                    "date" to PlatformFormats.date(now, "long", I18n.formattingTag),
                    "time" to PlatformFormats.time(now, "short", I18n.formattingTag),
                    "number" to LocalFormat.decimal(1234.5, 1),
                ),
            timeZone = zone ?: t("locale.timeZoneDevice", "zone" to DeviceLocale.timeZone()),
            saving = saving,
        )
    }
}
