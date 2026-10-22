package com.vovoscan.feature.auth.presentation

import com.vovoscan.core.common.Language
import com.vovoscan.core.common.i18n.t

/**
 * The tutorial's live reader demo (i18n/SPEC.md §4, scene 3, shared with the web): one balloon of a sample page,
 * lettered in the reading language when a sample was written by hand for it, the Japanese original shown while "Hold
 * to see the original" is held. Any other language keeps the original in the balloon and says real chapters come in
 * it: never a machine-made fake.
 */
data class DemoBalloon(
    /** The balloon as drawn: 「行くぞ、みんな！」. */
    val original: String,
    /** What the balloon shows: the sample in the reading language, else the original. */
    val translated: String,
    /** The language of [translated] (`ja` for the original). */
    val languageCode: String,
    val rtl: Boolean,
    /** [translated] is a sample in the reading language: the hold button shows the original. */
    val exact: Boolean,
    /** The note over the panel: which of the three cases applies. */
    val caption: String,
    /** "Hold to see the original"; null when the balloon already shows it. */
    val holdLabel: String?,
)

object DemoLines {
    const val ORIGINAL = "行くぞ、みんな！"

    /** The exact samples of the spec, identical on every client (apostrophes U+2019). */
    private val SAMPLES =
        mapOf(
            "en" to "Let’s go, everyone!",
            "fr" to "On y va, tout le monde !",
            "es" to "¡Vamos, todos!",
            "pt" to "Vamos, pessoal!",
            "de" to "Los geht’s, alle zusammen!",
            "it" to "Andiamo, ragazzi!",
            "nl" to "Kom op, allemaal!",
            "pl" to "Ruszamy, wszyscy!",
            "tr" to "Hadi, herkes!",
            "ru" to "Вперёд, все!",
            "uk" to "Вперед, усі!",
            "ar" to "هيا بنا، جميعًا!",
            "hi" to "चलो, सब लोग!",
            "id" to "Ayo, semuanya!",
            "vi" to "Đi thôi, mọi người!",
            "ko" to "가자, 모두!",
            "ja" to ORIGINAL,
            "zh-Hans" to "大家，出发吧！",
            "zh-Hant" to "大家，出發吧！",
            "sw" to "Twendeni, wote!",
        )

    fun line(code: String): String? = SAMPLES[code]

    /** The balloon for [readingCode] (any of the 47 languages; null: none chosen yet). */
    fun balloon(readingCode: String?): DemoBalloon {
        val language = readingCode?.let(Language::of)
        val name = language?.native ?: readingCode.orEmpty()
        val sample = readingCode?.let(SAMPLES::get)
        return when {
            readingCode == null || language == null ->
                DemoBalloon(
                    ORIGINAL,
                    ORIGINAL,
                    "ja",
                    rtl = false,
                    exact = false,
                    caption = t("onboarding.reading.demo.none"),
                    holdLabel = null,
                )
            sample == null ->
                DemoBalloon(
                    ORIGINAL,
                    ORIGINAL,
                    "ja",
                    rtl = false,
                    exact = false,
                    caption = t("onboarding.reading.demo.later", "language" to name),
                    holdLabel = null,
                )
            else ->
                DemoBalloon(
                    original = ORIGINAL,
                    translated = sample,
                    languageCode = readingCode,
                    rtl = language.rtl,
                    exact = readingCode != "ja",
                    caption = t("onboarding.reading.demo.note", "language" to name),
                    holdLabel = if (readingCode != "ja") t("onboarding.reading.demo.hold") else null,
                )
        }
    }
}
