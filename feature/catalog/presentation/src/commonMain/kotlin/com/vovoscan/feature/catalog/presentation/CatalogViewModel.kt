package com.vovoscan.feature.catalog.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.Freshness
import com.vovoscan.core.common.StaleReason
import com.vovoscan.core.common.i18n.t
import com.vovoscan.core.common.userMessage
import com.vovoscan.feature.catalog.domain.CatalogFilter
import com.vovoscan.feature.catalog.domain.CatalogMerge
import com.vovoscan.feature.catalog.domain.CatalogRepository
import com.vovoscan.feature.catalog.domain.CategoryOption
import com.vovoscan.feature.catalog.domain.CategoryUnion
import com.vovoscan.feature.catalog.domain.ContentKind
import com.vovoscan.feature.catalog.domain.ContentPolicy
import com.vovoscan.feature.catalog.domain.EpisodeFeature
import com.vovoscan.feature.catalog.domain.Favorites
import com.vovoscan.feature.catalog.domain.INTERNAL_SOURCE
import com.vovoscan.feature.catalog.domain.LibraryMarks
import com.vovoscan.feature.catalog.domain.MarkedTitle
import com.vovoscan.feature.catalog.domain.RatingsRepository
import com.vovoscan.feature.catalog.domain.ReadingFormat
import com.vovoscan.feature.catalog.domain.ReadingResume
import com.vovoscan.feature.catalog.domain.ResumePoint
import com.vovoscan.feature.catalog.domain.SearchPick
import com.vovoscan.feature.catalog.domain.SearchRepository
import com.vovoscan.feature.catalog.domain.SearchRules
import com.vovoscan.feature.catalog.domain.Series
import com.vovoscan.feature.catalog.domain.SeriesMerge
import com.vovoscan.feature.catalog.domain.SeriesRepository
import com.vovoscan.feature.catalog.domain.SourceCategory
import com.vovoscan.feature.catalog.domain.SuggestReason
import com.vovoscan.feature.catalog.domain.SuggestedTitle
import com.vovoscan.feature.catalog.domain.TitleDetail
import com.vovoscan.feature.catalog.domain.TitleStats
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
import kotlin.random.Random

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
    /** This account has started it: the card says so ("Reading"). */
    val started: Boolean = false,
    /** A search result readers who searched the same opened: the "Popular" badge. */
    val popular: Boolean = false,
    /** "▶ 3": chapters with an episode (red badge, top right of the cover); null without one or without `video`. */
    val episodeBadge: String? = null,
    /** In the favourites: the heart in the corner is red. */
    val favorite: Boolean = false,
    /** "★ 4.3 (12)" under the name, when 3 accounts or more rated it. */
    val ratingLine: String? = null,
    /** "♥ 30" under the name, when 3 accounts or more gave it the heart. */
    val favoriteLine: String? = null,
    /** "+ 2 sources": the series is also at other sources turned on (one card per series). */
    val moreSources: String? = null,
) {
    /** What the heart does, for screen readers: "Add to favourites" / "Remove from favourites". */
    val favoriteAction: String get() = t(if (favorite) "marks.favoriteRemove" else "marks.favoriteAdd")

    /**
     * The card's identity on screen, whatever its badges say: a card whose format, status or cover changes stays the
     * same view (iOS: the zoom transition's source tile must outlive the title page it opened).
     */
    val key: String get() = "$sourceKey/$id"
}

enum class SuggestionKind {
    /** This account's own search (↺): fills the field and runs it. */
    Recent,

    /** A search other readers made: fills the field and runs it. */
    Query,

    /** A title: opens it (and teaches the search what was meant). */
    Title,
}

/** A row under the search field. */
data class SuggestionRow(
    val kind: SuggestionKind,
    /** The search, or the title's name. */
    val text: String,
    /** A title's "Popular ✦ Project Gutenberg" / "You opened it ✦ vovoscan". */
    val detail: String? = null,
    val sourceKey: String? = null,
    val titleId: String? = null,
    val coverUrl: String? = null,
) {
    /** Stable id for the lists. */
    val key: String get() = if (kind == SuggestionKind.Title) "t/$sourceKey/$titleId" else "${kind.name}/$text"
}

/** "Your searches", "Others searched", "Titles". */
data class SuggestionSection(
    val kind: SuggestionKind,
    val title: String,
    val rows: List<SuggestionRow>,
)

/** "Continue: Title, Chapter 4": the last title read, opened where it was left. */
data class ResumeCard(
    val sourceKey: String,
    val titleId: String,
    val chapterId: String,
    val page: Int,
    val titleName: String,
    val coverUrl: String?,
    /** "Continue reading". */
    val label: String,
    /** "Chapter 4 · The Well ✦ page 12". */
    val detail: String,
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

/**
 * A section of the category sheet: the formats first (no [title]: Manga, Webtoon, Book), then each selected source's
 * own categories under its name, in the reader's order of sources.
 */
data class CategoryGroup(
    val title: String?,
    val choices: List<CategoryChoice>,
)

data class CatalogUiState(
    /** The visible sources, in the reader's order; `selected` as the filter has it ("All": every one). */
    val sources: List<SourceOption> = emptyList(),
    val allSources: Boolean = true,
    /** "All sources", "Project Gutenberg", "2 sources". */
    val sourceFilterLabel: String = CatalogSelection.ALL_SOURCES,
    val categories: List<CategoryChoice> = emptyList(),
    /** [categories] as the sheet shows them: the formats, then each source's own categories under its name. */
    val categoryGroups: List<CategoryGroup> = emptyList(),
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
    /** The titles started ("<sourceKey>/<id>"), for the cards' "Reading" mark. */
    val startedKeys: Set<String> = emptySet(),
    /** The last title read, on top of the catalog (not while searching). */
    val resume: ResumeCard? = null,
    /** Safe mode left titles out of what shows: one quiet line says so (the switch is in Settings). */
    val safeNote: String? = null,
    /** The search field has the focus: its suggestions show under it. */
    val searchFocused: Boolean = false,
    /** Under the search field: this account's searches, others' searches, titles (empty sections left out). */
    val suggestions: List<SuggestionSection> = emptyList(),
    /** The account has the `video` feature: the "▶ Episodes" chip shows next to the format filters. */
    val showEpisodesFilter: Boolean = false,
    /** Only titles with an episode (the chip is red). */
    val episodesOnly: Boolean = false,
    /** The titles with the heart ("<sourceKey>/<id>"). */
    val favoriteKeys: Set<String> = emptySet(),
    /** Everyone's numbers last read, by "<sourceKey>/<id>". */
    val stats: Map<String, TitleStats> = emptyMap(),
    /** "Could not update your favourites.": the heart was put back. */
    val favoriteError: String? = null,
    /** The series of titles on screen, by "<sourceKey>/<id>": one card per series. */
    val series: Map<String, Series> = emptyMap(),
) {
    /** "▶ Episodes". */
    val episodesLabel: String get() = t("catalog.episodesFilter")

    val showSuggestions: Boolean get() = searchFocused && suggestions.isNotEmpty()

    /** The badge of a result readers who searched the same opened. */
    val popularLabel: String get() = t("search.popular")

    val visible: List<TitleCard>
        get() =
            SeriesMerge
                .fold((results ?: cards).distinctBy { it.key }, { it.key }, series, sources.mapTo(HashSet()) { it.key })
                .map { folded ->
                    val it = folded.item
                    val numbers = stats[it.key]
                    // A series is one card: started or favourite at any of its sources.
                    val keys = series[it.key]?.members?.map { m -> m.key } ?: listOf(it.key)
                    it.copy(
                        started = it.started || keys.any { k -> k in startedKeys },
                        favorite = keys.any { k -> k in favoriteKeys },
                        ratingLine = MarkText.ratingLine(numbers),
                        favoriteLine = MarkText.favoriteLine(numbers),
                        moreSources = folded.otherSources.takeIf { n -> n > 0 }?.let { n -> t("catalog.moreSources", "count" to n) },
                    )
                }

    /** "Reading": the mark on a started title's card. */
    val startedLabel: String get() = t("catalog.started")

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
                results != null -> t("catalog.noMatch", "query" to query.trim())
                episodesOnly -> t("catalog.noEpisodes")
                category != null -> t("catalog.noneInCategory", "category" to category)
                else -> t("catalog.none")
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

    /** The heart on a card: on (the title is followed too) or off. Never opens the card. */
    data class ToggleFavorite(
        val sourceKey: String,
        val titleId: String,
    ) : CatalogIntent

    /** That source alone. */
    data class SelectSource(
        val key: String,
    ) : CatalogIntent

    /** A category by name; null for every category. */
    data class SelectCategory(
        val name: String?,
    ) : CatalogIntent

    /** The "▶ Episodes" chip: only titles with an episode, or every title again. */
    data object ToggleEpisodes : CatalogIntent

    /** The search field gained or lost the focus (its suggestions show while it has it). */
    data class SearchFocused(
        val focused: Boolean,
    ) : CatalogIntent

    /** A search under the field (this account's or others'): fills the field and runs it at once. */
    data class UseSearch(
        val text: String,
    ) : CatalogIntent

    /**
     * A title was opened from the grid or a suggestion. From search results or a suggested title, with something typed,
     * it teaches the search what was meant (a pick, sent in the background; nothing from plain browsing).
     */
    data class TitleOpened(
        val sourceKey: String,
        val titleId: String,
    ) : CatalogIntent
}

/**
 * The catalog of the selected sources ("All": every visible source) as one grid, offline first: each source's local
 * listing at once, mixed round by round (`CatalogMerge`), then reloaded source by source; a source that fails says
 * so in one line and the others still show. Search goes to every source read, with a short typing debounce. The
 * filter (sources, category) is remembered on the device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogViewModel(
    private val repository: CatalogRepository,
    private val resume: ReadingResume = ReadingResume { flowOf(emptyList()) },
    private val content: ContentPolicy = ContentPolicy.ShowAll,
    private val search: SearchRepository = SearchRepository.None,
    private val video: EpisodeFeature = EpisodeFeature.Off,
    private val favorites: Favorites = Favorites.None,
    private val ratings: RatingsRepository = RatingsRepository.None,
    private val seriesRepository: SeriesRepository = SeriesRepository.None,
) : ViewModel() {
    /** The account has the `video` feature: episode badges and the Episodes filter. */
    private var videoOn = false

    /** Safe mode off: every title shows. Follows the account (`content`). */
    private var showAdult = true
    private var hiddenInListing = false
    private val mutableState = MutableStateFlow(CatalogUiState())
    val state: StateFlow<CatalogUiState> = mutableState.asStateFlow()

    /**
     * What is read: a source, and its own category id when the category filter is on. A source without that category
     * whose titles carry the format the category stands for reads everything, keeping that [format].
     */
    private data class Reading(
        val source: CatalogSource,
        val categoryId: String?,
        val format: ReadingFormat? = null,
        /** Only titles with an episode: the server's listing (`has=episode`). */
        val episodes: Boolean = false,
    ) {
        fun keeps(title: TitleSummary): Boolean = (format == null || title.format == format) && (!episodes || title.episodeCount > 0)
    }

    private data class Plan(
        val visible: List<CatalogSource>,
        val filter: CatalogFilter,
        val selected: List<CatalogSource>,
        val options: List<CategoryOption>,
        val category: CategoryOption?,
        /** Each selected source's own categories, as read (the sheet groups them under the source). */
        val sourceCategories: List<Pair<String, List<SourceCategory>>> = emptyList(),
        /** The Episodes filter is on and the account has `video`. */
        val episodes: Boolean = false,
        /** The account has `video` (the chip and the badges show). */
        val video: Boolean = false,
    ) {
        val readings: List<Reading>
            get() {
                val all =
                    if (category == null) {
                        selected.map { Reading(it, null) }
                    } else {
                        selected.mapNotNull { source ->
                            category.ids[source.key]?.let { Reading(source, it) }
                                // Our catalog's Manga / Webtoon / Book: a source read on the device files its titles by format.
                                ?: category.format?.takeIf { source.key != INTERNAL_SOURCE }?.let { Reading(source, null, it) }
                        }
                    }
                if (!episodes) return all
                return all.mapNotNull { reading ->
                    when {
                        reading.source.key == INTERNAL_SOURCE || reading.categoryId == null -> reading.copy(episodes = true)
                        // A source read on the device: the server lists the titles imported from it, in no category; one
                        // that stands for a format keeps that format, a curated one leaves the source out.
                        else -> category?.format?.let { Reading(reading.source, null, it, episodes = true) }
                    }
                }
            }
    }

    private val visible = MutableStateFlow<List<CatalogSource>?>(null)
    private val filter = MutableStateFlow<CatalogFilter?>(null)
    private var plan: Plan? = null
    private var readings: List<Reading> = emptyList()

    /** False until the first plan: an empty first plan must still settle the screen. */
    private var planned = false

    /** The cards on screen carry episode badges. */
    private var badges = false
    private var hasMore: Map<String, Boolean> = emptyMap()
    private var generation = 0
    private var searchJob: Job? = null
    private var resultFormatsJob: Job? = null
    private var suggestJob: Job? = null
    private var blurJob: Job? = null

    /** What a pick would say of each title on screen as a search result or a suggestion (title, format known). */
    private var resultPicks: Map<Pair<String, String>, Pair<TitleSummary, ReadingFormat?>> = emptyMap()
    private var suggestionPicks: Map<Pair<String, String>, Pair<TitleSummary, ReadingFormat?>> = emptyMap()
    private var listingsJob: Job? = null
    private val categoriesAsked = mutableSetOf<String>()

    /** The library's hearts and follows, and the titles rated: what the server already knows of this account. */
    private var marks = LibraryMarks()
    private var rated: Set<String> = emptySet()

    /** Titles whose numbers, and whose series, were asked while the screen lives (once each). */
    private val statsAsked = mutableSetOf<String>()
    private val seriesAsked = mutableSetOf<String>()

    /** The mix of the grid (`CatalogMerge`): kept while the screen lives, drawn again on pull to refresh. */
    private val seed = MutableStateFlow(Random.nextLong())

    init {
        resume
            .observe()
            .flatMapLatest { points ->
                // The newest title whose page is on the device (its name, cover and chapters are known).
                val recent = points.take(RESUME_CANDIDATES)
                if (recent.isEmpty()) {
                    flowOf(points to null)
                } else {
                    combine(recent.map { point -> repository.observeTitle(point.sourceKey, point.titleId).map { point to it } }) { known ->
                        points to known.firstNotNullOfOrNull { (point, detail) -> detail?.let { resumeCard(point, it) } }
                    }
                }
            }.onEach { (points, card) ->
                mutableState.update { it.copy(startedKeys = points.mapTo(HashSet()) { p -> "${p.sourceKey}/${p.titleId}" }, resume = card) }
            }.launchIn(viewModelScope)
        // Safe mode switched in Settings: search results follow too (the listings combine it themselves).
        content
            .showAdult()
            .distinctUntilChanged()
            .onEach { adult ->
                val changed = adult != showAdult
                showAdult = adult
                if (changed && state.value.query.isNotBlank()) search(state.value.query)
            }.launchIn(viewModelScope)
        combine(favorites.observe(), ratings.observeRatings()) { m, r -> m to r.keys }
            .onEach { (m, r) ->
                marks = m
                rated = r
                mutableState.update { it.copy(favoriteKeys = m.favorites) }
                askStats(state.value.cards, typed = false)
            }.launchIn(viewModelScope)
        ratings
            .observeStats()
            .onEach { numbers -> mutableState.update { it.copy(stats = numbers) } }
            .launchIn(viewModelScope)
        seriesRepository
            .observe()
            .onEach { known -> mutableState.update { it.copy(series = known) } }
            .launchIn(viewModelScope)
        viewModelScope.launch { filter.value = repository.observeFilter().first() }
        // Picks made offline go out as soon as the catalog opens online.
        viewModelScope.launch { search.sendPending() }
        // Ratings given offline go out as soon as the catalog opens online.
        viewModelScope.launch { ratings.sync() }
        val selection =
            combine(visible.filterNotNull(), filter.filterNotNull(), video.enabled().distinctUntilChanged()) { v, f, on ->
                videoOn = on
                Triple(v, f, CatalogSelection.selected(v, f))
            }
        selection
            .flatMapLatest { (v, f, selected) ->
                categoriesOf(
                    selected,
                ).map { Plan(v, f, selected, emptyList(), null, episodes = f.episodesOnly && videoOn, video = videoOn).resolve(it) }
            }.distinctUntilChanged()
            .onEach(::apply)
            .launchIn(viewModelScope)
    }

    fun onIntent(intent: CatalogIntent) {
        when (intent) {
            CatalogIntent.Refresh -> {
                seed.value = Random.nextLong()
                refresh(categoriesToo = true, askFormats = true)
            }
            CatalogIntent.LoadMore -> loadMore()
            // The same text again (a native field echoing a search chosen under it) changes nothing.
            is CatalogIntent.Query -> if (intent.value != state.value.query) search(intent.value)
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
            CatalogIntent.ToggleEpisodes -> if (videoOn) changeFilter { it.copy(episodesOnly = !it.episodesOnly) }
            is CatalogIntent.ToggleFavorite -> toggleFavorite(intent.sourceKey, intent.titleId)
            is CatalogIntent.SearchFocused -> {
                blurJob?.cancel()
                if (intent.focused) {
                    mutableState.update { it.copy(searchFocused = true) }
                    suggest(state.value.query)
                } else {
                    // A tap on a suggestion takes the focus from the field before the tap lands: the list stays a
                    // moment, so the tap reaches the row, never what lies under it.
                    blurJob =
                        viewModelScope.launch {
                            delay(BLUR_GRACE_MS)
                            closeSuggestions()
                        }
                }
            }
            is CatalogIntent.UseSearch -> {
                closeSuggestions()
                search(intent.text, debounce = false)
            }
            is CatalogIntent.TitleOpened -> {
                closeSuggestions()
                picked(intent.sourceKey, intent.titleId)
            }
        }
    }

    private fun toggleFavorite(
        sourceKey: String,
        titleId: String,
    ) {
        val card = state.value.visible.firstOrNull { it.sourceKey == sourceKey && it.id == titleId } ?: return
        mutableState.update { it.copy(favoriteError = null) }
        // The card's heart is the series': off takes it from every source that has it, on gives it to the card's title.
        val hearted =
            state.value.series[card.key]
                ?.members
                ?.filter { it.key in state.value.favoriteKeys }
                ?.map { MarkedTitle(it.sourceKey, it.titleRef) to it.name }
        val targets =
            if (card.favorite && !hearted.isNullOrEmpty()) hearted else listOf(MarkedTitle(sourceKey, titleId) to card.name)
        viewModelScope.launch {
            val failed =
                targets
                    .map { (title, name) -> favorites.set(title, name, card.coverUrl.takeIf { title.key == card.key }, !card.favorite) }
                    .any { it is AppResult.Err }
            if (failed) mutableState.update { it.copy(favoriteError = t("marks.favoriteError")) }
        }
    }

    /**
     * Everyone's numbers for cards of sources read on the device, only for titles the server already knows from this
     * account (followed, rated, or a typed search's results): browsing never tells it what a source lists. Our catalog's
     * listings carry their numbers.
     */
    private fun askStats(
        cards: List<TitleCard>,
        typed: Boolean,
    ) {
        askSeries(cards, typed)
        val wanted =
            cards.filter {
                it.sourceKey != INTERNAL_SOURCE && (typed || it.key in marks.followed || it.key in rated) && it.key !in statsAsked
            }
        if (wanted.isEmpty()) return
        statsAsked += wanted.map { it.key }
        viewModelScope.launch {
            // Not answered (offline): asked again with the next cards.
            if (ratings.refreshStats(wanted.map { MarkedTitle(it.sourceKey, it.id) }) is AppResult.Err) {
                statsAsked -= wanted.map { it.key }.toSet()
            }
        }
    }

    /** The series of cards on screen: ours, and a source read on the device's only as [askStats] says. */
    private fun askSeries(
        cards: List<TitleCard>,
        typed: Boolean,
    ) {
        val wanted =
            cards.filter {
                (it.sourceKey == INTERNAL_SOURCE || typed || it.key in marks.followed || it.key in rated) && it.key !in seriesAsked
            }
        if (wanted.isEmpty()) return
        seriesAsked += wanted.map { it.key }
        viewModelScope.launch {
            if (seriesRepository.resolve(wanted.map { MarkedTitle(it.sourceKey, it.id) }) is AppResult.Err) {
                seriesAsked -= wanted.map { it.key }.toSet()
            }
        }
    }

    private fun closeSuggestions() {
        blurJob?.cancel()
        suggestJob?.cancel()
        mutableState.update { it.copy(searchFocused = false) }
    }

    /** A pick: what was typed, and the title opened from the results or a suggestion. */
    private fun picked(
        sourceKey: String,
        titleId: String,
    ) {
        val current = state.value
        val query = current.query.trim()
        if (SearchRules.normalise(query).length < SearchRules.MIN_PICK_QUERY) return
        val key = sourceKey to titleId
        val (title, format) = (if (current.results != null) resultPicks[key] else null) ?: suggestionPicks[key] ?: return
        viewModelScope.launch {
            search.recordPick(SearchPick(query, title.sourceKey, title.id, title.name, title.contentRating, format))
        }
    }

    /**
     * How a title reads, as the device knows it: the server's word for a suggested one, else the format of the
     * catalog filter it was found under, else a book's; null rather than a guess (a source that names no format reads
     * as manga by default).
     */
    private fun knownFormat(title: TitleSummary): ReadingFormat? =
        plan?.category?.format ?: ReadingFormat.Text.takeIf { title.kind == ContentKind.Book }

    /** Suggestions under the field, a short pause after typing (the server answers 120 a minute). */
    private fun suggest(query: String) {
        suggestJob?.cancel()
        if (!state.value.searchFocused) return
        val names = plan?.visible.orEmpty().associate { it.key to it.name }
        val format = plan?.category?.format
        val episodes = plan?.episodes == true
        suggestJob =
            viewModelScope.launch {
                delay(SUGGEST_DEBOUNCE_MS)
                val found = search.suggest(query.trim(), SearchRepository.SUGGEST_LIMIT, format, episodes)
                val titles = found.titles.filter { it.sourceKey in names && it.toSummary().shownIn(showAdult) }
                val covers = titles.associate { (it.sourceKey to it.titleRef) to (it.coverUrl ?: savedCover(it)) }
                suggestionPicks = titles.associate { (it.sourceKey to it.titleRef) to (it.toSummary() to it.format) }
                mutableState.update {
                    it.copy(suggestions = sections(found.recent, found.queries, titles, names, covers))
                }
            }
    }

    private fun sections(
        recent: List<String>,
        queries: List<String>,
        titles: List<SuggestedTitle>,
        names: Map<String, String>,
        covers: Map<Pair<String, String>, String?>,
    ): List<SuggestionSection> =
        listOfNotNull(
            recent.takeIf { it.isNotEmpty() }?.let { list ->
                SuggestionSection(SuggestionKind.Recent, t("search.recent"), list.map { SuggestionRow(SuggestionKind.Recent, it) })
            },
            queries.takeIf { it.isNotEmpty() }?.let { list ->
                SuggestionSection(SuggestionKind.Query, t("search.others"), list.map { SuggestionRow(SuggestionKind.Query, it) })
            },
            titles.takeIf { it.isNotEmpty() }?.let { list ->
                SuggestionSection(
                    SuggestionKind.Title,
                    t("search.titles"),
                    list.map { title ->
                        SuggestionRow(
                            kind = SuggestionKind.Title,
                            text = title.name,
                            detail =
                                listOfNotNull(
                                    when (title.reason) {
                                        SuggestReason.Popular -> t("search.popular")
                                        SuggestReason.Yours -> t("search.yours")
                                        null -> null
                                    },
                                    names[title.sourceKey],
                                ).joinToString(" ✦ "),
                            sourceKey = title.sourceKey,
                            titleId = title.titleRef,
                            coverUrl = covers[title.sourceKey to title.titleRef],
                        )
                    },
                )
            },
        )

    /** A source read on the device gets no cover from the server: the saved copy's, when there is one. */
    private suspend fun savedCover(title: SuggestedTitle): String? =
        repository
            .observeTitle(title.sourceKey, title.titleRef)
            .first()
            ?.summary
            ?.coverUrl

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
        val options = CategoryUnion.formatsFirst(CategoryUnion.of(categories), formatNames())
        return copy(options = options, category = CategoryUnion.find(options, filter.categoryName), sourceCategories = categories)
    }

    private fun formatNames() =
        listOf(
            ReadingFormat.Manga to t("catalog.formatManga"),
            ReadingFormat.Webtoon to t("catalog.formatWebtoon"),
            ReadingFormat.Text to t("catalog.formatBook"),
        )

    /** The formats (the first options), then each source's other categories under its name; empty groups left out. */
    private fun groups(next: Plan): List<CategoryGroup> {
        val formats = next.options.take(formatNames().size)
        val chosen = next.category?.name

        fun choice(option: CategoryOption) = CategoryChoice(option.name, option.name == chosen)
        val names = next.visible.associate { it.key to it.name }
        val genres =
            next.sourceCategories.mapNotNull { (key, list) ->
                val own =
                    list
                        .mapNotNull { c -> CategoryUnion.find(next.options, c.name)?.takeIf { it !in formats } }
                        .distinct()
                        .map(::choice)
                own.takeIf { it.isNotEmpty() }?.let { CategoryGroup(names[key] ?: key, it) }
            }
        return listOf(CategoryGroup(null, formats.map(::choice))) + genres
    }

    /** A new plan: the filters' rows, and when what is read changed, the listings to show and reload. */
    private fun apply(next: Plan) {
        plan = next
        val names = next.visible.associate { it.key to it.name }
        val read = next.readings.mapTo(HashSet()) { it.source.key }
        val missing = next.category?.let { next.selected.filter { it.key !in read }.map { it.name } }.orEmpty()
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
                categoryGroups = groups(next),
                category = next.category?.name,
                categoryNote = next.category?.let { c -> CatalogNotices.categoryLeftOut(c.name, missing) },
                showEpisodesFilter = next.video,
                episodesOnly = next.episodes,
            )
        }
        // Categories are asked once per source while the screen lives (and again on Refresh).
        next.selected.filter { categoriesAsked.add(it.key) }.forEach { source ->
            viewModelScope.launch { repository.refreshCategories(source.key) }
        }
        val nextReadings = next.readings
        // `video` switched: the same listings, drawn again with or without their badges.
        if (planned && nextReadings == readings && next.video == badges) return
        badges = next.video
        planned = true
        readings = nextReadings
        hasMore = emptyMap()
        listingsJob?.cancel()
        listingsJob =
            mergedListings(nextReadings)
                .combine(content.showAdult()) { titles, adult -> titles to adult }
                .onEach { (titles, adult) ->
                    val shown = titles.filter { t -> t.shownIn(adult) }
                    hiddenInListing = shown.size < titles.size
                    mutableState.update {
                        it.copy(cards = shown.map { t -> card(t, names) }, safeNote = safeNote(hiddenInListing))
                    }
                    askStats(state.value.cards, typed = false)
                }.launchIn(viewModelScope)
        mutableState.update { it.copy(sourcesRead = nextReadings.size, problems = emptyList(), canLoadMore = false) }
        refresh(categoriesToo = false)
        if (state.value.query.isNotBlank()) search(state.value.query)
    }

    private fun safeNote(hidden: Boolean) = if (hidden) t("catalog.safeHidden") else null

    private fun mergedListings(list: List<Reading>): Flow<List<TitleSummary>> =
        if (list.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(
                list.map { reading ->
                    repository
                        .observeListing(reading.source.key, reading.categoryId, reading.episodes)
                        // Filed by format: what the server learned of these titles (asked once each) before keeping them.
                        // The server's own listing of titles with an episode names their formats already.
                        .onEach { titles ->
                            if (reading.format != null && !reading.episodes) {
                                viewModelScope.launch { repository.learnFormats(reading.source.key, titles.map { it.title.id }) }
                            }
                        }.map { titles -> titles.filter { reading.keeps(it.title) } }
                },
            ) { listings -> listings.toList() }.combine(seed) { listings, s -> CatalogMerge.merge(listings, s) }
        }

    /** The formats the server knew nothing of, asked again for what the filtered sources list now. */
    private suspend fun askFormatsAgain(of: List<Reading>) {
        of.filter { it.format != null && !it.episodes }.forEach { reading ->
            repository.askFormatsAgain(reading.source.key)
            val ids = repository.observeListing(reading.source.key, reading.categoryId).first().map { it.title.id }
            if (ids.isNotEmpty()) repository.learnFormats(reading.source.key, ids)
        }
    }

    private fun refresh(
        categoriesToo: Boolean,
        askFormats: Boolean = false,
    ) {
        val current = readings
        if (categoriesToo) {
            plan?.selected?.forEach { source -> viewModelScope.launch { repository.refreshCategories(source.key) } }
        }
        val run = ++generation
        if (current.isEmpty()) {
            // Nothing to read (no source selected, or none has the category): an empty catalog, never a skeleton.
            mutableState.update { it.copy(freshness = Freshness.Fresh, problems = emptyList(), canLoadMore = false) }
            return
        }
        mutableState.update { it.copy(freshness = Freshness.Refreshing, error = null) }
        viewModelScope.launch {
            val results =
                current
                    .map { reading ->
                        async { reading to repository.refreshListing(reading.source.key, reading.categoryId, reading.episodes) }
                    }.awaitAll()
            if (run != generation) return@launch
            hasMore = results.associate { (reading, result) -> reading.source.key to ((result as? AppResult.Ok)?.value == true) }
            mutableState.update { it.after(results, replacing = current.map { r -> r.source.key }.toSet()) }
            if (askFormats) askFormatsAgain(current)
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
                more
                    .map { reading ->
                        async { reading to repository.loadMoreListing(reading.source.key, reading.categoryId, reading.episodes) }
                    }.awaitAll()
            if (run != generation) return@launch
            hasMore = hasMore + results.associate { (reading, result) -> reading.source.key to ((result as? AppResult.Ok)?.value == true) }
            mutableState.update { it.after(results, replacing = more.map { r -> r.source.key }.toSet()).copy(loadingMore = false) }
            askFormatsAgain(more)
        }
    }

    private fun search(
        query: String,
        debounce: Boolean = true,
    ) {
        val changed = query != state.value.query
        mutableState.update { it.copy(query = query) }
        if (changed) suggest(query)
        searchJob?.cancel()
        if (query.isBlank()) {
            resultFormatsJob?.cancel()
            mutableState.update { it.copy(results = null, searchNotice = null) }
            resultPicks = emptyMap()
            return
        }
        val current = readings
        val names = plan?.visible.orEmpty().associate { it.key to it.name }
        val format = plan?.category?.format
        val episodes = plan?.episodes == true
        // A curated category (no format) only keeps what its sources return; otherwise a title the server learned for
        // this spelling shows even when no source found it (the typo tolerance).
        val extras = plan?.category?.let { it.format != null } ?: true
        searchJob =
            viewModelScope.launch {
                if (debounce) delay(SEARCH_DEBOUNCE_MS)
                val learned = async { search.suggest(query.trim(), SearchRepository.RESULTS_LIMIT, format, episodes) }
                // The field keeps what was typed; sources get it without the spaces around (iOS autocorrect adds one).
                val found =
                    current
                        .map { reading ->
                            async {
                                repository.search(reading.source.key, query.trim(), reading.categoryId, reading.episodes).let { found ->
                                    // A typed search already goes to the server (suggestions): its results' formats are
                                    // asked too (comic sources read on the device, once each), so a learned strip is a
                                    // webtoon here as in the grid. Browsing never asks.
                                    val learned =
                                        if (found.offline || reading.episodes) {
                                            emptyMap()
                                        } else {
                                            repository.learnFormats(
                                                reading.source.key,
                                                found.titles.map { it.id },
                                            )
                                        }
                                    val titles =
                                        found.titles.map { t ->
                                            learned[t.id]
                                                ?.takeIf { reading.format != null || t.format == ReadingFormat.Manga }
                                                ?.let { t.copy(format = it) } ?: t
                                        }
                                    found.copy(titles = titles.filter(reading::keeps))
                                }
                            }
                        }.awaitAll()
                val mixed = CatalogMerge.mix(found.map { it.titles }, Random(seed.value))
                val read = current.mapTo(HashSet()) { it.source.key }
                // Learned titles first (offline, the device's own guesses are not "learned": the sources' mix stands).
                val suggested =
                    learned
                        .await()
                        .takeUnless { it.offline }
                        ?.titles
                        .orEmpty()
                        .filter { it.sourceKey in read }
                // The server's word on a strip (it learned it from imported pages) beats a source's manga default.
                val serverStrips =
                    suggested.filter { it.format == ReadingFormat.Webtoon }.mapTo(HashSet()) { it.sourceKey to it.titleRef }
                val all =
                    SearchRules.order(suggested, mixed, extras).map { found ->
                        val t =
                            if (found.format == ReadingFormat.Manga && (found.sourceKey to found.id) in serverStrips) {
                                found.copy(format = ReadingFormat.Webtoon)
                            } else {
                                found
                            }
                        // No cover in the answer (a learned title, or a source's search that sends none): the saved one.
                        if (t.coverUrl == null) {
                            t.copy(
                                coverUrl =
                                    repository
                                        .observeTitle(t.sourceKey, t.id)
                                        .first()
                                        ?.summary
                                        ?.coverUrl,
                            )
                        } else {
                            t
                        }
                    }
                val titles = all.filter { t -> t.shownIn(showAdult) }
                val popular =
                    suggested.filter { it.reason == SuggestReason.Popular }.mapTo(HashSet()) { it.sourceKey to it.titleRef }
                val serverFormats = suggested.associate { (it.sourceKey to it.titleRef) to it.format }
                resultPicks =
                    titles.associate { t ->
                        val key = t.sourceKey to t.id
                        key to (t to (serverFormats[key] ?: knownFormat(t)))
                    }
                mutableState.update {
                    it.copy(
                        results = titles.map { t -> card(t, names).copy(popular = (t.sourceKey to t.id) in popular) },
                        safeNote = safeNote(hiddenInListing || titles.size < all.size),
                        searchNotice =
                            CatalogNotices.search(
                                offline = found.any { r -> r.offline && r.unavailableSource == null },
                                unavailable = found.mapNotNull { r -> r.unavailableSource.takeIf { r.offline } },
                            ),
                    )
                }
                askStats(state.value.results.orEmpty(), typed = true)
                followResultFormats(titles, names)
            }
    }

    /**
     * Results stay in memory: a title page opened from them may learn the title's format (a strip read from its
     * genres), and the result's card follows the saved row at once, like the grid's.
     */
    private fun followResultFormats(
        titles: List<TitleSummary>,
        names: Map<String, String>,
    ) {
        resultFormatsJob?.cancel()
        val watched = titles.filter { it.sourceKey != INTERNAL_SOURCE && it.kind != ContentKind.Book }
        if (watched.isEmpty()) return
        resultFormatsJob =
            combine(watched.map { t -> repository.observeTitle(t.sourceKey, t.id).map { d -> t to d?.summary?.format } }) { it.toList() }
                .map { pairs -> pairs.mapNotNull { (t, f) -> f?.takeIf { it != t.format }?.let { (t.sourceKey to t.id) to it } }.toMap() }
                .distinctUntilChanged()
                .onEach { saved ->
                    if (saved.isEmpty()) return@onEach
                    mutableState.update { state ->
                        state.copy(
                            results =
                                state.results?.map { card ->
                                    val format = saved[card.sourceKey to card.id] ?: return@map card
                                    val title = titles.firstOrNull { it.sourceKey == card.sourceKey && it.id == card.id } ?: return@map card
                                    card(title.copy(format = format), names).copy(popular = card.popular, started = card.started)
                                },
                        )
                    }
                }.launchIn(viewModelScope)
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
                    if (readings.size >
                        1
                    ) {
                        t("catalog.sourceError", "name" to reading.source.name, "message" to error.userMessage())
                    } else {
                        error.userMessage()
                    }
                },
        )
    }

    private fun resumeCard(
        point: ResumePoint,
        detail: TitleDetail,
    ): ResumeCard? {
        val chapter = detail.chapters.firstOrNull { it.id == point.chapterId } ?: return null
        val label =
            if (detail.summary.kind == ContentKind.Book) {
                chapter.name ?: t("reader.part", "n" to chapter.label)
            } else {
                chapterLabel(chapter.label, chapter.name)
            }
        return ResumeCard(
            sourceKey = point.sourceKey,
            titleId = point.titleId,
            chapterId = point.chapterId,
            page = point.page,
            titleName = detail.summary.name,
            coverUrl = detail.summary.coverUrl,
            label = t("catalog.continueReading"),
            detail = if (point.page > 0) t("catalog.resumeAt", "chapter" to label, "page" to point.page + 1) else label,
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
                title.kind == ContentKind.Book -> t("catalog.badgeBook")
                title.format == ReadingFormat.Webtoon -> t("catalog.badgeWebtoon")
                else -> t("catalog.badgeManga")
            },
        status = statusLabel(title.status),
        episodeBadge = episodeBadge(title.episodeCount, videoOn),
    )

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
        const val SUGGEST_DEBOUNCE_MS = 150L

        /** How long suggestions outlive the field's focus: enough for the tap that took it to land on a row. */
        const val BLUR_GRACE_MS = 300L
        const val RESUME_CANDIDATES = 5
    }
}

/** "▶ 3" on a card whose title has episodes, only with the `video` feature. */
internal fun episodeBadge(
    count: Int,
    video: Boolean,
): String? = if (video && count > 0) t("catalog.episodeBadge", "count" to count) else null
