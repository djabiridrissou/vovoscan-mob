package com.vovoscan.core.common.i18n

import com.vovoscan.core.common.Language

/** One row of a content-language picker: its own name, then its name in the UI language ("Éwé" ✦ "Ewe"). */
data class LanguageRow(
    val code: String,
    val title: String,
    val detail: String,
    val selected: Boolean,
)

/** The one searchable picker of content languages (reading language, tutorial): own name, UI-language name or code. */
object LanguagePicker {
    fun rows(
        query: String,
        selected: String?,
    ): List<LanguageRow> {
        val needle = query.trim().lowercase()
        return Language.ALL
            .map { it to it.displayTitle() }
            .filter { (language, shown) -> needle.isEmpty() || language.matches(needle) || shown.lowercase().contains(needle) }
            .sortedBy { (_, shown) -> shown.lowercase() }
            .map { (language, shown) -> LanguageRow(language.code, language.native, shown, language.code == selected) }
    }

    /** "Français", or null for an unknown code. */
    fun label(code: String?): String? = code?.let(Language::of)?.native

    private fun Language.matches(needle: String): Boolean =
        native.lowercase().contains(needle) || name.lowercase().contains(needle) || code.lowercase() == needle
}
