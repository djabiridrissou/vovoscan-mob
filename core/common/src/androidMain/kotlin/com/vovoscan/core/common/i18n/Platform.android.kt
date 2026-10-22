package com.vovoscan.core.common.i18n

import android.os.LocaleList
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

actual object DeviceLocale {
    actual fun languages(): List<String> {
        val list = LocaleList.getDefault()
        return (0 until list.size()).map { list[it].toLanguageTag() }
    }

    actual fun timeZone(): String = TimeZone.getDefault().id
}

actual object PlatformFormats : MessageFormat.Formats {
    actual override fun number(
        value: Number,
        tag: String,
    ): String = NumberFormat.getInstance(Locale.forLanguageTag(tag)).format(value)

    actual override fun date(
        epochMillis: Long,
        style: String,
        tag: String,
    ): String = DateFormat.getDateInstance(style(style), Locale.forLanguageTag(tag)).format(Date(epochMillis))

    actual override fun time(
        epochMillis: Long,
        style: String,
        tag: String,
    ): String = DateFormat.getTimeInstance(style(style), Locale.forLanguageTag(tag)).format(Date(epochMillis))

    actual fun languageName(
        code: String,
        tag: String,
    ): String? {
        val language = Locale.forLanguageTag(code)
        return language.getDisplayName(Locale.forLanguageTag(tag)).takeIf { it.isNotBlank() && it != code && it != language.language }
    }

    actual fun regionName(
        code: String,
        tag: String,
    ): String = Locale("", code).getDisplayCountry(Locale.forLanguageTag(tag)).ifBlank { code }

    private fun style(name: String): Int =
        when (name) {
            "short" -> DateFormat.SHORT
            "long" -> DateFormat.LONG
            "full" -> DateFormat.FULL
            else -> DateFormat.MEDIUM
        }
}
