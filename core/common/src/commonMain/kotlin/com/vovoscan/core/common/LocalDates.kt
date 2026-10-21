package com.vovoscan.core.common

/** The device's offset from UTC at a moment (daylight saving included): local dates in labels and periods. */
fun interface UtcOffset {
    fun offsetMillis(epochMillis: Long): Long
}

/** The device's time zone (`java.util.TimeZone` on Android, `NSTimeZone` on iOS). */
expect object DeviceTimeZone : UtcOffset {
    override fun offsetMillis(epochMillis: Long): Long
}

/** A calendar date (proleptic Gregorian), month 1–12. */
data class CivilDate(
    val year: Int,
    val month: Int,
    val day: Int,
)

const val DAY_MILLIS = 86_400_000L

/** Days since 1970-01-01 of a date (H. Hinnant's `days_from_civil`). */
fun epochDay(date: CivilDate): Long {
    val y = (if (date.month <= 2) date.year - 1 else date.year).toLong()
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val mp = (date.month + 9) % 12
    val doy = (153 * mp + 2) / 5 + date.day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097 + doe - 719_468
}

/** The date of a day number since 1970-01-01 (`civil_from_days`). */
fun civilDate(epochDay: Long): CivilDate {
    val z = epochDay + 719_468
    val era = (if (z >= 0) z else z - 146_096) / 146_097
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = (doy - (153 * mp + 2) / 5 + 1).toInt()
    val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
    val year = (yoe + era * 400 + if (month <= 2) 1 else 0).toInt()
    return CivilDate(year, month, day)
}

/** The local calendar date of a moment, in the time zone [offset]. */
fun localDate(
    epochMillis: Long,
    offset: UtcOffset,
): CivilDate = civilDate((epochMillis + offset.offsetMillis(epochMillis)).floorDiv(DAY_MILLIS))

/** The moment local midnight starts [date] in the time zone [offset]. */
fun startOfLocalDay(
    date: CivilDate,
    offset: UtcOffset,
): Long {
    val utcMidnight = epochDay(date) * DAY_MILLIS
    // The offset at midnight local time: one correction is enough outside a transition hour.
    val guess = utcMidnight - offset.offsetMillis(utcMidnight)
    return utcMidnight - offset.offsetMillis(guess)
}
