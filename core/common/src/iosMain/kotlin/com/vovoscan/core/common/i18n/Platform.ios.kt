package com.vovoscan.core.common.i18n

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterFullStyle
import platform.Foundation.NSDateFormatterLongStyle
import platform.Foundation.NSDateFormatterMediumStyle
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.NSDateFormatterStyle
import platform.Foundation.NSLocale
import platform.Foundation.NSLocaleCountryCode
import platform.Foundation.NSLocaleIdentifier
import platform.Foundation.NSNumber
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterCurrencyStyle
import platform.Foundation.NSNumberFormatterDecimalStyle
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone
import platform.Foundation.preferredLanguages
import platform.Foundation.timeZoneForSecondsFromGMT

actual object DeviceLocale {
    actual fun languages(): List<String> = NSLocale.preferredLanguages.mapNotNull { it as? String }

    actual fun timeZone(): String = NSTimeZone.localTimeZone.name
}

actual object PlatformFormats : MessageFormat.Formats {
    actual override fun number(
        value: Number,
        tag: String,
    ): String {
        val formatter = NSNumberFormatter()
        formatter.locale = nsLocale(tag)
        formatter.numberStyle = NSNumberFormatterDecimalStyle
        formatter.maximumFractionDigits = 2u
        return formatter.stringFromNumber(NSNumber(double = value.toDouble())) ?: value.toString()
    }

    actual override fun date(
        epochMillis: Long,
        style: String,
        tag: String,
    ): String = formatter(tag, date = style(style), time = NSDateFormatterNoStyle).stringFromDate(at(epochMillis))

    actual override fun time(
        epochMillis: Long,
        style: String,
        tag: String,
    ): String = formatter(tag, date = NSDateFormatterNoStyle, time = style(style)).stringFromDate(at(epochMillis))

    actual fun decimal(
        value: Double,
        fractionDigits: Int,
        tag: String,
    ): String {
        val formatter = NSNumberFormatter()
        formatter.locale = nsLocale(tag)
        formatter.numberStyle = NSNumberFormatterDecimalStyle
        formatter.minimumFractionDigits = fractionDigits.toULong()
        formatter.maximumFractionDigits = fractionDigits.toULong()
        return formatter.stringFromNumber(NSNumber(double = value)) ?: value.toString()
    }

    actual fun currency(
        value: Double,
        currency: String,
        tag: String,
    ): String {
        val formatter = NSNumberFormatter()
        formatter.locale = nsLocale(tag)
        formatter.numberStyle = NSNumberFormatterCurrencyStyle
        formatter.currencyCode = currency
        return formatter.stringFromNumber(NSNumber(double = value)) ?: "$currency $value"
    }

    actual fun wallDate(
        localMillis: Long,
        style: String,
        tag: String,
    ): String =
        formatter(tag, date = style(style), time = NSDateFormatterNoStyle)
            .apply { timeZone = NSTimeZone.timeZoneForSecondsFromGMT(0) }
            .stringFromDate(at(localMillis))

    actual fun wallTime(
        localMillis: Long,
        style: String,
        tag: String,
    ): String =
        formatter(tag, date = NSDateFormatterNoStyle, time = style(style))
            .apply { timeZone = NSTimeZone.timeZoneForSecondsFromGMT(0) }
            .stringFromDate(at(localMillis))

    actual fun languageName(
        code: String,
        tag: String,
    ): String? = nsLocale(tag).displayNameForKey(NSLocaleIdentifier, code)?.takeIf { it.isNotBlank() && it != code }

    actual fun regionName(
        code: String,
        tag: String,
    ): String = nsLocale(tag).displayNameForKey(NSLocaleCountryCode, code) ?: code

    private fun nsLocale(tag: String) = NSLocale(localeIdentifier = tag.replace('-', '_'))

    private fun at(epochMillis: Long) = NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0)

    private fun formatter(
        tag: String,
        date: NSDateFormatterStyle,
        time: NSDateFormatterStyle,
    ) = NSDateFormatter().apply {
        locale = nsLocale(tag)
        dateStyle = date
        timeStyle = time
    }

    private fun style(name: String): NSDateFormatterStyle =
        when (name) {
            "short" -> NSDateFormatterShortStyle
            "long" -> NSDateFormatterLongStyle
            "full" -> NSDateFormatterFullStyle
            else -> NSDateFormatterMediumStyle
        }
}
