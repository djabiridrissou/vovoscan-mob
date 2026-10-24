package com.vovoscan.feature.catalog.domain

import com.vovoscan.core.common.AppResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Everyone's numbers on a title: the average rating and how many rated it, how many gave it the heart. Each is null
 * below 3 accounts (the server's k-anonymity, like search): nothing is shown then, never "1 rating".
 */
data class TitleStats(
    val ratingAvg: Double? = null,
    val ratingCount: Int? = null,
    val favoriteCount: Int? = null,
)

/** A title on screen, as the marks know it. */
data class MarkedTitle(
    val sourceKey: String,
    val id: String,
) {
    val key: String get() = "$sourceKey/$id"
}

/**
 * The account's own ratings (1 to 5 stars, half steps), offline first like the library: a rating applies at once and
 * waits in a queue; [sync] sends the queue (last write wins on the device time), then pulls the ratings given on the
 * account's other devices. Everyone's numbers ([TitleStats]) are kept for offline display.
 */
interface RatingsRepository {
    /** The account's ratings, by [MarkedTitle.key]. */
    fun observeRatings(): Flow<Map<String, Double>>

    /** Everyone's numbers last read, by [MarkedTitle.key]. */
    fun observeStats(): Flow<Map<String, TitleStats>>

    /**
     * Everyone's numbers for these titles (`POST ratings/stats`, 100 a call). It tells the server which titles are on
     * screen: callers only ask for a source read on the device what the server already knows (search results, the
     * library, a title rated) — browsing a source never says what it lists.
     */
    suspend fun refreshStats(titles: List<MarkedTitle>): AppResult<Unit>

    /**
     * Rates the title ([score] 1..5 in half steps) or takes the rating back (null). Applied at once; Err only when the
     * server refused it for good (the rating is then put back as it was); offline it waits and the answer is Ok.
     */
    suspend fun rate(
        title: MarkedTitle,
        titleName: String,
        score: Double?,
    ): AppResult<Unit>

    suspend fun sync(): AppResult<Unit>

    suspend fun clearLocal()

    /** Nothing rated, no numbers: screens built without the ratings. */
    companion object {
        val None: RatingsRepository =
            object : RatingsRepository {
                override fun observeRatings(): Flow<Map<String, Double>> = flowOf(emptyMap())

                override fun observeStats(): Flow<Map<String, TitleStats>> = flowOf(emptyMap())

                override suspend fun refreshStats(titles: List<MarkedTitle>) = AppResult.Ok(Unit)

                override suspend fun rate(
                    title: MarkedTitle,
                    titleName: String,
                    score: Double?,
                ) = AppResult.Ok(Unit)

                override suspend fun sync() = AppResult.Ok(Unit)

                override suspend fun clearLocal() = Unit
            }
    }
}

/** The library's view of the titles: those with the heart, and those followed (both by [MarkedTitle.key]). */
data class LibraryMarks(
    val favorites: Set<String> = emptySet(),
    val followed: Set<String> = emptySet(),
)

/**
 * Port: the heart, kept by the library (wired in `shared`; the catalog does not know that feature). Giving it follows
 * the title; Err when the server refused it for good (the heart is then back as it was).
 */
interface Favorites {
    fun observe(): Flow<LibraryMarks>

    suspend fun set(
        title: MarkedTitle,
        name: String,
        coverUrl: String?,
        favorite: Boolean,
    ): AppResult<Unit>

    companion object {
        val None: Favorites =
            object : Favorites {
                override fun observe(): Flow<LibraryMarks> = flowOf(LibraryMarks())

                override suspend fun set(
                    title: MarkedTitle,
                    name: String,
                    coverUrl: String?,
                    favorite: Boolean,
                ) = AppResult.Ok(Unit)
            }
    }
}

object Stars {
    const val MIN = 1.0
    const val MAX = 5.0

    /**
     * The score a tap or a drag gives: [x] across the five stars, 0..1; the left half of a star is its half (2.3 stars
     * across = 2.5), never below one star.
     */
    fun at(x: Double): Double {
        val raw = x.coerceIn(0.0, 1.0) * MAX
        val halves = kotlin.math.ceil(raw * 2.0) / 2.0
        return halves.coerceIn(MIN, MAX)
    }

    /** A score the server takes: 1 to 5, half steps. */
    fun valid(score: Double): Boolean = score in MIN..MAX && (score * 2.0) % 1.0 == 0.0

    /** Each star's fill for [score]: 1 full, 0.5 half, 0 empty. */
    fun fills(score: Double?): List<Double> =
        (1..5).map { star ->
            val left = (score ?: 0.0) - (star - 1)
            when {
                left >= 1.0 -> 1.0
                left >= 0.5 -> 0.5
                else -> 0.0
            }
        }

    /** One step up or down (screen readers' adjustable action), from nothing to one star and back. */
    fun step(
        score: Double?,
        up: Boolean,
    ): Double? =
        when {
            score == null -> if (up) MIN else null
            up -> (score + 0.5).coerceAtMost(MAX)
            score <= MIN -> null
            else -> score - 0.5
        }
}
