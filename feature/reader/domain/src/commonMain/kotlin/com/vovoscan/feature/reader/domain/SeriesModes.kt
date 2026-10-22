package com.vovoscan.feature.reader.domain

/** The account's name of a mode (`long-strip`, `paged-rtl`…); a book has none (its mode is not a choice). */
val ReadingMode.wire: String?
    get() =
        when (this) {
            ReadingMode.LongStrip -> "long-strip"
            ReadingMode.LongStripGaps -> "long-strip-gaps"
            ReadingMode.PagedRightToLeft -> "paged-rtl"
            ReadingMode.PagedLeftToRight -> "paged-ltr"
            ReadingMode.PagedVertical -> "paged-vertical"
            ReadingMode.Book -> null
        }

fun readingModeOfWire(value: String): ReadingMode? = ReadingMode.comicModes.firstOrNull { it.wire == value }

/** A series on the account: `"<sourceKey>:<titleRef>"` (a source key never holds a colon; a title ref may). */
data class SeriesKey(
    val sourceKey: String,
    val titleId: String,
) {
    val wire: String get() = "$sourceKey:$titleId"

    companion object {
        fun of(wire: String): SeriesKey? {
            val colon = wire.indexOf(':')
            if (colon <= 0 || colon == wire.lastIndex) return null
            return SeriesKey(wire.substring(0, colon), wire.substring(colon + 1))
        }
    }
}

/**
 * The account's side of the modes chosen per series (`GET /settings/reading` → `seriesModes`,
 * `PUT /settings/reading/series-mode`), wired in `shared` to the account slice.
 */
interface SeriesModeSync {
    /** Every series' mode on the account; null when unknown (offline, an older server). */
    suspend fun pull(): Map<SeriesKey, ReadingMode>?

    /** Whether the account took it (null: back to the default). */
    suspend fun push(
        series: SeriesKey,
        mode: ReadingMode?,
    ): Boolean

    companion object {
        val None =
            object : SeriesModeSync {
                override suspend fun pull(): Map<SeriesKey, ReadingMode>? = null

                override suspend fun push(
                    series: SeriesKey,
                    mode: ReadingMode?,
                ) = false
            }
    }
}

/**
 * How the device's copy and the account meet (reader/SPEC.md §1). The device's series modes are a cache of the
 * account's: a choice made on the device and not sent yet ([pending]) goes to the account and stays; the account wins
 * everywhere else, a series it no longer has included (reset on another device); the device's choices from before the
 * account kept them are sent once ([migrated] false).
 */
object SeriesModeMerge {
    data class Plan(
        /** What the device's copy must hold for each series that changes (null: removed). */
        val local: Map<SeriesKey, ReadingMode?>,
        /** What to send to the account. */
        val push: Map<SeriesKey, ReadingMode?>,
    )

    fun plan(
        account: Map<SeriesKey, ReadingMode>,
        device: Map<SeriesKey, ReadingMode>,
        pending: Map<SeriesKey, ReadingMode?>,
        migrated: Boolean,
    ): Plan {
        val push = pending.toMutableMap()
        if (!migrated) device.forEach { (key, mode) -> if (key !in account && key !in pending) push[key] = mode }
        val local = mutableMapOf<SeriesKey, ReadingMode?>()
        account.forEach { (key, mode) -> if (key !in pending && device[key] != mode) local[key] = mode }
        device.keys.forEach { key -> if (key !in account && key !in push) local[key] = null }
        return Plan(local, push)
    }
}
