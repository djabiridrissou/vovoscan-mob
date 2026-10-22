package com.vovoscan.feature.catalog.presentation

import com.vovoscan.core.common.StaleReason
import com.vovoscan.feature.catalog.domain.CatalogFilter

/** A visible source, in the reader's order (from the Sources screen, wired by the app). */
data class CatalogSource(
    val key: String,
    val name: String,
)

/** A source that couldn't be read at the last reload, and why. */
data class SourceProblem(
    val sourceKey: String,
    val sourceName: String,
    val reason: StaleReason,
)

/** Which sources the catalog reads: pure rules over the visible sources and the remembered filter. */
object CatalogSelection {
    /** The filter's sources still visible, in the reader's order; every visible source for "All" (or none left). */
    fun selected(
        visible: List<CatalogSource>,
        filter: CatalogFilter,
    ): List<CatalogSource> {
        val chosen = visible.filter { it.key in filter.sourceKeys }
        return chosen.ifEmpty { visible }
    }

    fun isAll(
        visible: List<CatalogSource>,
        filter: CatalogFilter,
    ): Boolean = visible.none { it.key in filter.sourceKeys }

    /**
     * Tapping a source in the filter: from "All", that source alone; otherwise it is added or removed. No source left,
     * or every visible source chosen, is "All" again (a source shown later then joins in).
     */
    fun toggle(
        visible: List<CatalogSource>,
        filter: CatalogFilter,
        key: String,
    ): CatalogFilter {
        if (visible.none { it.key == key }) return filter
        if (isAll(visible, filter)) return filter.copy(sourceKeys = listOf(key))
        val current = selected(visible, filter).map { it.key }.toSet()
        val next = if (key in current) current - key else current + key
        val ordered = visible.map { it.key }.filter { it in next }
        return filter.copy(sourceKeys = if (ordered.isEmpty() || ordered.size == visible.size) emptyList() else ordered)
    }

    /** "All sources", "Project Gutenberg", "2 sources". */
    fun label(
        visible: List<CatalogSource>,
        filter: CatalogFilter,
    ): String {
        if (isAll(visible, filter)) return ALL_SOURCES
        val selected = selected(visible, filter)
        return if (selected.size == 1) selected.single().name else "${selected.size} sources"
    }

    const val ALL_SOURCES = "All sources"
    const val ALL_CATEGORIES = "All categories"
}

/** The one notice line of the catalog, whatever number of sources failed. */
object CatalogNotices {
    /**
     * No network at all: the offline line. One source read and failing: its own line, as before ("Project Gutenberg
     * isn't answering right now. Showing what's saved.", "This source couldn't be read…"). Several sources: the
     * failing ones by name; the others' titles still show.
     */
    fun of(
        problems: List<SourceProblem>,
        sourcesRead: Int,
        hasSaved: Boolean,
    ): String? {
        if (problems.isEmpty()) return null
        problems.firstOrNull { it.reason == StaleReason.Offline }?.let { return it.reason.notice(hasSaved) }
        if (sourcesRead <= 1) return problems.first().reason.notice(hasSaved)
        val down = problems.filter { it.reason is StaleReason.SourceDown }.map { it.sourceName }
        val unreadable = problems.filter { it.reason == StaleReason.SourceUnreadable }.map { it.sourceName }
        return listOfNotNull(
            down.takeIf { it.isNotEmpty() }?.let { "${names(it)} ${if (it.size == 1) "isn't" else "aren't"} answering right now." },
            unreadable.takeIf { it.isNotEmpty() }?.let {
                "${names(it)} couldn't be read. ${if (it.size == 1) "It" else "They"} may need an update."
            },
        ).joinToString(" ")
    }

    /** Search fell back to what is saved: no network, or these sources didn't answer. */
    fun search(
        offline: Boolean,
        unavailable: List<String>,
    ): String? =
        when {
            offline -> "Offline: searching titles saved on this device."
            unavailable.isNotEmpty() ->
                "${names(unavailable)} ${if (unavailable.size == 1) "isn't" else "aren't"} answering right now. Searching what's saved."
            else -> null
        }

    /** The selected sources that have no category of that name, left out while the category filter is on. */
    fun categoryLeftOut(
        category: String,
        missing: List<String>,
    ): String? =
        when (missing.size) {
            0 -> null
            1 -> "${missing.single()} has no “$category” category, so it's left out."
            else -> "${names(missing)} have no “$category” category, so they're left out."
        }

    /** "A", "A and B", "A, B and C". */
    fun names(list: List<String>): String =
        when (list.size) {
            0 -> ""
            1 -> list.single()
            else -> list.dropLast(1).joinToString(", ") + " and " + list.last()
        }
}
