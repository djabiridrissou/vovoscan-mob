package com.vovoscan.feature.reader.domain

/**
 * A title's place on the account (backend `reading-history`), as another device left it. [read] null: the title was
 * taken out of the history. [updatedAt]: the time of that move (ms), what the next pull starts after.
 */
data class AccountPlace(
    val sourceKey: String,
    val titleId: String,
    val read: LastRead?,
    val updatedAt: Long,
)

/**
 * The account's reading history (reader/SPEC.md §9): every title read, followed or not, so "Continue reading" is the
 * same on the web and on every device. A port wired by the app to the API; the device's history is its cache.
 */
interface HistorySync {
    /** Whether the place is settled: taken, or refused for good (a source gone, an older server). False: send it again. */
    suspend fun push(read: LastRead): Boolean

    /** Changes since [sinceMillis] (null: everything), tombstones included; null when unknown (offline). */
    suspend fun pull(sinceMillis: Long?): List<AccountPlace>?

    companion object {
        val None =
            object : HistorySync {
                override suspend fun push(read: LastRead) = false

                override suspend fun pull(sinceMillis: Long?): List<AccountPlace>? = null
            }
    }
}

/** What the device writes from the account's places: [put] replaces the device's place, [remove] forgets it. */
data class HistoryChanges(
    val put: List<LastRead>,
    val remove: List<String>,
)

object HistoryMerge {
    /**
     * The account's [places] against the device's [local] history (by [LastRead.key]): a place newer than the device's
     * wins, a tombstone newer than the device's read forgets it; a device read newer than both stays (and is sent).
     */
    fun apply(
        local: Map<String, LastRead>,
        places: List<AccountPlace>,
    ): HistoryChanges {
        val put = mutableListOf<LastRead>()
        val remove = mutableListOf<String>()
        places.groupBy { "${it.sourceKey}/${it.titleId}" }.forEach { (key, all) ->
            val newest = all.maxBy { it.updatedAt }
            val mine = local[key]
            if (mine != null && mine.readAt >= newest.updatedAt) return@forEach
            when (val read = newest.read) {
                null -> if (mine != null) remove += key
                else -> put += read
            }
        }
        return HistoryChanges(put, remove)
    }
}

/** A title's name and cover as the device knows them: the account's history names a title read on the device. */
fun interface HistoryTitles {
    /** Null: the device has no name for it (the place is sent at the next sync, once the title page has loaded). */
    suspend fun title(
        sourceKey: String,
        titleId: String,
    ): HistoryTitle?
}

data class HistoryTitle(
    val name: String,
    val coverUrl: String?,
)
