package com.vovoscan.core.common.i18n

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The app's words in the UI locale (i18n/SPEC.md). Catalogues: core/common/i18n/<locale>.json (English is the
 * reference, the others have exactly its keys), built into the code. A key missing in a locale falls back to English,
 * then to the key itself. ViewModels write their texts with [t]; the native screens only show them.
 */
object I18n {
    private data class Current(
        val locale: String,
        val region: String?,
    )

    // English until the app resolves the locale at launch (`UiLocales.resolve`): tests read the reference wording.
    private val current = MutableStateFlow(Current(UiLocales.DEFAULT, null))

    /** The UI locale now ("en", "fr"). Changing it rebuilds the screens (their ViewModels write texts once). */
    val locale: StateFlow<String> get() = localeFlow.asStateFlow()

    private val localeFlow = MutableStateFlow(current.value.locale)

    val region: String? get() = current.value.region

    /** "fr-TG" or "fr": dates and numbers. */
    val formattingTag: String get() = UiLocales.formattingTag(current.value.locale, current.value.region)

    fun use(
        locale: String,
        region: String? = this.region,
    ) {
        val next = Current(UiLocales.supported(locale) ?: UiLocales.DEFAULT, region?.uppercase()?.takeIf(Regions::valid))
        current.value = next
        localeFlow.value = next.locale
    }

    fun has(key: String): Boolean = Catalogues.messages(UiLocales.DEFAULT).containsKey(key)

    fun t(
        key: String,
        vararg args: Pair<String, Any?>,
    ): String = format(key, current.value.locale, args.toMap())

    /** For Swift and Compose: [key] without arguments (Kotlin varargs read badly from Swift). */
    fun text(key: String): String = format(key, current.value.locale, emptyMap())

    /** For Swift and Compose: [key] with named arguments. */
    fun textWith(
        key: String,
        args: Map<String, Any?>,
    ): String = format(key, current.value.locale, args)

    /** The same key in a given locale (the language picker shows each language in its own words). */
    fun inLocale(
        locale: String,
        key: String,
        vararg args: Pair<String, Any?>,
    ): String = format(key, locale, args.toMap())

    private fun format(
        key: String,
        locale: String,
        args: Map<String, Any?>,
    ): String {
        val pattern = Catalogues.messages(locale)[key] ?: Catalogues.messages(UiLocales.DEFAULT)[key] ?: return key
        val tag = if (locale == current.value.locale) formattingTag else locale
        return MessageFormat.format(pattern, tag, args)
    }
}

/** `t("library.empty")`, `t("episode.step", "done" to 3, "total" to 9)`. */
fun t(
    key: String,
    vararg args: Pair<String, Any?>,
): String = I18n.t(key, *args)

internal object Catalogues {
    private val parsed = mutableMapOf<String, Map<String, String>>()

    fun messages(locale: String): Map<String, String> =
        parsed.getOrPut(locale) { CatalogueSources.json[locale]?.let(FlatJson::parse).orEmpty() }
}

/** Numbers, money and dates in the UI locale and region, through the platform's ICU. Never hand-built. */
object LocalFormat {
    fun decimal(
        value: Double,
        fractionDigits: Int = 0,
    ): String = PlatformFormats.decimal(value, fractionDigits, I18n.formattingTag)

    fun integer(value: Long): String = PlatformFormats.decimal(value.toDouble(), 0, I18n.formattingTag)

    /** "$1.24" in English, "1,24 $US" in French; an amount above zero but under a cent says so. */
    fun usd(value: Double): String =
        when {
            value <= 0.0 -> PlatformFormats.currency(0.0, "USD", I18n.formattingTag)
            value < 0.01 -> t("common.underAmount", "amount" to PlatformFormats.currency(0.01, "USD", I18n.formattingTag))
            else -> PlatformFormats.currency(value, "USD", I18n.formattingTag)
        }

    /** [localMillis]: local wall time counted as UTC (see [PlatformFormats.wallDate]). */
    fun date(
        localMillis: Long,
        style: String = "medium",
    ): String = PlatformFormats.wallDate(localMillis, style, I18n.formattingTag)

    fun time(
        localMillis: Long,
        style: String = "short",
    ): String = PlatformFormats.wallTime(localMillis, style, I18n.formattingTag)
}
