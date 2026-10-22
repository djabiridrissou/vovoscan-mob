package com.vovoscan.feature.auth.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.Clock
import com.vovoscan.core.common.SystemClock
import com.vovoscan.core.common.i18n.I18n
import com.vovoscan.core.common.i18n.UiLanguage
import com.vovoscan.core.common.i18n.UiLocales
import com.vovoscan.core.common.i18n.t
import com.vovoscan.core.common.userMessage
import com.vovoscan.feature.auth.domain.AuthRepository
import com.vovoscan.feature.auth.domain.Change
import com.vovoscan.feature.auth.domain.ProfileChange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PreferencesUiState(
    val language: LanguageChoiceUi = LanguageChoiceUi(),
    val error: String? = null,
) {
    val lead: String get() = t("prefs.lead")
    val tourTitle: String get() = t("prefs.tour.title")
    val tourLead: String get() = t("prefs.tour.lead")
    val tourAction: String get() = t("prefs.tour.action")
    val readingTitle: String get() = t("prefs.reading.title")
}

sealed interface PreferencesIntent {
    /** The UI language: the screens are rebuilt in it (the native root follows `I18n.locale`). */
    data class ChooseLocale(
        val code: String,
    ) : PreferencesIntent

    data class RegionQuery(
        val text: String,
    ) : PreferencesIntent

    /** null = not set. */
    data class ChooseRegion(
        val code: String?,
    ) : PreferencesIntent

    data object DismissMessage : PreferencesIntent
}

/**
 * Settings › Preferences: the app's language and region (formatting only, never what can be read). Applied on the
 * device at once and saved on the account (sent later when offline). The reading language is the account slice's
 * `ReadingSettingsViewModel`; "Replay the tour" goes through `SessionViewModel.replayTutorial`.
 */
class PreferencesViewModel(
    private val auth: AuthRepository,
    private val uiLanguage: UiLanguage,
    private val clock: Clock = SystemClock,
) : ViewModel() {
    private var query = ""
    private val mutableState = MutableStateFlow(PreferencesUiState())
    val state: StateFlow<PreferencesUiState> = mutableState.asStateFlow()

    init {
        render()
        auth.session.onEach { render() }.launchIn(viewModelScope)
    }

    fun onIntent(intent: PreferencesIntent) {
        when (intent) {
            is PreferencesIntent.ChooseLocale -> save(ProfileChange(locale = intent.code)) { uiLanguage.choose(intent.code) }
            is PreferencesIntent.RegionQuery -> {
                query = intent.text
                render()
            }
            is PreferencesIntent.ChooseRegion -> {
                query = ""
                save(ProfileChange(region = Change.Set(intent.code))) { uiLanguage.choose(I18n.locale.value, intent.code) }
            }
            PreferencesIntent.DismissMessage -> mutableState.update { it.copy(error = null) }
        }
    }

    private fun save(
        change: ProfileChange,
        applyOnDevice: () -> Unit,
    ) {
        if (change.locale != null && UiLocales.supported(change.locale) == null) return
        // Signed out (never here today) or not: the device follows at once.
        applyOnDevice()
        render()
        viewModelScope.launch {
            val result = auth.updateProfile(change)
            if (result is AppResult.Err) mutableState.update { it.copy(error = result.error.userMessage()) }
        }
    }

    private fun render() {
        val session = auth.session.value
        mutableState.update { it.copy(language = LanguageChoice.build(I18n.region, query, clock, session?.timeZone)) }
    }
}
