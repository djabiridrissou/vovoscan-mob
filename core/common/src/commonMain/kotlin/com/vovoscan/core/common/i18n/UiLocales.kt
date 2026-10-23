package com.vovoscan.core.common.i18n

/**
 * The languages of the app's own words (i18n/SPEC.md §1-2), separate from the content languages of chapters and
 * episodes (`Language.ALL`). A new one = its catalogue file (core/common/i18n/<code>.json) and its code here.
 */
object UiLocales {
    const val DEFAULT = "en"

    val ALL: List<String> = listOf("en", "fr")

    /** Each language named in itself, for the picker. */
    val NATIVE_NAMES: Map<String, String> = mapOf("en" to "English", "fr" to "Français")

    /** "fr-BE" → "fr", "pt_BR" → "pt". */
    fun base(tag: String): String = tag.substringBefore('-').substringBefore('_').lowercase()

    fun supported(tag: String?): String? = tag?.let(::base)?.takeIf { it in ALL }

    /**
     * The resolution chain: the account's locale, the one chosen on this device before signing in, the first device
     * language whose base is supported, else English.
     */
    fun resolve(
        account: String?,
        saved: String?,
        device: List<String>,
    ): String = supported(account) ?: supported(saved) ?: device.firstNotNullOfOrNull(::supported) ?: DEFAULT

    /** Formatting tag: "fr-TG" when a region is set, else "fr". */
    fun formattingTag(
        locale: String,
        region: String?,
    ): String = if (region.isNullOrBlank()) locale else "$locale-$region"
}
