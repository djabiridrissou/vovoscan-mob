package com.vovoscan.feature.account.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.Freshness
import com.vovoscan.feature.account.domain.ReadingModel
import com.vovoscan.feature.account.domain.ReadingSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ModelOption(
    val model: ReadingModel,
    /** "Haiku — fastest, cheapest". */
    val label: String,
    val selected: Boolean,
)

data class ReadingSettingsUiState(
    /** The server's choice (kept on the device), Haiku until it is known. */
    val selected: ReadingModel = ReadingModel.Default,
    /** The model being saved, shown as selected meanwhile. */
    val saving: ReadingModel? = null,
    val freshness: Freshness = Freshness.Refreshing,
    val notice: String? = null,
    val error: String? = null,
) {
    val options: List<ModelOption>
        get() = ReadingModel.entries.map { ModelOption(it, it.label, it == (saving ?: selected)) }

    val footnote: String get() = AccountText.MODEL_FOOTNOTE
}

sealed interface ReadingSettingsIntent {
    data object Refresh : ReadingSettingsIntent

    data class Select(
        val model: ReadingModel,
    ) : ReadingSettingsIntent

    data object DismissMessage : ReadingSettingsIntent
}

/** The Claude model that reads the bubbles of the chapters the user prepares. A change needs the network. */
class ReadingSettingsViewModel(
    private val repository: ReadingSettingsRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ReadingSettingsUiState())
    val state: StateFlow<ReadingSettingsUiState> = mutableState.asStateFlow()

    init {
        repository
            .observeReadingModel()
            .onEach { model ->
                if (model !=
                    null
                ) {
                    mutableState.update { it.copy(selected = model) }
                }
            }.launchIn(viewModelScope)
        refresh()
    }

    fun onIntent(intent: ReadingSettingsIntent) {
        when (intent) {
            ReadingSettingsIntent.Refresh -> refresh()
            is ReadingSettingsIntent.Select -> select(intent.model)
            ReadingSettingsIntent.DismissMessage -> mutableState.update { it.copy(notice = null, error = null) }
        }
    }

    private fun select(model: ReadingModel) {
        val current = state.value
        if (current.saving != null || model == current.selected) return
        mutableState.update { it.copy(saving = model, notice = null, error = null) }
        viewModelScope.launch {
            val result = repository.set(model)
            mutableState.update {
                when (result) {
                    is AppResult.Ok -> it.copy(saving = null, selected = result.value, notice = AccountText.MODEL_SAVED)
                    // The previous choice stays selected.
                    is AppResult.Err -> it.copy(saving = null, error = AccountText.modelError(result.error))
                }
            }
        }
    }

    private fun refresh() {
        mutableState.update { it.copy(freshness = Freshness.Refreshing, error = null) }
        viewModelScope.launch {
            val result = repository.refreshReadingModel()
            mutableState.update {
                when (result) {
                    is AppResult.Ok -> it.copy(freshness = Freshness.Fresh)
                    is AppResult.Err ->
                        it.copy(
                            freshness = Freshness.Offline,
                            error = if (result.error == AppError.Network) null else AccountText.modelError(result.error),
                        )
                }
            }
        }
    }
}
