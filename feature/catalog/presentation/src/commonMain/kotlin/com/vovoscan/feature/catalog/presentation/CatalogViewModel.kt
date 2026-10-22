package com.vovoscan.feature.catalog.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.Freshness
import com.vovoscan.core.common.StaleReason
import com.vovoscan.core.common.userMessage
import com.vovoscan.feature.catalog.domain.CatalogRepository
import com.vovoscan.feature.catalog.domain.ContentKind
import com.vovoscan.feature.catalog.domain.INTERNAL_SOURCE
import com.vovoscan.feature.catalog.domain.ReadingFormat
import com.vovoscan.feature.catalog.domain.TitleSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A catalog card, ready to display. */
data class TitleCard(
    val sourceKey: String,
    val id: String,
    val name: String,
    val coverUrl: String?,
    /** "WEBTOON", "MANGA" or "BOOK": badge on the cover. */
    val formatLabel: String,
    val status: String,
)

data class CatalogUiState(
    /** Displayed source; the picker comes from the "Sources" screen (wired by the app). */
    val sourceKey: String = INTERNAL_SOURCE,
    val cards: List<TitleCard> = emptyList(),
    val query: String = "",
    /** Search results; null when not searching (the catalog shows). */
    val results: List<TitleCard>? = null,
    val searchingOffline: Boolean = false,
    /** Search fell back to the local copy because this source didn't answer. */
    val searchUnavailableSource: String? = null,
    val freshness: Freshness = Freshness.Refreshing,
    /** Why the last reload failed: offline, the source isn't answering, or its rules can't read it. */
    val stale: StaleReason? = null,
    val loadingMore: Boolean = false,
    val canLoadMore: Boolean = false,
    val error: String? = null,
) {
    val visible: List<TitleCard> get() = results ?: cards

    /** Nothing to show, once the source has been read (offline or a source that failed: the banner says so instead). */
    val empty: Boolean
        get() = visible.isEmpty() && freshness != Freshness.Refreshing && (stale == null || results != null)

    val emptyMessage: String
        get() = if (results != null) "No title matches “$query”." else "No titles yet. Check back soon."

    /**
     * Discreet banner when the local copy is shown: no network ("Offline: …"), a source that isn't answering
     * ("Project Gutenberg isn't answering right now. Showing what's saved.") or rules that can't read it.
     */
    val offlineNotice: String?
        get() =
            when {
                searchingOffline && searchUnavailableSource != null ->
                    "$searchUnavailableSource isn't answering right now. Searching what's saved."
                searchingOffline -> "Offline: searching titles saved on this device."
                stale != null -> stale.notice(hasSaved = cards.isNotEmpty())
                else -> null
            }

    /** A source that didn't answer: "Retry" next to the banner (sends `Refresh`). */
    val canRetry: Boolean get() = !searchingOffline && stale?.canRetry == true
}

sealed interface CatalogIntent {
    data object Refresh : CatalogIntent

    data object LoadMore : CatalogIntent

    data class Query(
        val value: String,
    ) : CatalogIntent

    data class SelectSource(
        val key: String,
    ) : CatalogIntent

    /** Visible sources, in the reader's order: the first is used until they pick another one. */
    data class SourcesChanged(
        val keys: List<String>,
    ) : CatalogIntent
}

/**
 * A source's catalog, offline first: the local copy at once, then reloaded; search with a short typing
 * debounce. Switching sources starts from that source's own local copy.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogViewModel(
    private val repository: CatalogRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CatalogUiState())
    val state: StateFlow<CatalogUiState> = mutableState.asStateFlow()
    private var searchJob: Job? = null

    private val source = MutableStateFlow(INTERNAL_SOURCE)
    private var pickedByReader = false

    init {
        source
            .flatMapLatest { key -> repository.observeTitles(key).map { key to it } }
            .onEach { (key, titles) -> if (key == source.value) mutableState.update { it.copy(cards = titles.map(::card)) } }
            .launchIn(viewModelScope)
        refresh()
    }

    fun onIntent(intent: CatalogIntent) {
        when (intent) {
            CatalogIntent.Refresh -> refresh()
            CatalogIntent.LoadMore -> loadMore()
            is CatalogIntent.Query -> search(intent.value)
            is CatalogIntent.SelectSource -> {
                pickedByReader = true
                select(intent.key)
            }
            is CatalogIntent.SourcesChanged -> follow(intent.keys)
        }
    }

    private fun follow(keys: List<String>) {
        val first = keys.firstOrNull() ?: return
        if (source.value in keys && pickedByReader) return
        // Source hidden in the meantime: fall back to the reader's order.
        pickedByReader = false
        select(first)
    }

    private fun select(key: String) {
        if (key == source.value) return
        searchJob?.cancel()
        source.value = key
        mutableState.update { CatalogUiState(sourceKey = key) }
        refresh()
    }

    private fun refresh() {
        mutableState.update { it.copy(freshness = Freshness.Refreshing, error = null) }
        viewModelScope.launch {
            val key = source.value
            val result = repository.refreshTitles(key)
            if (key != source.value) return@launch
            mutableState.update { it.after(result) }
        }
    }

    private fun loadMore() {
        val current = state.value
        if (!current.canLoadMore || current.loadingMore || current.results != null) return
        mutableState.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            val key = source.value
            val result = repository.loadMoreTitles(key)
            if (key != source.value) return@launch
            mutableState.update { it.after(result).copy(loadingMore = false) }
        }
    }

    private fun search(query: String) {
        mutableState.update { it.copy(query = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            mutableState.update { it.copy(results = null, searchingOffline = false, searchUnavailableSource = null) }
            return
        }
        searchJob =
            viewModelScope.launch {
                delay(SEARCH_DEBOUNCE_MS)
                val found = repository.search(source.value, query)
                mutableState.update {
                    it.copy(
                        results = found.titles.map(::card),
                        searchingOffline = found.offline,
                        searchUnavailableSource = found.unavailableSource,
                    )
                }
            }
    }

    private fun CatalogUiState.after(result: AppResult<Boolean>): CatalogUiState =
        when (result) {
            is AppResult.Ok -> copy(freshness = Freshness.Fresh, canLoadMore = result.value, error = null, stale = null)
            is AppResult.Err -> {
                // Offline, source down or unreadable: the banner says it; anything else is an error line.
                val reason = StaleReason.of(result.error)
                copy(freshness = Freshness.Offline, stale = reason, error = if (reason == null) result.error.userMessage() else null)
            }
        }

    private fun card(title: TitleSummary) =
        TitleCard(
            sourceKey = title.sourceKey,
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
