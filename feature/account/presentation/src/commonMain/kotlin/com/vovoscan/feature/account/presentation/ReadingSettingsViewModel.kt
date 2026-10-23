package com.vovoscan.feature.account.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.Freshness
import com.vovoscan.core.common.i18n.LanguagePicker
import com.vovoscan.core.common.i18n.LanguageRow
import com.vovoscan.core.common.i18n.t
import com.vovoscan.feature.account.domain.Provider
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

/** One text provider in the account's order: "1  Gemini", with its moves. */
data class TextProviderRow(
    val provider: Provider,
    val label: String,
    val position: Int,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
)

data class ReadingSettingsUiState(
    /** The server's choice (kept on the device), Haiku until it is known. */
    val selected: ReadingModel = ReadingModel.Default,
    /** The model being saved, shown as selected meanwhile. */
    val saving: ReadingModel? = null,
    val freshness: Freshness = Freshness.Refreshing,
    val notice: String? = null,
    val error: String? = null,
    /** The content language chapters are translated into; null until chosen. */
    val targetLanguage: String? = null,
    /** The language being saved, shown as chosen meanwhile. */
    val savingLanguage: String? = null,
    val languageQuery: String = "",
    /** The order text providers are tried in; null until the server said it (an older server never does). */
    val textOrder: List<Provider>? = null,
    /** The order being saved, shown meanwhile. */
    val savingOrder: List<Provider>? = null,
) {
    /** Empty while the server's order is unknown: nothing to show. */
    val textRows: List<TextProviderRow>
        get() =
            (savingOrder ?: textOrder).orEmpty().let { order ->
                order.mapIndexed { i, p ->
                    TextProviderRow(
                        p,
                        p.label,
                        i + 1,
                        i > 0 && savingOrder == null,
                        i < order.lastIndex && savingOrder == null,
                    )
                }
            }

    val textOrderTitle: String get() = t("keys.textOrder")
    val textOrderHint: String get() = t("keys.textOrderHint")
    val moveUpLabel: String get() = t("keys.moveUp")
    val moveDownLabel: String get() = t("keys.moveDown")

    val options: List<ModelOption>
        get() = ReadingModel.entries.map { ModelOption(it, it.label, it == (saving ?: selected)) }

    val footnote: String get() = AccountText.MODEL_FOOTNOTE

    /** "Français", or "Not chosen yet". */
    val languageLabel: String get() = LanguagePicker.label(savingLanguage ?: targetLanguage) ?: t("prefs.reading.none")

    val languageHint: String get() = t("prefs.reading.hint")

    /** The picker's rows, filtered by [languageQuery]. */
    val languageRows: List<LanguageRow> get() = LanguagePicker.rows(languageQuery, savingLanguage ?: targetLanguage)

    /** The discreet banner while the copy on the device is shown offline. */
    val offlineNotice: String? get() = if (freshness == Freshness.Offline) AccountText.OFFLINE_COPY else null
}

sealed interface ReadingSettingsIntent {
    data object Refresh : ReadingSettingsIntent

    data class Select(
        val model: ReadingModel,
    ) : ReadingSettingsIntent

    data class LanguageQuery(
        val text: String,
    ) : ReadingSettingsIntent

    /** The content language chapters are translated into (a `Language.ALL` code). */
    data class ChooseLanguage(
        val code: String,
    ) : ReadingSettingsIntent

    /** Moves a text provider one place up (-1) or down (+1) in the order they are tried in. */
    data class MoveTextProvider(
        val provider: Provider,
        val by: Int,
    ) : ReadingSettingsIntent

    data object DismissMessage : ReadingSettingsIntent
}

/**
 * The Claude model that reads the bubbles of the chapters the user prepares, and the language chapters are translated
 * into. A change needs the network.
 */
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
        repository
            .observeTextProviders()
            .onEach { order -> if (order != null) mutableState.update { it.copy(textOrder = order) } }
            .launchIn(viewModelScope)
        repository
            .observeTargetLanguage()
            .onEach { code -> if (code != null) mutableState.update { it.copy(targetLanguage = code) } }
            .launchIn(viewModelScope)
        refresh()
    }

    fun onIntent(intent: ReadingSettingsIntent) {
        when (intent) {
            ReadingSettingsIntent.Refresh -> refresh()
            is ReadingSettingsIntent.Select -> select(intent.model)
            is ReadingSettingsIntent.LanguageQuery -> mutableState.update { it.copy(languageQuery = intent.text) }
            is ReadingSettingsIntent.ChooseLanguage -> chooseLanguage(intent.code)
            is ReadingSettingsIntent.MoveTextProvider -> move(intent.provider, intent.by)
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

    private fun chooseLanguage(code: String) {
        val current = state.value
        if (current.savingLanguage != null || code == current.targetLanguage || LanguagePicker.label(code) == null) return
        mutableState.update { it.copy(savingLanguage = code, languageQuery = "", notice = null, error = null) }
        viewModelScope.launch {
            val result = repository.setTargetLanguage(code)
            mutableState.update {
                when (result) {
                    is AppResult.Ok -> it.copy(savingLanguage = null, targetLanguage = result.value, notice = t("common.saved"))
                    is AppResult.Err -> it.copy(savingLanguage = null, error = AccountText.modelError(result.error))
                }
            }
        }
    }

    private fun move(
        provider: Provider,
        by: Int,
    ) {
        val current = state.value
        val order = current.textOrder ?: return
        if (current.savingOrder != null) return
        val from = order.indexOf(provider)
        val to = from + by
        if (from < 0 || to !in order.indices) return
        val next = order.toMutableList().apply { add(to, removeAt(from)) }
        mutableState.update { it.copy(savingOrder = next, notice = null, error = null) }
        viewModelScope.launch {
            val result = repository.setTextProviders(next)
            mutableState.update {
                when (result) {
                    is AppResult.Ok -> it.copy(savingOrder = null, textOrder = result.value, notice = t("common.saved"))
                    // The previous order stays.
                    is AppResult.Err -> it.copy(savingOrder = null, error = AccountText.modelError(result.error))
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
