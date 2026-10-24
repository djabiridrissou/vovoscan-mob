package com.vovoscan.feature.catalog.domain

import com.vovoscan.core.common.AppResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * A title of a series at one source: its name there and how many chapters it has (null: unknown). Only page content
 * makes two titles one series (the server compares the pages of imported chapters); a name never does.
 */
data class SeriesMember(
    val sourceKey: String,
    val titleRef: String,
    val name: String,
    val chapters: Int?,
) {
    val key: String get() = "$sourceKey/$titleRef"
}

/** One series across sources. [members] come in the server's order: the primary first (the most chapters, ours on a tie). */
data class Series(
    val id: String,
    val members: List<SeriesMember>,
)

/**
 * The series titles belong to (`GET api/v1/series/resolve`), kept on the device for offline display. A title in no
 * series is in none of the answers. Callers only ask for a source read on the device what the server already knows
 * from this account (followed, rated, a typed search's results): browsing never tells it what a source lists.
 */
interface SeriesRepository {
    /** Every series known, by the [MarkedTitle.key] of each title asked about. */
    fun observe(): Flow<Map<String, Series>>

    /** Asks the server (100 titles a call) and keeps the answers: a title no longer in a series is forgotten. */
    suspend fun resolve(titles: List<MarkedTitle>): AppResult<Unit>

    companion object {
        val None: SeriesRepository =
            object : SeriesRepository {
                override fun observe(): Flow<Map<String, Series>> = flowOf(emptyMap())

                override suspend fun resolve(titles: List<MarkedTitle>) = AppResult.Ok(Unit)
            }
    }
}

/**
 * Port: the sources this account turned on, key → name, in its order (wired in `shared` to the sources slice). A series
 * member at a source that is off is left out.
 */
fun interface EnabledSources {
    fun observe(): Flow<Map<String, String>>

    companion object {
        val All = EnabledSources { flowOf(emptyMap()) }
    }
}

/** A series folded into one item: the item shown and how many other sources carry the series. */
data class Folded<T>(
    val item: T,
    val otherSources: Int,
)

object SeriesMerge {
    /**
     * One item per series, in order: of the items of a series on screen, the one whose title comes first in the
     * series' order (the primary when it is there) stands for it, at its own place; [otherSources] counts the series'
     * other members at a source in [enabled] (every source when it is empty), shown or not. Items in no series stay.
     */
    fun <T> fold(
        items: List<T>,
        key: (T) -> String,
        series: Map<String, Series>,
        enabled: Set<String>,
    ): List<Folded<T>> {
        val shown = items.mapTo(HashSet(), key)

        fun on(member: SeriesMember) = enabled.isEmpty() || member.sourceKey in enabled
        val standing =
            items
                .mapNotNull { series[key(it)] }
                .distinctBy { it.id }
                .associate { s -> s.id to s.members.firstOrNull { it.key in shown && on(it) }?.key }
        return items.mapNotNull { item ->
            val s = series[key(item)] ?: return@mapNotNull Folded(item, 0)
            val stands = standing[s.id]
            // A member at a source that is off doesn't stand for the series (it shows as it is when nothing else does).
            when {
                stands == null -> Folded(item, 0)
                stands != key(item) -> null
                else -> Folded(item, s.members.count { on(it) && it.key != stands })
            }
        }
    }

    /**
     * A series' chapters merged by number: each number once, from [own] (the title page's source) when it has it, else
     * from the first member (series order) that does. Newest to oldest, like a title page.
     */
    fun chapters(
        own: SeriesMember,
        lists: List<Pair<SeriesMember, List<Chapter>>>,
    ): List<Pair<SeriesMember, Chapter>> {
        val ordered = lists.sortedBy { (member, _) -> if (member.key == own.key) 0 else 1 }
        val byNumber = LinkedHashMap<Double, Pair<SeriesMember, Chapter>>()
        ordered.forEach { (member, chapters) -> chapters.forEach { byNumber.getOrPut(it.number) { member to it } } }
        return byNumber.values.sortedByDescending { it.second.number }
    }
}
