package com.vovoscan.feature.library.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.Freshness
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
)

data class LibraryUiState(
    val rows: List<LibraryRow> = emptyList(),
    val freshness: Freshness = Freshness.Refreshing,
    val error: String? = null,
) {
    val empty: Boolean get() = rows.isEmpty() && freshness != Freshness.Refreshing
    val emptyMessage: String get() = "Your library is empty. Follow a title to find it here."
    val pendingNotice: String?
        get() =
            rows.count { it.pendingSync }.takeIf { it > 0 }?.let {
                if (it ==
                    1
                ) {
                    "1 change waiting to sync."
                } else {
                    "$it changes waiting to sync."
                }
            }
}

sealed interface LibraryIntent {
    data object Sync : LibraryIntent

    data class Unfollow(
        val sourceKey: String,
        val titleId: String,
    ) : LibraryIntent
}

class LibraryViewModel(
    private val repository: LibraryRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = mutableState.asStateFlow()

    init {
        repository
            .observe()
            .onEach { entries -> mutableState.update { it.copy(rows = entries.map(::row)) } }
            .launchIn(viewModelScope)
        sync()
    }

    fun onIntent(intent: LibraryIntent) {
        when (intent) {
            LibraryIntent.Sync -> sync()
            is LibraryIntent.Unfollow -> viewModelScope.launch { repository.unfollow(intent.sourceKey, intent.titleId) }
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

    private fun row(entry: LibraryEntry) =
        LibraryRow(
            sourceKey = entry.sourceKey,
            titleId = entry.titleRef,
            name = entry.titleName,
            coverUrl = entry.coverUrl,
            progress = if (entry.lastChapterRef == null) "Not started" else "Page ${entry.lastPage + 1}",
            pendingSync = entry.pendingSync,
        )
}
