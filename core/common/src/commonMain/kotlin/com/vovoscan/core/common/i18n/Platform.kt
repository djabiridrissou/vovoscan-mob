package com.vovoscan.core.common.i18n

/** The device's preferred languages, most preferred first ("fr-TG", "en-US"). */
expect object DeviceLocale {
    fun languages(): List<String>

    /** The device's IANA time zone ("Africa/Lome"). */
    fun timeZone(): String
}

/** The platforms' ICU: numbers, dates, region names. */
expect object PlatformFormats : MessageFormat.Formats {
    override fun number(
        value: Number,
        tag: String,
    ): String

    override fun date(
        epochMillis: Long,
        style: String,
        tag: String,
    ): String

    override fun time(
        epochMillis: Long,
        style: String,
        tag: String,
    ): String

    /** "anglais" for en in French; null when the platform doesn't know the language. */
    fun languageName(
        code: String,
        tag: String,
    ): String?

    /** "Togo" (en), "Togo" (fr), "Allemagne" for DE in French. */
    fun regionName(
        code: String,
        tag: String,
    ): String
}
