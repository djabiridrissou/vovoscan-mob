package com.vovoscan.feature.auth.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.Clock
import com.vovoscan.core.common.SystemClock
import com.vovoscan.core.common.i18n.DeviceLocale
import com.vovoscan.core.common.i18n.DevicePreferences
import com.vovoscan.core.common.i18n.I18n
import com.vovoscan.core.common.i18n.LanguagePicker
import com.vovoscan.core.common.i18n.LanguageRow
import com.vovoscan.core.common.i18n.RichRun
import com.vovoscan.core.common.i18n.RichText
import com.vovoscan.core.common.i18n.UiLanguage
import com.vovoscan.core.common.i18n.UiLocales
import com.vovoscan.core.common.i18n.t
import com.vovoscan.core.common.userMessage
import com.vovoscan.feature.auth.domain.AuthRepository
import com.vovoscan.feature.auth.domain.Change
import com.vovoscan.feature.auth.domain.OnboardingCredits
import com.vovoscan.feature.auth.domain.OnboardingServices
import com.vovoscan.feature.auth.domain.OnboardingSources
import com.vovoscan.feature.auth.domain.OnboardingStep
import com.vovoscan.feature.auth.domain.OnboardingTitle
import com.vovoscan.feature.auth.domain.ProfileChange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the native side opens in the tutorial's place, then comes back. */
enum class OnboardingDestination { Sources, Keys }

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.Welcome,
    /** The steps the progress dots count (welcome and done are not). */
    val dots: Int = OnboardingViewModel.COUNTED.size,
    /** 0-based dot lit now; -1 on welcome and done. */
    val dot: Int = -1,
    // language
    val language: LanguageChoiceUi = LanguageChoiceUi(),
    // reading
    val readingLanguage: String? = null,
    val readingQuery: String = "",
    val readingSaving: Boolean = false,
    // sources
    val sources: OnboardingSources = OnboardingSources(officialAvailable = false, officialOn = false, enabledCount = 0),
    val turningOn: Boolean = false,
    // credits
    val credits: OnboardingCredits = OnboardingCredits(balance = null, ownKeys = false),
    /** Done: a real title of the official source to start with; null without one (the library opens instead). */
    val featured: OnboardingTitle? = null,
    val error: String? = null,
    /** Finished or skipped: the native side closes the tutorial (`SessionViewModel.tutorialClosed`). */
    val finished: Boolean = false,
    /** Opened from the sources or keys step; the native side answers `Returned`. */
    val open: OnboardingDestination? = null,
) {
    val progressLabel: String? get() = if (dot < 0) null else t("onboarding.progress", "step" to dot + 1, "total" to dots)
    val canGoBack: Boolean get() = step.ordinal >= OnboardingStep.Language.ordinal && step != OnboardingStep.Done
    val skipLabel: String get() = t("common.skip")
    val backLabel: String get() = t("common.back")
    val nextLabel: String get() = t("onboarding.next")
    val showSkip: Boolean get() = step != OnboardingStep.Done

    // welcome
    val welcomeTitle: String get() = t("onboarding.welcome.title")
    val welcomeLead: String get() = t("onboarding.welcome.lead")
    val welcomeList: String get() = t("onboarding.welcome.list")
    val welcomeSteps: List<String> get() = (1..4).map { t("onboarding.welcome.step$it") }
    val startLabel: String get() = t("onboarding.welcome.start")
    val welcomeCold: String get() = t("onboarding.welcome.cold")

    // language
    val languageTitle: String get() = t("onboarding.language.title")
    val languageLead: String get() = t("onboarding.language.lead")

    // reading
    val readingTitle: String get() = t("onboarding.reading.title")
    val readingLead: String get() = t("onboarding.reading.lead")
    val readingLabel: String get() = t("onboarding.reading.label")
    val readingChoice: String get() = LanguagePicker.label(readingLanguage) ?: t("prefs.reading.none")
    val readingRows: List<LanguageRow> get() = LanguagePicker.rows(readingQuery, readingLanguage)

    /** The live demo: a balloon of a sample page, in the reading language as it is chosen. */
    val demo: DemoBalloon get() = DemoLines.balloon(readingLanguage)
    val demoKicker: String get() = t("onboarding.demo.kicker")

    // sources
    val sourcesTitle: String get() = t("onboarding.sources.title")
    val sourcesLead: String get() = t("onboarding.sources.lead")
    val sourcesCount: String get() = t("onboarding.sources.count", "count" to sources.enabledCount)
    val officialTitle: String get() = t("onboarding.sources.vovoscan.title")
    val officialBody: String get() = t("onboarding.sources.vovoscan.body")

    /** "Turn on", or "On" once it is. */
    val officialAction: String get() = t(if (sources.officialOn) "onboarding.sources.vovoscan.on" else "onboarding.sources.vovoscan.turnOn")
    val canTurnOnOfficial: Boolean get() = sources.officialAvailable && !sources.officialOn && !turningOn
    val officialUnavailable: String? get() = if (sources.officialAvailable) null else t("onboarding.sources.vovoscan.unavailable")
    val moreTitle: String get() = t("onboarding.sources.more.title")
    val moreBody: String get() = t("onboarding.sources.more.body")
    val moreAction: String get() = t("onboarding.sources.more.action")
    val laterLabel: String get() = t("onboarding.later")

    // credits
    val creditsTitle: String get() = t("onboarding.credits.title")
    val creditsLead: String get() = t("onboarding.credits.lead")
    val creditsOwn: List<RichRun> get() = RichText.runs(t("onboarding.credits.own"))
    val creditsWallet: List<RichRun> get() = RichText.runs(t("onboarding.credits.wallet"))
    val creditsShared: String get() = t("onboarding.credits.shared")

    /** "Your own keys cover everything…" with keys, else "Balance: 120 credits" (none on a server without credits). */
    val creditsStatus: String?
        get() =
            when {
                credits.ownKeys -> t("onboarding.credits.ownKeys")
                else -> credits.balance?.let { t("onboarding.credits.balance", "count" to it) }
            }
    val addKeysLabel: String get() = t("onboarding.credits.addKeys")
    val notNowLabel: String get() = t("onboarding.credits.notNow")
    val walletKicker: String get() = t("onboarding.credits.walletKicker")

    // done
    val doneTitle: String get() = t("onboarding.done.title")
    val doneLead: String get() = t("onboarding.done.lead")
    val doneAction: String get() = t("onboarding.done.action")
    val featuredKicker: String get() = t("onboarding.done.featured")

    /** "Read Le Griot de Guerre", the primary action when there is a title to start with. */
    val featuredAction: String? get() = featured?.let { t("onboarding.done.read", "title" to it.name) }

    /** The balance as the credits card counts up to it ("Balance: 120 credits"). */
    fun balanceLabel(count: Long): String = t("onboarding.credits.balance", "count" to count)
}

sealed interface OnboardingIntent {
    data object Next : OnboardingIntent

    data object Back : OnboardingIntent

    /** Every step: counts as completed, replayable from Settings › Preferences. */
    data object Skip : OnboardingIntent

    data class ChooseLocale(
        val code: String,
    ) : OnboardingIntent

    data class RegionQuery(
        val text: String,
    ) : OnboardingIntent

    data class ChooseRegion(
        val code: String?,
    ) : OnboardingIntent

    data class ReadingQuery(
        val text: String,
    ) : OnboardingIntent

    data class ChooseReading(
        val code: String,
    ) : OnboardingIntent

    data object TurnOnOfficial : OnboardingIntent

    /** "Add a source" / "Add my keys": the native side opens that screen. */
    data class Open(
        val destination: OnboardingDestination,
    ) : OnboardingIntent

    /** Back from the screen [OnboardingUiState.open] named. */
    data object Returned : OnboardingIntent

    data object DismissMessage : OnboardingIntent
}

/**
 * The first-launch tutorial (i18n/SPEC.md §4, `ONBOARDING_VERSION`): welcome, language, reading, sources, credits,
 * done. Every step saves at once (nothing is lost if the app closes); finishing or skipping tells the server
 * (`onboardingCompleted`, sent later when offline).
 */
class OnboardingViewModel(
    private val auth: AuthRepository,
    private val services: OnboardingServices,
    private val uiLanguage: UiLanguage,
    /** The step reached, kept on the device: choosing a language rebuilds the screens, and the app may close. */
    private val prefs: DevicePreferences,
    private val clock: Clock = SystemClock,
) : ViewModel() {
    private var regionQuery = ""
    private val mutableState =
        MutableStateFlow(
            (prefs.get(STEP_KEY)?.let { saved -> OnboardingStep.entries.firstOrNull { it.name == saved } } ?: OnboardingStep.Welcome)
                .let { step ->
                    val readingLanguage = if (step == OnboardingStep.Reading) I18n.locale.value else null
                    OnboardingUiState(step = step, dot = COUNTED.indexOf(step), readingLanguage = readingLanguage)
                },
        )
    val state: StateFlow<OnboardingUiState> = mutableState.asStateFlow()

    init {
        renderLanguage()
        services
            .readingLanguage()
            .onEach { code -> if (code != null && !state.value.readingSaving) mutableState.update { it.copy(readingLanguage = code) } }
            .launchIn(viewModelScope)
        services.sources().onEach { sources -> mutableState.update { it.copy(sources = sources) } }.launchIn(viewModelScope)
        services.credits().onEach { credits -> mutableState.update { it.copy(credits = credits) } }.launchIn(viewModelScope)
        services.featured().onEach { title -> mutableState.update { it.copy(featured = title) } }.launchIn(viewModelScope)
        viewModelScope.launch {
            // The account's time zone comes from the device the first time, when it has none.
            val session = auth.session.value
            if (session != null && session.timeZone == null) {
                auth.updateProfile(ProfileChange(timeZone = Change.Set(DeviceLocale.timeZone())))
                renderLanguage()
            }
        }
        viewModelScope.launch { services.refreshSources() }
        viewModelScope.launch { services.refreshCredits() }
        viewModelScope.launch { services.refreshFeatured() }
    }

    fun onIntent(intent: OnboardingIntent) {
        when (intent) {
            OnboardingIntent.Next -> next()
            OnboardingIntent.Back -> go(OnboardingStep.entries[(state.value.step.ordinal - 1).coerceAtLeast(0)])
            OnboardingIntent.Skip -> finish()
            is OnboardingIntent.ChooseLocale -> chooseLocale(intent.code)
            is OnboardingIntent.RegionQuery -> {
                regionQuery = intent.text
                renderLanguage()
            }
            is OnboardingIntent.ChooseRegion -> chooseRegion(intent.code)
            is OnboardingIntent.ReadingQuery -> mutableState.update { it.copy(readingQuery = intent.text) }
            is OnboardingIntent.ChooseReading ->
                if (LanguagePicker.label(intent.code) !=
                    null
                ) {
                    mutableState.update { it.copy(readingLanguage = intent.code, readingQuery = "") }
                }
            OnboardingIntent.TurnOnOfficial -> turnOnOfficial()
            is OnboardingIntent.Open -> mutableState.update { it.copy(open = intent.destination) }
            OnboardingIntent.Returned -> {
                mutableState.update { it.copy(open = null) }
                viewModelScope.launch { services.refreshSources() }
                viewModelScope.launch { services.refreshCredits() }
            }
            OnboardingIntent.DismissMessage -> mutableState.update { it.copy(error = null) }
        }
    }

    private fun next() {
        val current = state.value
        when (current.step) {
            OnboardingStep.Reading -> saveReading()
            OnboardingStep.Done -> finish()
            else -> go(OnboardingStep.entries[current.step.ordinal + 1])
        }
    }

    private fun go(step: OnboardingStep) {
        prefs.put(STEP_KEY, step.name)
        mutableState.update {
            it.copy(
                step = step,
                dot = COUNTED.indexOf(step),
                error = null,
                // The reading language starts as the app's own (they stay independent afterwards).
                readingLanguage = it.readingLanguage ?: if (step == OnboardingStep.Reading) I18n.locale.value else null,
            )
        }
    }

    private fun chooseLocale(code: String) {
        if (UiLocales.supported(code) == null) return
        uiLanguage.choose(code)
        renderLanguage()
        saveProfile(ProfileChange(locale = code))
    }

    private fun chooseRegion(code: String?) {
        regionQuery = ""
        uiLanguage.choose(I18n.locale.value, code)
        renderLanguage()
        saveProfile(ProfileChange(region = Change.Set(code)))
    }

    private fun saveReading() {
        val current = state.value
        val code = current.readingLanguage ?: I18n.locale.value
        if (current.readingSaving) return
        mutableState.update { it.copy(readingSaving = true, error = null) }
        viewModelScope.launch {
            val result = services.saveReadingLanguage(code)
            mutableState.update { it.copy(readingSaving = false) }
            when {
                result is AppResult.Ok -> go(OnboardingStep.Sources)
                // Offline: the step moves on; Settings › Preferences sets it later.
                (result as AppResult.Err).error == AppError.Network -> go(OnboardingStep.Sources)
                else -> mutableState.update { it.copy(error = result.error.userMessage()) }
            }
        }
    }

    private fun turnOnOfficial() {
        if (!state.value.canTurnOnOfficial) return
        mutableState.update { it.copy(turningOn = true, error = null) }
        viewModelScope.launch {
            val result = services.turnOnOfficial()
            mutableState.update {
                when (result) {
                    is AppResult.Ok -> it.copy(turningOn = false)
                    is AppResult.Err ->
                        it.copy(turningOn = false, error = if (result.error == AppError.Network) null else result.error.userMessage())
                }
            }
            // Its first title is what the tour ends on.
            if (result is AppResult.Ok) services.refreshFeatured()
        }
    }

    private fun finish() {
        if (state.value.finished) return
        prefs.put(STEP_KEY, null)
        mutableState.update { it.copy(finished = true) }
        saveProfile(ProfileChange(onboardingCompleted = true))
    }

    /** Applied on the device at once by the repository; offline it is sent later. Refusals are shown. */
    private fun saveProfile(change: ProfileChange) {
        viewModelScope.launch {
            val result = auth.updateProfile(change)
            if (result is AppResult.Err && result.error != AppError.Network) {
                mutableState.update { it.copy(error = result.error.userMessage()) }
            }
        }
    }

    private fun renderLanguage() {
        mutableState.update { it.copy(language = LanguageChoice.build(I18n.region, regionQuery, clock, auth.session.value?.timeZone)) }
    }

    companion object {
        private const val STEP_KEY = "onboarding_step"

        val COUNTED = listOf(OnboardingStep.Language, OnboardingStep.Reading, OnboardingStep.Sources, OnboardingStep.Credits)
    }
}
