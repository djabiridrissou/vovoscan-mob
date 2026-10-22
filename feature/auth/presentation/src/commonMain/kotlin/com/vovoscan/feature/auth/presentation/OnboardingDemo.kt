package com.vovoscan.feature.auth.presentation

import com.vovoscan.core.common.Language
import com.vovoscan.core.common.i18n.t

/**
 * The tutorial's live reader demo: one balloon of a sample page, lettered in Japanese, then set again in the reading
 * language, the way a translated chapter reads. One short line, written out by hand for the languages it is sure of;
 * any other language shows it in the app's language and says so, never a guessed translation.
 */
data class DemoBalloon(
    /** The balloon as drawn ("行くぞ！"). */
    val original: String,
    /** The same balloon in [languageCode], or in the app's language when [exact] is false. */
    val translated: String,
    val languageCode: String,
    val rtl: Boolean,
    /** [translated] is in the reading language itself. */
    val exact: Boolean,
    /** "Same page, in Français." / "Shown in English: your chapters come in Eʋegbe." */
    val caption: String,
)

object DemoLines {
    const val ORIGINAL = "行くぞ！"

    /** "Here we go!" Narrow no-break space before French "!". */
    private val LINES =
        mapOf(
            "en" to "Here we go!",
            "fr" to "C’est parti !",
            "es" to "¡Allá vamos!",
            "pt" to "Lá vamos nós!",
            "de" to "Los geht’s!",
            "it" to "Andiamo!",
            "nl" to "Daar gaan we!",
            "pl" to "No to jazda!",
            "ro" to "Să mergem!",
            "tr" to "Hadi gidelim!",
            "ru" to "Поехали!",
            "uk" to "Поїхали!",
            "el" to "Πάμε!",
            "ar" to "هيا بنا!",
            "he" to "קדימה!",
            "hi" to "चलो चलें!",
            "vi" to "Đi thôi!",
            "id" to "Ayo berangkat!",
            "ms" to "Jom kita pergi!",
            "fil" to "Tara na!",
            "ja" to "行くぞ！",
            "ko" to "가자!",
            "zh-Hans" to "出发吧！",
            "zh-Hant" to "出發吧！",
            "sw" to "Twende!",
            "ln" to "Tokende!",
            "rw" to "Tugende!",
            "zu" to "Asihambe!",
            "xh" to "Masihambe!",
            "ht" to "Ann ale!",
        )

    fun line(code: String): String? = LINES[code]

    /** The balloon for [readingCode] (any of the 47 languages); [uiCode] is the app's own language. */
    fun balloon(
        readingCode: String,
        uiCode: String,
    ): DemoBalloon {
        val language = Language.of(readingCode)
        val name = language?.native ?: readingCode
        val exact = LINES[readingCode]
        return DemoBalloon(
            original = ORIGINAL,
            translated = exact ?: LINES[uiCode] ?: LINES.getValue("en"),
            languageCode = if (exact != null) readingCode else uiCode,
            rtl = exact != null && language?.rtl == true,
            exact = exact != null,
            caption =
                if (exact != null) {
                    t("onboarding.demo.same", "language" to name)
                } else {
                    t("onboarding.demo.pending", "language" to name)
                },
        )
    }
}
