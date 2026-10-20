package com.vovoscan.feature.sources.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.Freshness
import com.vovoscan.core.common.userMessage
import com.vovoscan.feature.sources.domain.SourceEngine
import com.vovoscan.feature.sources.domain.SourceItem
import com.vovoscan.feature.sources.domain.SourcesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SourceRow(
    val key: String,
    val name: String,
    /** "Manga · Books". */
    val kindsLabel: String,
    /** "vovoscan" or "Read on your device". */
    val engineLabel: String,
    val enabled: Boolean,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
)

/** A source in the catalog picker. */
data class SourceChip(
    val key: String,
    val name: String,
)

data class SourcesUiState(
    val rows: List<SourceRow> = emptyList(),
    val freshness: Freshness = Freshness.Refreshing,
    val pendingNotice: String? = null,
    val error: String? = null,
) {
    /** Visible sources, in the chosen order: the catalog picker. */
    val chips: List<SourceChip> get() = rows.filter { it.enabled }.map { SourceChip(it.key, it.name) }

    val empty: Boolean get() = rows.isEmpty() && freshness != Freshness.Refreshing
}

sealed interface SourcesIntent {
    data object Refresh : SourcesIntent

    data class Toggle(
        val key: String,
    ) : SourcesIntent

    data class MoveUp(
        val key: String,
    ) : SourcesIntent

    data class MoveDown(
        val key: String,
    ) : SourcesIntent
}

/** "Sources" screen: show or hide each source, reorder it. At least one source stays visible. */
class SourcesViewModel(
    private val repository: SourcesRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SourcesUiState())
    val state: StateFlow<SourcesUiState> = mutableState.asStateFlow()
    private var items: List<SourceItem> = emptyList()

    init {
        repository
            .observe()
            .onEach { sources ->
                items = sources
                mutableState.update {
                    it.copy(
                        rows = sources.mapIndexed { i, s -> row(s, i, sources.size) },
                        pendingNotice = if (sources.any { s -> s.pendingSync }) "Your changes will sync when you're back online." else null,
                    )
                }
            }.launchIn(viewModelScope)
        refresh()
    }

    fun onIntent(intent: SourcesIntent) {
        when (intent) {
            SourcesIntent.Refresh -> refresh()
            is SourcesIntent.Toggle -> {
                val target = items.firstOrNull { it.key == intent.key } ?: return
                // The last visible source cannot be hidden: the catalog would be empty.
                if (target.enabled && items.count { it.enabled } == 1) {
                    mutableState.update { it.copy(error = "Keep at least one source visible.") }
                    return
                }
                save(items.map { if (it.key == intent.key) it.copy(enabled = !it.enabled) else it })
            }
            is SourcesIntent.MoveUp -> move(intent.key, -1)
            is SourcesIntent.MoveDown -> move(intent.key, +1)
        }
    }

    private fun move(
        key: String,
        delta: Int,
    ) {
        val from = items.indexOfFirst { it.key == key }
        val to = from + delta
        if (from < 0 || to !in items.indices) return
        save(items.toMutableList().apply { add(to, removeAt(from)) })
    }

    private fun save(ordered: List<SourceItem>) {
        mutableState.update { it.copy(error = null) }
        viewModelScope.launch {
            val result = repository.save(ordered.map { it.key to it.enabled })
            if (result is AppResult.Err && result.error != AppError.Network) {
                mutableState.update { it.copy(error = result.error.userMessage()) }
            }
        }
    }

    private fun refresh() {
        mutableState.update { it.copy(freshness = Freshness.Refreshing, error = null) }
        viewModelScope.launch {
            val result = repository.refresh()
            mutableState.update {
                when (result) {
                    is AppResult.Ok -> it.copy(freshness = Freshness.Fresh)
                    is AppResult.Err ->
                        it.copy(
                            freshness = Freshness.Offline,
                            error =
                                if (result.error ==
                                    AppError.Network
                                ) {
                                    null
                                } else {
                                    result.error.userMessage()
                                },
                        )
                }
            }
        }
    }

    private fun row(
        source: SourceItem,
        index: Int,
        count: Int,
    ) = SourceRow(
        key = source.key,
        name = source.name,
        kindsLabel = source.kinds.joinToString(" · ") { kindLabel(it) },
        engineLabel = if (source.engine == SourceEngine.Internal) "vovoscan" else "Read on your device",
        enabled = source.enabled,
        canMoveUp = index > 0,
        canMoveDown = index < count - 1,
    )

    private fun kindLabel(kind: String) =
        when (kind) {
            "comic" -> "Manga"
            "book" -> "Books"
            "audiobook" -> "Audiobooks"
            "video" -> "Videos"
            else -> kind
        }
}
