package com.vovoscan.feature.catalog.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.Freshness
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

/** Une carte du catalogue, prête à afficher. */
data class TitleCard(
    val sourceKey: String,
    val id: String,
    val name: String,
    val coverUrl: String?,
    /** « WEBTOON », « MANGA » ou « BOOK » : pastille sur la couverture. */
    val formatLabel: String,
    val status: String,
)

data class CatalogUiState(
    /** Source affichée ; le sélecteur vient de l'écran « Sources » (assemblé par l'application). */
    val sourceKey: String = INTERNAL_SOURCE,
    val cards: List<TitleCard> = emptyList(),
    val query: String = "",
    /** Résultats de recherche ; null hors recherche (le catalogue s'affiche). */
    val results: List<TitleCard>? = null,
    val searchingOffline: Boolean = false,
    val freshness: Freshness = Freshness.Refreshing,
    val loadingMore: Boolean = false,
    val canLoadMore: Boolean = false,
    val error: String? = null,
) {
    val visible: List<TitleCard> get() = results ?: cards

    /** Rien à montrer, une fois le serveur relu. */
    val empty: Boolean get() = visible.isEmpty() && freshness != Freshness.Refreshing

    val emptyMessage: String
        get() = if (results != null) "No title matches “$query”." else "No titles yet. Check back soon."

    /** Bandeau discret quand la copie locale est affichée. */
    val offlineNotice: String?
        get() =
            when {
                searchingOffline -> "Offline: searching titles saved on this device."
                freshness == Freshness.Offline -> "Offline: showing what's saved on this device."
                else -> null
            }
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

    /** Sources visibles, dans l'ordre du lecteur : la première sert tant qu'il n'en a pas choisi une autre. */
    data class SourcesChanged(
        val keys: List<String>,
    ) : CatalogIntent
}

/**
 * Catalogue d'une source, hors ligne d'abord : la copie locale tout de suite, puis relue ; recherche avec un court
 * délai de frappe. Changer de source repart de sa propre copie locale.
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
        // Source masquée entre-temps : on revient à l'ordre du lecteur.
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
            mutableState.update { it.copy(results = null, searchingOffline = false) }
            return
        }
        searchJob =
            viewModelScope.launch {
                delay(SEARCH_DEBOUNCE_MS)
                val found = repository.search(source.value, query)
                mutableState.update { it.copy(results = found.titles.map(::card), searchingOffline = found.offline) }
            }
    }

    private fun CatalogUiState.after(result: AppResult<Boolean>): CatalogUiState =
        when (result) {
            is AppResult.Ok -> copy(freshness = Freshness.Fresh, canLoadMore = result.value, error = null)
            is AppResult.Err ->
                if (result.error == AppError.Network) {
                    copy(freshness = Freshness.Offline)
                } else {
                    copy(freshness = Freshness.Offline, error = result.error.userMessage())
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
