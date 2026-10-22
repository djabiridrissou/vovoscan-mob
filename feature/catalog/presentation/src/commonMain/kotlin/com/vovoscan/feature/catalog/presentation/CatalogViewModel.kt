package com.vovoscan.feature.catalog.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.Freshness
import com.vovoscan.core.common.StaleReason
import com.vovoscan.core.common.userMessage
import com.vovoscan.feature.catalog.domain.CatalogFilter
import com.vovoscan.feature.catalog.domain.CatalogMerge
import com.vovoscan.feature.catalog.domain.CatalogRepository
import com.vovoscan.feature.catalog.domain.CategoryOption
import com.vovoscan.feature.catalog.domain.CategoryUnion
import com.vovoscan.feature.catalog.domain.ContentKind
import com.vovoscan.feature.catalog.domain.ReadingFormat
import com.vovoscan.feature.catalog.domain.SourceCategory
import com.vovoscan.feature.catalog.domain.TitleSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A catalog card, ready to display. */
data class TitleCard(
    val sourceKey: String,
    /** Small badge under the format badge: where the title comes from. */
    val sourceName: String,
    val id: String,
    val name: String,
    val coverUrl: String?,
    /** "WEBTOON", "MANGA" or "BOOK": badge on the cover. */
    val formatLabel: String,
    val status: String,
)

/** A row of the source filter's sheet. */
data class SourceOption(
    val key: String,
    val name: String,
    val selected: Boolean,
)

/** A choice of the category filter (the union, by name, of the selected sources' categories). */
data class CategoryChoice(
    val name: String,
    val selected: Boolean,
)

data class CatalogUiState(
    /** The visible sources, in the reader's order; `selected` as the filter has it ("All": every one). */
    val sources: List<SourceOption> = emptyList(),
    val allSources: Boolean = true,
    /** "All sources", "Project Gutenberg", "2 sources". */
    val sourceFilterLabel: String = CatalogSelection.ALL_SOURCES,
    val categories: List<CategoryChoice> = emptyList(),
    /** The category in use (its name), null for every category. */
    val category: String? = null,
    /** Sources left out by the category filter ("Project Gutenberg has no “Webtoon” category, so it's left out."). */
    val categoryNote: String? = null,
    /** Every source read now, merged round by round. */
    val cards: List<TitleCard> = emptyList(),
    val query: String = "",
    /** Search results; null when not searching (the catalog shows). */
    val results: List<TitleCard>? = null,
    /** Search fell back to saved titles: the line saying why (no network, or which sources didn't answer). */
    val searchNotice: String? = null,
    val freshness: Freshness = Freshness.Refreshing,
    /** The sources that failed at the last reload (offline, not answering, unreadable); the others still show. */
    val problems: List<SourceProblem> = emptyList(),
    /** How many sources are read now (with the category filter on, only those that have it). */
    val sourcesRead: Int = 0,
    val loadingMore: Boolean = false,
    val canLoadMore: Boolean = false,
    val error: String? = null,
) {
    val visible: List<TitleCard> get() = results ?: cards

    /** The source filter shows once there is a choice to make. */
    val showSourceFilter: Boolean get() = sources.size > 1

    val showCategoryFilter: Boolean get() = categories.isNotEmpty()

    val categoryFilterLabel: String get() = category ?: CatalogSelection.ALL_CATEGORIES

    /** Nothing to show, once the sources have been read (sources that failed: the notice says so instead). */
    val empty: Boolean
        get() = visible.isEmpty() && freshness != Freshness.Refreshing && (problems.isEmpty() || results != null)

    val emptyMessage: String
        get() =
            when {
                results != null -> "No title matches “$query”."
                category != null -> "No titles in “$category” yet."
                else -> "No titles yet. Check back soon."
            }

    /**
     * One discreet line when something couldn't be read: no network ("Offline: …"), a source that isn't answering
     * ("Project Gutenberg isn't answering right now."), or rules that can't read it. The other sources still show.
     */
    val offlineNotice: String?
        get() = if (results != null && searchNotice != null) searchNotice else CatalogNotices.of(problems, sourcesRead, cards.isNotEmpty())

    /** A source that didn't answer: "Retry" next to the notice (sends `Refresh`). */
    val canRetry: Boolean get() = !(results != null && searchNotice != null) && problems.any { it.reason.canRetry }
}

sealed interface CatalogIntent {
    data object Refresh : CatalogIntent

    data object LoadMore : CatalogIntent

    data class Query(
        val value: String,
    ) : CatalogIntent

    /** Visible sources, in the reader's order (from the Sources screen). */
    data class SourcesChanged(
        val sources: List<CatalogSource>,
    ) : CatalogIntent

    /** "All" in the source filter. */
    data object ShowAllSources : CatalogIntent

    /** A source of the filter's list: from "All", that source alone; otherwise added or removed. */
    data class ToggleSource(
        val key: String,
    ) : CatalogIntent

    /** That source alone. */
    data class SelectSource(
        val key: String,
    ) : CatalogIntent

    /** A category by name; null for every category. */
    data class SelectCategory(
        val name: String?,
    ) : CatalogIntent
}

/**
 * The catalog of the selected sources ("All": every visible source) as one grid, offline first: each source's local
 * listing at once, merged round by round (`CatalogMerge`), then reloaded source by source; a source that fails says
 * so in one line and the others still show. Search goes to every source read, with a short typing debounce. The
 * filter (sources, category) is remembered on the device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogViewModel(
    private val repository: CatalogRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CatalogUiState())
    val state: StateFlow<CatalogUiState> = mutableState.asStateFlow()

    /** What is read: a source, and its own category id when the category filter is on. */
    private data class Reading(
        val source: CatalogSource,
        val categoryId: String?,
    )

    private data class Plan(
        val visible: List<CatalogSource>,
        val filter: CatalogFilter,
        val selected: List<CatalogSource>,
        val options: List<CategoryOption>,
        val category: CategoryOption?,
    ) {
        val readings: List<Reading>
            get() =
                if (category == null) {
                    selected.map { Reading(it, null) }
                } else {
                    selected.mapNotNull { source -> category.ids[source.key]?.let { Reading(source, it) } }
                }
    }

    private val visible = MutableStateFlow<List<CatalogSource>?>(null)
    private val filter = MutableStateFlow<CatalogFilter?>(null)
    private var plan: Plan? = null
    private var readings: List<Reading> = emptyList()
    private var hasMore: Map<String, Boolean> = emptyMap()
    private var generation = 0
    private var searchJob: Job? = null
    private var listingsJob: Job? = null
    private val categoriesAsked = mutableSetOf<String>()

    init {
        viewModelScope.launch { filter.value = repository.observeFilter().first() }
        val selection =
            combine(visible.filterNotNull(), filter.filterNotNull()) { v, f -> Triple(v, f, CatalogSelection.selected(v, f)) }
        selection
            .flatMapLatest { (v, f, selected) -> categoriesOf(selected).map { Plan(v, f, selected, emptyList(), null).resolve(it) } }
            .distinctUntilChanged()
            .onEach(::apply)
            .launchIn(viewModelScope)
    }

    fun onIntent(intent: CatalogIntent) {
        when (intent) {
            CatalogIntent.Refresh -> refresh(categoriesToo = true)
            CatalogIntent.LoadMore -> loadMore()
            is CatalogIntent.Query -> search(intent.value)
            // Unknown until the Sources screen's list is read (it always keeps one source visible).
            is CatalogIntent.SourcesChanged -> if (intent.sources.isNotEmpty()) visible.value = intent.sources
            CatalogIntent.ShowAllSources -> changeFilter { it.copy(sourceKeys = emptyList()) }
            is CatalogIntent.ToggleSource -> changeFilter { CatalogSelection.toggle(visible.value.orEmpty(), it, intent.key) }
            is CatalogIntent.SelectSource ->
                changeFilter { f ->
                    val all = visible.value.orEmpty()
                    if (all.size == 1 &&
                        all.single().key == intent.key
                    ) {
                        f.copy(sourceKeys = emptyList())
                    } else {
                        f.copy(sourceKeys = listOf(intent.key))
                    }
                }
            is CatalogIntent.SelectCategory -> changeFilter { it.copy(categoryName = intent.name?.trim()?.takeIf { n -> n.isNotEmpty() }) }
        }
    }

    private fun changeFilter(change: (CatalogFilter) -> CatalogFilter) {
        val current = filter.value ?: CatalogFilter()
        val next = change(current)
        if (next == current) return
        filter.value = next
        viewModelScope.launch { repository.saveFilter(next) }
    }

    private fun categoriesOf(selected: List<CatalogSource>): Flow<List<Pair<String, List<SourceCategory>>>> =
        if (selected.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(selected.map { source -> repository.observeCategories(source.key).map { source.key to it } }) { it.toList() }
        }

    private fun Plan.resolve(categories: List<Pair<String, List<SourceCategory>>>): Plan {
        val union = CategoryUnion.of(categories)
        return copy(options = union, category = CategoryUnion.find(union, filter.categoryName))
    }

    /** A new plan: the filters' rows, and when what is read changed, the listings to show and reload. */
    private fun apply(next: Plan) {
        plan = next
        val names = next.visible.associate { it.key to it.name }
        val missing =
            next.category?.let { category -> next.selected.filter { it.key !in category.ids }.map { it.name } }.orEmpty()
        mutableState.update {
            it.copy(
                sources =
                    next.visible.map { s ->
                        SourceOption(
                            s.key,
                            s.name,
                            s in next.selected && !CatalogSelection.isAll(next.visible, next.filter),
                        )
                    },
                allSources = CatalogSelection.isAll(next.visible, next.filter),
                sourceFilterLabel = CatalogSelection.label(next.visible, next.filter),
                categories = next.options.map { o -> CategoryChoice(o.name, o.name == next.category?.name) },
                category = next.category?.name,
                categoryNote = next.category?.let { c -> CatalogNotices.categoryLeftOut(c.name, missing) },
            )
        }
        // Categories are asked once per source while the screen lives (and again on Refresh).
        next.selected.filter { categoriesAsked.add(it.key) }.forEach { source ->
            viewModelScope.launch { repository.refreshCategories(source.key) }
        }
        val nextReadings = next.readings
        if (nextReadings == readings) return
        readings = nextReadings
        hasMore = emptyMap()
        listingsJob?.cancel()
        listingsJob =
            mergedListings(nextReadings)
                .onEach { titles -> mutableState.update { it.copy(cards = titles.map { t -> card(t, names) }) } }
                .launchIn(viewModelScope)
        mutableState.update { it.copy(sourcesRead = nextReadings.size, problems = emptyList(), canLoadMore = false) }
        refresh(categoriesToo = false)
        if (state.value.query.isNotBlank()) search(state.value.query)
    }

    private fun mergedListings(list: List<Reading>): Flow<List<TitleSummary>> =
        if (list.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(
                list.map { repository.observeListing(it.source.key, it.categoryId) },
            ) { listings -> CatalogMerge.merge(listings.toList()) }
        }

    private fun refresh(categoriesToo: Boolean) {
        val current = readings
        if (categoriesToo) {
            plan?.selected?.forEach { source -> viewModelScope.launch { repository.refreshCategories(source.key) } }
        }
        if (current.isEmpty()) return
        val run = ++generation
        mutableState.update { it.copy(freshness = Freshness.Refreshing, error = null) }
        viewModelScope.launch {
            val results =
                current
                    .map { reading -> async { reading to repository.refreshListing(reading.source.key, reading.categoryId) } }
                    .awaitAll()
            if (run != generation) return@launch
            hasMore = results.associate { (reading, result) -> reading.source.key to ((result as? AppResult.Ok)?.value == true) }
            mutableState.update { it.after(results, replacing = current.map { r -> r.source.key }.toSet()) }
        }
    }

    private fun loadMore() {
        val current = state.value
        if (!current.canLoadMore || current.loadingMore || current.results != null) return
        val more = readings.filter { hasMore[it.source.key] == true }
        if (more.isEmpty()) return
        val run = generation
        mutableState.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            // Every source with pages left gives its next page: one more round of the merged grid.
            val results =
                more.map { reading -> async { reading to repository.loadMoreListing(reading.source.key, reading.categoryId) } }.awaitAll()
            if (run != generation) return@launch
            hasMore = hasMore + results.associate { (reading, result) -> reading.source.key to ((result as? AppResult.Ok)?.value == true) }
            mutableState.update { it.after(results, replacing = more.map { r -> r.source.key }.toSet()).copy(loadingMore = false) }
        }
    }

    private fun search(query: String) {
        mutableState.update { it.copy(query = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            mutableState.update { it.copy(results = null, searchNotice = null) }
            return
        }
        val current = readings
        val names = plan?.visible.orEmpty().associate { it.key to it.name }
        searchJob =
            viewModelScope.launch {
                delay(SEARCH_DEBOUNCE_MS)
                val found = current.map { reading -> async { repository.search(reading.source.key, query, reading.categoryId) } }.awaitAll()
                val titles = CatalogMerge.roundRobin(found.map { it.titles })
                mutableState.update {
                    it.copy(
                        results = titles.map { t -> card(t, names) },
                        searchNotice =
                            CatalogNotices.search(
                                offline = found.any { r -> r.offline && r.unavailableSource == null },
                                unavailable = found.mapNotNull { r -> r.unavailableSource.takeIf { r.offline } },
                            ),
                    )
                }
            }
    }

    /** After reloading these sources: their problems replace the ones they had, the others' stay. */
    private fun CatalogUiState.after(
        results: List<Pair<Reading, AppResult<Boolean>>>,
        replacing: Set<String>,
    ): CatalogUiState {
        val failed = results.mapNotNull { (reading, result) -> (result as? AppResult.Err)?.let { reading to it.error } }
        val newProblems =
            failed.mapNotNull { (reading, error) ->
                StaleReason.of(error)?.let { SourceProblem(reading.source.key, reading.source.name, it) }
            }
        val other = failed.firstOrNull { (_, error) -> StaleReason.of(error) == null }
        val problems = this.problems.filter { it.sourceKey !in replacing } + newProblems
        return copy(
            freshness = if (problems.isEmpty()) Freshness.Fresh else Freshness.Offline,
            problems = problems,
            canLoadMore = hasMore.values.any { it },
            // Anything else (not a source problem): an error line, naming the source when several are read.
            error =
                other?.let { (reading, error) ->
                    if (readings.size > 1) "${reading.source.name}: ${error.userMessage()}" else error.userMessage()
                },
        )
    }

    private fun card(
        title: TitleSummary,
        names: Map<String, String>,
    ) = TitleCard(
        sourceKey = title.sourceKey,
        sourceName = names[title.sourceKey] ?: title.sourceKey,
        id = title.id,
        name = title.name,
        coverUrl = title.coverUrl,
        formatLabel =
            when {
                title.kind == ContentKind.Book -> "BOOK"
                title.format == ReadingFormat.Webtoon -> "WEBTOON"
                else -> "MANGA"
            },
        status = title.status.replaceFirstChar { it.uppercase() },
    )

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}
