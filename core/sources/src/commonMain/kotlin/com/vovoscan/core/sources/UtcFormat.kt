package com.vovoscan.core.sources

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * UTC clock formatting for `{{utc:…}}` templates: the tokens yyyy, MM, dd, HH, mm, ss, computed from the
 * unix epoch so every platform agrees (no locale, no timezone database).
 */
@OptIn(ExperimentalTime::class)
object UtcFormat {
    fun format(
        pattern: String,
        instant: Instant,
    ): String {
        val epochSeconds = instant.epochSeconds
        val days = floorDiv(epochSeconds, 86400)
        val secs = floorMod(epochSeconds, 86400)
        val (year, month, day) = civilFromDays(days)
        val hour = secs / 3600
        val minute = secs % 3600 / 60
        val second = secs % 60
        return pattern
            .replace("yyyy", year.toString().padStart(4, '0'))
            .replace("MM", month.toString().padStart(2, '0'))
            .replace("dd", day.toString().padStart(2, '0'))
            .replace("HH", hour.toString().padStart(2, '0'))
            .replace("mm", minute.toString().padStart(2, '0'))
            .replace("ss", second.toString().padStart(2, '0'))
    }

    private fun floorDiv(
        a: Long,
        b: Long,
    ): Long {
        var q = a / b
        if (a % b != 0L && (a < 0) != (b < 0)) q--
        return q
    }

    private fun floorMod(
        a: Long,
        b: Long,
    ): Long {
        val m = a % b
        return if (m < 0) m + b else m
    }

    /** Howard Hinnant's civil_from_days: days since 1970-01-01 → (year, month, day), proleptic Gregorian. */
    private fun civilFromDays(z: Long): Triple<Int, Int, Int> {
        val shifted = z + 719468
        val era = floorDiv(shifted, 146097)
        val dayOfEra = shifted - era * 146097
        val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36524 - dayOfEra / 146096) / 365
        val year = yearOfEra + era * 400
        val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        val mp = (5 * dayOfYear + 2) / 153
        val day = dayOfYear - (153 * mp + 2) / 5 + 1
        val month = if (mp < 10) mp + 3 else mp - 9
        return Triple((if (month <= 2) year + 1 else year).toInt(), month.toInt(), day.toInt())
    }
}
