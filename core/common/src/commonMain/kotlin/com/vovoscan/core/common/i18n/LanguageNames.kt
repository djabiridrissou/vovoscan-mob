package com.vovoscan.core.common.i18n

import com.vovoscan.core.common.Language

/**
 * A content language named in the UI locale, as the platform's ICU names it ("anglais" in French), else its English
 * name. As it is for a sentence ("Doublé en anglais"); [displayTitle] for a list or a label.
 */
fun Language.displayName(): String =
    if (I18n.locale.value == UiLocales.DEFAULT) name else PlatformFormats.languageName(code, I18n.formattingTag) ?: name

fun Language.displayTitle(): String = displayName().replaceFirstChar { it.uppercaseChar() }
