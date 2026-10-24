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
import com.vovoscan.feature.auth.domain.AccountRepository
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
    /** Safe mode: only titles rated for all audiences (unrated ones hidden too). On by default. */
    val safeMode: Boolean = true,
    /** Turning safe mode off asks first ("I'm 18 or older"). */
    val confirmingAdult: Boolean = false,
    val savingSafeMode: Boolean = false,
) {
    val safeModeTitle: String get() = t("prefs.safe.title")
    val safeModeHelp: String get() = t("prefs.safe.help")
    val adultTitle: String get() = t("prefs.safe.confirmTitle")
    val adultBody: String get() = t("prefs.safe.confirmBody")
    val adultAction: String get() = t("prefs.safe.confirmAction")

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

    /** The switch: on applies at once; off asks first ([ConfirmAdult] / [CancelAdult]). */
    data class SetSafeMode(
        val on: Boolean,
    ) : PreferencesIntent

    data object ConfirmAdult : PreferencesIntent

    data object CancelAdult : PreferencesIntent

    data object DismissMessage : PreferencesIntent
}

/**
 * Settings › Preferences: the app's language and region (formatting only, never what can be read). Applied on the
 * device at once and saved on the account (sent later when offline). The reading language is the account slice's
 * `ReadingSettingsViewModel`; "Replay the tour" goes through `SessionViewModel.replayTutorial`.
 */
class PreferencesViewModel(
    private val auth: AuthRepository,
    private val account: AccountRepository,
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
            is PreferencesIntent.SetSafeMode ->
                if (intent.on) {
                    showAdult(false)
                } else if (state.value.safeMode) {
                    mutableState.update { it.copy(confirmingAdult = true) }
                }
            PreferencesIntent.ConfirmAdult -> {
                mutableState.update { it.copy(confirmingAdult = false) }
                showAdult(true)
            }
            PreferencesIntent.CancelAdult -> mutableState.update { it.copy(confirmingAdult = false) }
            PreferencesIntent.DismissMessage -> mutableState.update { it.copy(error = null) }
        }
    }

    /** Online only, never queued: what can be shown changes only once the server agrees. */
    private fun showAdult(on: Boolean) {
        if (state.value.savingSafeMode) return
        mutableState.update { it.copy(savingSafeMode = true, error = null) }
        viewModelScope.launch {
            val result = account.setShowAdult(on)
            mutableState.update {
                it.copy(savingSafeMode = false, error = (result as? AppResult.Err)?.error?.userMessage())
            }
            render()
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
        mutableState.update {
            it.copy(
                language = LanguageChoice.build(I18n.region, query, clock, session?.timeZone),
                safeMode = session?.showAdult != true,
            )
        }
    }
}
