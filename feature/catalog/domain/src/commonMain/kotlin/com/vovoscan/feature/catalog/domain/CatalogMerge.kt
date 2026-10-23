package com.vovoscan.feature.catalog.domain

import kotlin.random.Random

/**
 * The catalog of several sources as one grid, mixed but fair: page 1 of every source shuffled together, then page 2 of
 * every source, and so on. Within a round each next title comes from a source drawn at random, weighted by how many
 * titles it has left in the round (a big source shows up more often without drowning the others), each source keeps
 * its own order (its best or newest first), and no source takes more than [MAX_RUN] places in a row while another
 * still has titles. The draw follows a seed: the same seed gives the same grid, so loading more appends a new round
 * without moving what is already on screen; a new seed (pull to refresh) gives a new mix.
 */
object CatalogMerge {
    const val MAX_RUN = 2

    /** `listings`: one per source, in the reader's order. */
    fun merge(
        listings: List<List<ListedTitle>>,
        seed: Long,
    ): List<TitleSummary> {
        val lastPage = listings.maxOfOrNull { listing -> listing.maxOfOrNull { it.page } ?: 0 } ?: 0
        return (1..lastPage).flatMap { page ->
            mix(listings.map { listing -> listing.filter { it.page == page }.map { it.title } }, Random(seed * 31 + page))
        }
    }

    /** Every element of `lists`, each list's order kept, the lists drawn as described above. */
    fun <T> mix(
        lists: List<List<T>>,
        random: Random,
    ): List<T> {
        val next = IntArray(lists.size)
        val out = ArrayList<T>(lists.sumOf { it.size })
        var last = -1
        var run = 0
        while (true) {
            val left = lists.indices.filter { next[it] < lists[it].size }
            if (left.isEmpty()) return out
            val allowed = if (run >= MAX_RUN && left.size > 1) left - last else left
            var draw = random.nextInt(allowed.sumOf { lists[it].size - next[it] })
            val source = allowed.first { i -> (draw < lists[i].size - next[i]).also { if (!it) draw -= lists[i].size - next[i] } }
            out += lists[source][next[source]++]
            run = if (source == last) run + 1 else 1
            last = source
        }
    }
}

/** A category offered across the selected sources: one name, and each source's own id for it. */
data class CategoryOption(
    val name: String,
    /** Source key → that source's category id, in the reader's order of sources. */
    val ids: Map<String, String>,
    /** The title format it stands for: a source without the category shows its titles of that format under it. */
    val format: ReadingFormat? = null,
)

object CategoryUnion {
    /**
     * The categories of the selected sources merged by name (trimmed, case-insensitive), in order of first
     * appearance (the reader's order of sources, then each source's own order). A source keeps its first category of
     * a given name.
     */
    fun of(categories: List<Pair<String, List<SourceCategory>>>): List<CategoryOption> {
        val names = linkedMapOf<String, String>()
        val ids = mutableMapOf<String, LinkedHashMap<String, String>>()
        val formats = mutableMapOf<String, ReadingFormat>()
        for ((sourceKey, list) in categories) {
            for (category in list) {
                val name = category.name.trim()
                if (name.isEmpty()) continue
                val key = name.lowercase()
                names.getOrPut(key) { name }
                ids.getOrPut(key) { linkedMapOf() }.getOrPut(sourceKey) { category.id }
                category.format?.let { formats.getOrPut(key) { it } }
            }
        }
        return names.map { (key, name) -> CategoryOption(name, ids.getValue(key), formats[key]) }
    }

    /**
     * The format categories first, whatever the sources and their order: one per entry of [formats] (in that order),
     * the sources' own category for it when one has it (its format, or its name, case-insensitive), else one that
     * files every source's titles by that format. Then the other categories, in [union]'s order.
     */
    fun formatsFirst(
        union: List<CategoryOption>,
        formats: List<Pair<ReadingFormat, String>>,
    ): List<CategoryOption> {
        val used = mutableSetOf<CategoryOption>()
        val first =
            formats.map { (format, name) ->
                val own =
                    union.firstOrNull { it.format == format && it !in used }
                        ?: union.firstOrNull { it.format == null && it.name.equals(name, ignoreCase = true) && it !in used }
                own?.also { used += it } ?: CategoryOption(name, emptyMap(), format)
            }
        return first + union.filter { it !in used }
    }

    /** The option of that name (case-insensitive), if the selected sources have it. */
    fun find(
        options: List<CategoryOption>,
        name: String?,
    ): CategoryOption? = name?.trim()?.lowercase()?.let { wanted -> options.firstOrNull { it.name.lowercase() == wanted } }
}
