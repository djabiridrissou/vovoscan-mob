package com.vovoscan.feature.catalog.domain

/**
 * The catalog of several sources as one grid, fairly: page 1 of every source interleaved one title at a time (in the
 * reader's order of sources), then page 2 of every source, and so on. A big source never drowns the others, and
 * loading more appends a new round without moving what is already on screen.
 */
object CatalogMerge {
    /** `listings`: one per source, in the reader's order. */
    fun merge(listings: List<List<ListedTitle>>): List<TitleSummary> {
        val lastPage = listings.maxOfOrNull { listing -> listing.maxOfOrNull { it.page } ?: 0 } ?: 0
        return (1..lastPage).flatMap { page ->
            roundRobin(listings.map { listing -> listing.filter { it.page == page }.map { it.title } })
        }
    }

    /** The first of each list, then the second of each, …; a shorter list simply runs out. */
    fun <T> roundRobin(lists: List<List<T>>): List<T> {
        val longest = lists.maxOfOrNull { it.size } ?: 0
        return (0 until longest).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }
    }
}

/** A category offered across the selected sources: one name, and each source's own id for it. */
data class CategoryOption(
    val name: String,
    /** Source key → that source's category id, in the reader's order of sources. */
    val ids: Map<String, String>,
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
        for ((sourceKey, list) in categories) {
            for (category in list) {
                val name = category.name.trim()
                if (name.isEmpty()) continue
                val key = name.lowercase()
                names.getOrPut(key) { name }
                ids.getOrPut(key) { linkedMapOf() }.getOrPut(sourceKey) { category.id }
            }
        }
        return names.map { (key, name) -> CategoryOption(name, ids.getValue(key)) }
    }

    /** The option of that name (case-insensitive), if the selected sources have it. */
    fun find(
        options: List<CategoryOption>,
        name: String?,
    ): CategoryOption? = name?.trim()?.lowercase()?.let { wanted -> options.firstOrNull { it.name.lowercase() == wanted } }
}
