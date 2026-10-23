package com.vovoscan.core.common.i18n

import kotlin.math.abs

/** CLDR cardinal plural categories of the UI locales (French puts 0 and 1, and 1.5, in `one`). */
object PluralRules {
    fun category(
        tag: String,
        number: Number,
    ): String {
        val n = abs(number.toDouble())
        val integer = n.toLong()
        val whole = n == integer.toDouble()
        return when (UiLocales.base(tag)) {
            "fr" ->
                when {
                    integer == 0L || integer == 1L -> "one"
                    whole && integer % 1_000_000L == 0L -> "many"
                    else -> "other"
                }
            else -> if (whole && integer == 1L) "one" else "other"
        }
    }
}
