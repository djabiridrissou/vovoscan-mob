package com.vovoscan.feature.catalog.presentation

import com.vovoscan.core.common.i18n.LocalFormat
import com.vovoscan.core.common.i18n.t
import com.vovoscan.feature.catalog.domain.TitleStats

/** Everyone's numbers and the stars, as the cards and the title page say them (favourites and ratings contract). */
object MarkText {
    /** 950, 1.2k, 46k, 2M (the locale's decimal separator). */
    fun compactCount(n: Int): String =
        when {
            n < 1_000 -> LocalFormat.integer(n.toLong())
            n < 1_000_000 -> t("marks.thousands", "n" to oneDecimal(n / 1_000.0, below = 10.0))
            else -> t("marks.millions", "n" to oneDecimal(n / 1_000_000.0, below = 10.0))
        }

    /** One decimal under [below] (cut, never rounded up to a higher figure), none when whole or above. */
    private fun oneDecimal(
        value: Double,
        below: Double,
    ): String {
        val tenths = kotlin.math.floor(value * 10.0) / 10.0
        return if (value >= below ||
            tenths % 1.0 == 0.0
        ) {
            LocalFormat.integer(kotlin.math.floor(value).toLong())
        } else {
            LocalFormat.decimal(tenths, 1)
        }
    }

    /** "★ 4.3 (12)", only when both are known (3 accounts or more). */
    fun ratingLine(stats: TitleStats?): String? {
        val avg = stats?.ratingAvg ?: return null
        val count = stats.ratingCount ?: return null
        return t("marks.ratingLine", "avg" to LocalFormat.decimal(avg, 1), "count" to compactCount(count))
    }

    /** "♥ 30", only when known and above zero. */
    fun favoriteLine(stats: TitleStats?): String? =
        stats?.favoriteCount?.takeIf { it > 0 }?.let { t("marks.favoriteLine", "count" to compactCount(it)) }

    /** "Rated 4.3 out of 5 by 12 readers", for screen readers. */
    fun ratedBy(stats: TitleStats?): String? {
        val avg = stats?.ratingAvg ?: return null
        val count = stats.ratingCount ?: return null
        return t("marks.ratedBy", "avg" to LocalFormat.decimal(avg, 1), "count" to count)
    }

    /** "4.5 out of 5 stars", or "Not rated". */
    fun stars(score: Double?): String =
        score?.let { t("marks.stars", "score" to LocalFormat.decimal(it, if (it % 1.0 == 0.0) 0 else 1)) } ?: t("marks.noRating")
}
