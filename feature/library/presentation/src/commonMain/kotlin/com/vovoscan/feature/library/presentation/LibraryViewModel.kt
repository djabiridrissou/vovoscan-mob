package com.vovoscan.feature.library.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.Freshness
import com.vovoscan.core.common.i18n.t
import com.vovoscan.core.common.userMessage
import com.vovoscan.feature.library.domain.LibraryEntry
import com.vovoscan.feature.library.domain.LibraryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryRow(
    val sourceKey: String,
    val titleId: String,
    val name: String,
    val coverUrl: String?,
    /** "Page 12" or "Not started". */
    val progress: String,
    /** Changed offline, not sent yet: small badge. */
    val pendingSync: Boolean,
    /** Its source is no longer in the user's list: greyed out (readable again if the source comes back). */
    val sourceRemoved: Boolean = false,
    /** "Source removed", or null. */
    val sourceNotice: String? = null,
)

data class LibraryUiState(
    val rows: List<LibraryRow> = emptyList(),
    val freshness: Freshness = Freshness.Refreshing,
    val error: String? = null,
) {
    val empty: Boolean get() = rows.isEmpty() && freshness != Freshness.Refreshing
    val emptyMessage: String get() = t("library.empty")
    val pendingNotice: String?
        get() =
            rows.count { it.pendingSync }.takeIf { it > 0 }?.let { t("library.pending", "count" to it) }
}

sealed interface LibraryIntent {
    data object Sync : LibraryIntent

    data class Unfollow(
        val sourceKey: String,
        val titleId: String,
    ) : LibraryIntent

    /** Every source of the user's list (visible or not), sent by the app from the sources feature. */
    data class SourcesChanged(
        val keys: List<String>,
    ) : LibraryIntent
}

class LibraryViewModel(
    private val repository: LibraryRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = mutableState.asStateFlow()
    private var entries: List<LibraryEntry> = emptyList()

    /** Sources of the user's list; null until the app sends them (nothing is flagged before). */
    private var sourceKeys: Set<String>? = null

    init {
        repository
            .observe()
            .onEach { list ->
                entries = list
                render()
            }.launchIn(viewModelScope)
        sync()
    }

    private fun render() = mutableState.update { it.copy(rows = entries.map(::row)) }

    fun onIntent(intent: LibraryIntent) {
        when (intent) {
            LibraryIntent.Sync -> sync()
            is LibraryIntent.Unfollow -> viewModelScope.launch { repository.unfollow(intent.sourceKey, intent.titleId) }
            is LibraryIntent.SourcesChanged -> {
                // An empty list means the sources are not loaded yet, not that they were all removed.
                sourceKeys = intent.keys.toSet().takeIf { it.isNotEmpty() }
                render()
            }
        }
    }

    private fun sync() {
        mutableState.update { it.copy(freshness = Freshness.Refreshing, error = null) }
        viewModelScope.launch {
            val result = repository.sync()
            mutableState.update {
                when (result) {
                    is AppResult.Ok -> it.copy(freshness = Freshness.Fresh)
                    is AppResult.Err ->
                        it.copy(
                            freshness = Freshness.Offline,
                            error = if (result.error == AppError.Network) null else result.error.userMessage(),
                        )
                }
            }
        }
    }

    private fun row(entry: LibraryEntry): LibraryRow {
        val removed = sourceKeys?.let { entry.sourceKey !in it } ?: false
        return LibraryRow(
            sourceKey = entry.sourceKey,
            titleId = entry.titleRef,
            name = entry.titleName,
            coverUrl = entry.coverUrl,
            progress = if (entry.lastChapterRef == null) t("library.notStarted") else t("library.page", "n" to entry.lastPage + 1),
            pendingSync = entry.pendingSync,
            sourceRemoved = removed,
            sourceNotice = if (removed) t("library.sourceRemoved") else null,
        )
    }
}
