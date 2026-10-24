package com.vovoscan.feature.catalog.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.i18n.t
import com.vovoscan.core.common.userMessage
import com.vovoscan.feature.catalog.domain.SearchRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchPrivacyUiState(
    /** null until the account's choice is known (online only). */
    val learning: Boolean? = null,
    val busy: Boolean = false,
    /** "Search history cleared." */
    val notice: String? = null,
    val error: String? = null,
) {
    val title: String get() = t("search.card.title")
    val privacy: String get() = t("search.card.privacy")

    /** "Learning from your searches." / "Not learning from your searches."; null while unknown. */
    val status: String?
        get() =
            when (learning) {
                true -> t("search.card.learning")
                false -> t("search.card.off")
                null -> null
            }

    /** "Stop learning from my searches" / "Learn from my searches". */
    val toggleLabel: String get() = if (learning == false) t("search.card.start") else t("search.card.stop")
    val clearLabel: String get() = t("search.card.clear")
    val canAct: Boolean get() = learning != null && !busy
}

sealed interface SearchPrivacyIntent {
    /** Read the account's choice again (the screen came back, or Retry). */
    data object Load : SearchPrivacyIntent

    /** Stop learning (the server deletes this account's picks) or start again. */
    data object Toggle : SearchPrivacyIntent

    data object ClearHistory : SearchPrivacyIntent
}

/**
 * Settings › Preferences, the "Search" card: whether the search learns from this account's picks, and clearing its
 * history. Online only, never queued ("You're offline…").
 */
class SearchPrivacyViewModel(
    private val search: SearchRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SearchPrivacyUiState())
    val state: StateFlow<SearchPrivacyUiState> = mutableState.asStateFlow()

    init {
        load()
    }

    fun onIntent(intent: SearchPrivacyIntent) {
        when (intent) {
            SearchPrivacyIntent.Load -> load()
            SearchPrivacyIntent.Toggle -> {
                val learning = state.value.learning ?: return
                act {
                    search.setLearning(!learning).also { r ->
                        if (r is AppResult.Ok) mutableState.update { it.copy(learning = r.value) }
                    }
                }
            }
            SearchPrivacyIntent.ClearHistory ->
                act {
                    search.clearHistory().also { r ->
                        if (r is AppResult.Ok) mutableState.update { it.copy(notice = t("search.card.cleared")) }
                    }
                }
        }
    }

    private fun load() {
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val result = search.learning()
            mutableState.update {
                when (result) {
                    is AppResult.Ok -> it.copy(learning = result.value, busy = false)
                    is AppResult.Err -> it.copy(busy = false, error = result.error.userMessage())
                }
            }
        }
    }

    private fun act(action: suspend () -> AppResult<*>) {
        if (!state.value.canAct) return
        mutableState.update { it.copy(busy = true, error = null, notice = null) }
        viewModelScope.launch {
            val result = action()
            mutableState.update { it.copy(busy = false, error = (result as? AppResult.Err)?.error?.userMessage()) }
        }
    }
}
