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

    /** A number with exactly [fractionDigits] decimals and the locale's grouping ("1,234.50", "1 234,50"). */
    fun decimal(
        value: Double,
        fractionDigits: Int,
        tag: String,
    ): String

    /** An amount in [currency] (ISO 4217) as the locale writes it ("$1.24", "1,24 $US"). */
    fun currency(
        value: Double,
        currency: String,
        tag: String,
    ): String

    /**
     * Dates and times of a wall clock: [localMillis] is the local time counted as if it were UTC (the caller applied
     * the time zone), so the result never depends on the device's zone.
     */
    fun wallDate(
        localMillis: Long,
        style: String,
        tag: String,
    ): String

    fun wallTime(
        localMillis: Long,
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
