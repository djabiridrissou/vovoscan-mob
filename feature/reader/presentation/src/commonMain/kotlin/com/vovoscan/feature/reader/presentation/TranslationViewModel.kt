package com.vovoscan.feature.reader.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.Language
import com.vovoscan.core.common.i18n.t
import com.vovoscan.core.common.userMessage
import com.vovoscan.feature.reader.domain.ChapterTranslation
import com.vovoscan.feature.reader.domain.Lettering
import com.vovoscan.feature.reader.domain.LetteringLayout
import com.vovoscan.feature.reader.domain.TranslationQuotes
import com.vovoscan.feature.reader.domain.TranslationStatus
import com.vovoscan.feature.reader.domain.TranslationsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A row of the language picker: "Original" (code null), then every language, those already made first. */
data class LanguageOption(
    val code: String?,
    /** "Français" (the language's own name). */
    val label: String,
    /** "French"; empty for "Original". */
    val englishName: String,
    /** "Ready", "Translating ✦ 42%", "Failed"; null when nobody asked for it yet. */
    val statusLabel: String?,
    val ready: Boolean,
    val selected: Boolean,
)

/**
 * A language nobody has made yet, before anything is paid: what it costs this account, and what can be done
 * ("Translate", or "Add a key" when the wallet can't pay). Never a dead end.
 */
data class TranslationOffer(
    val code: String,
    val title: String,
    val line: String,
    /** False when nobody can pay (no key, wallet short): only "Add a key" and "Not now". */
    val canTranslate: Boolean,
    val translateLabel: String,
    val cancelLabel: String,
    /** Shown when the account's own key would make it free (always when it can't be paid). */
    val addKeyLabel: String?,
)

/**
 * The panel docked over the reader while a language is not on the page yet (reader SPEC §11): its cost first, then its
 * progress while the reader keeps reading, or why it failed. The page keeps the lettering it had until the new one is
 * ready, then swaps in place. Never a dead end: there is always a way on (Translate, Add a key, Try again) or back.
 */
data class TranslationDock(
    val title: String,
    val line: String?,
    /** 0..1 for the bar while it is made; null: no bar. */
    val progress: Float?,
    /** A spinner: the cost is being checked, or the run waits in the queue. */
    val busy: Boolean,
    /** "Translate", when the offer can be paid. */
    val confirmLabel: String?,
    /** "Add a key": the account's own key makes it free (always offered when nothing else can pay). */
    val addKeyLabel: String?,
    /** "Try again" after a failure. */
    val retryLabel: String?,
    /** "Not now" / "Cancel" / "Close": back to the lettering on the page ([TranslationIntent.CloseDock]). */
    val closeLabel: String,
    val failed: Boolean,
)

data class TranslationUiState(
    /** Translations exist for chapters of our catalog only: elsewhere the reader shows no language button. */
    val available: Boolean = false,
    val selected: String? = null,
    /** The reader's language button: "Original", "Français". */
    val buttonLabel: String = TranslationText.ORIGINAL,
    val query: String = "",
    /** The picker's rows, filtered by [query] (name, own name or code). */
    val options: List<LanguageOption> = emptyList(),
    /** Asking for, or waiting on, the selected translation. */
    val working: Boolean = false,
    /** "Translating ✦ 42%" while the selected language is made. */
    val progressLabel: String? = null,
    val notice: String? = null,
    val error: String? = null,
    /** Lettering of every translated page, by page index. */
    val lettering: Map<Int, List<Lettering>> = emptyMap(),
    /** "Translate into Yoruba?" with its cost, while the reader decides. */
    val offer: TranslationOffer? = null,
    /** "Checking the cost…" while the quote comes. */
    val checking: String? = null,
    /** The language whose lettering is on the page now (null: the original); [selected] may be on its way. */
    val shown: String? = null,
    /** 0..1 while the selected language is made; null when unknown. */
    val progress: Float? = null,
) {
    /** The panel over the reader; null when the page shows what was asked. */
    val dock: TranslationDock?
        get() {
            val target = offer?.code ?: selected
            val language = target?.let { Language.of(it)?.native } ?: target.orEmpty()
            return when {
                offer != null ->
                    TranslationDock(
                        title = offer.title,
                        line = offer.line,
                        progress = null,
                        busy = false,
                        confirmLabel = offer.translateLabel.takeIf { offer.canTranslate },
                        addKeyLabel = offer.addKeyLabel,
                        retryLabel = null,
                        closeLabel = offer.cancelLabel,
                        failed = false,
                    )
                checking != null -> TranslationDock(checking, null, null, true, null, null, null, TranslationText.NOT_NOW, false)
                working && selected != null ->
                    TranslationDock(
                        title = t("translation.dock.making", "language" to language),
                        line = listOfNotNull(progressLabel, TranslationText.KEEP_READING).joinToString(" ✦ "),
                        progress = progress,
                        busy = progress == null,
                        confirmLabel = null,
                        addKeyLabel = null,
                        retryLabel = null,
                        closeLabel = TranslationText.CANCEL,
                        failed = false,
                    )
                error != null ->
                    TranslationDock(
                        title = language.ifEmpty { TranslationText.ORIGINAL },
                        line = error,
                        progress = null,
                        busy = false,
                        confirmLabel = null,
                        addKeyLabel = TranslationText.ADD_KEY.takeIf { error == TranslationText.NO_CREDITS },
                        retryLabel = TranslationText.TRY_AGAIN,
                        closeLabel = TranslationText.CLOSE,
                        failed = true,
                    )
                else -> null
            }
        }

    /** The end card's way to the picker: "Read it in another language". */
    val endCardLabel: String get() = TranslationText.END_CARD

    /** What to draw over page [index] (empty for the original lettering). */
    fun letteringFor(index: Int): List<Lettering> = lettering[index] ?: emptyList()

    val translated: Boolean get() = lettering.isNotEmpty()
}

sealed interface TranslationIntent {
    data class Query(
        val value: String,
    ) : TranslationIntent

    /** A language, or null for the original lettering. */
    data class Choose(
        val code: String?,
    ) : TranslationIntent

    data object Retry : TranslationIntent

    /** "Translate" on the offer: the translation starts, paid as the offer said. */
    data object ConfirmOffer : TranslationIntent

    /** "Not now" (or the sheet closed): nothing is asked, the page stays as it was. */
    data object DismissOffer : TranslationIntent

    data object DismissMessage : TranslationIntent

    /** The dock's "Not now" / "Cancel" / "Close": back to the lettering on the page, whatever the dock showed. */
    data object CloseDock : TranslationIntent
}

/**
 * The reader's language: the chapter's translations (made once per language, shared by everyone, free when they
 * exist), asking for a new one (paid by the user's keys or credits), following it until ready, and the lettering
 * laid over each page (`LetteringLayout`, the web reader's rules). Online only.
 */
class TranslationViewModel(
    private val sourceKey: String,
    private val chapterId: String,
    private val repository: TranslationsRepository,
    private val quotes: TranslationQuotes = TranslationQuotes.None,
) : ViewModel() {
    private val mutableState = MutableStateFlow(TranslationUiState(available = sourceKey == INTERNAL_SOURCE))
    val state: StateFlow<TranslationUiState> = mutableState.asStateFlow()
    private var known: Map<String, ChapterTranslation> = emptyMap()
    private var job: Job? = null

    init {
        if (state.value.available) {
            render()
            viewModelScope.launch { reloadLanguages() }
        }
    }

    fun onIntent(intent: TranslationIntent) {
        if (!state.value.available) return
        when (intent) {
            is TranslationIntent.Query -> {
                mutableState.update { it.copy(query = intent.value) }
                render()
            }
            is TranslationIntent.Choose -> offerOrChoose(intent.code)
            TranslationIntent.Retry -> choose(state.value.selected)
            TranslationIntent.ConfirmOffer ->
                state.value.offer
                    ?.takeIf { it.canTranslate }
                    ?.let { choose(it.code) }
            TranslationIntent.DismissOffer -> {
                quoteJob?.cancel()
                mutableState.update { it.copy(offer = null, checking = null) }
            }
            TranslationIntent.DismissMessage -> mutableState.update { it.copy(notice = null, error = null) }
            TranslationIntent.CloseDock -> closeDock()
        }
    }

    private var quoteJob: Job? = null

    /**
     * Back to what the page shows. A run already asked for goes on on the server (made once, shared): only this reader
     * stops waiting for it; picking the language again finds it further along, or ready.
     */
    private fun closeDock() {
        quoteJob?.cancel()
        job?.cancel()
        mutableState.update {
            it.copy(
                offer = null,
                checking = null,
                working = false,
                progressLabel = null,
                progress = null,
                notice = null,
                error = null,
                selected = it.shown,
            )
        }
        render()
    }

    /**
     * A language already made (or the original) shows at once, free. One nobody made yet is quoted first: own key →
     * free, else its credits; started only on "Translate". Without a quote (no credits on the server, offline) it is
     * asked straight away and the server's answer says the rest.
     */
    private fun offerOrChoose(code: String?) {
        quoteJob?.cancel()
        mutableState.update { it.copy(offer = null, checking = null) }
        if (code == null ||
            Language.of(code) == null ||
            known[code]?.status == TranslationStatus.Ready ||
            known[code]?.status?.inProgress == true
        ) {
            return choose(code)
        }
        mutableState.update { it.copy(checking = TranslationText.CHECKING) }
        quoteJob =
            viewModelScope.launch {
                val quote = quotes.quote(chapterId, code)
                mutableState.update { it.copy(checking = null) }
                if (quote == null || quote.alreadyAvailable) return@launch choose(code)
                val language = Language.of(code)?.native ?: code
                mutableState.update {
                    it.copy(
                        offer =
                            TranslationOffer(
                                code = code,
                                title = t("translation.offer.title", "language" to language),
                                line =
                                    when {
                                        quote.coveredByOwnKeys -> t("translation.offer.ownKeys")
                                        quote.fundable -> t("translation.offer.credits", "credits" to quote.credits)
                                        else -> t("translation.offer.short")
                                    },
                                canTranslate = quote.fundable || quote.coveredByOwnKeys,
                                translateLabel = t("translation.offer.translate"),
                                cancelLabel = t("translation.offer.cancel"),
                                addKeyLabel = if (quote.coveredByOwnKeys) null else t("translation.offer.addKey"),
                            ),
                    )
                }
            }
    }

    private fun choose(code: String?) {
        job?.cancel()
        mutableState.update { it.copy(offer = null) }
        if (code == null || Language.of(code) == null) {
            mutableState.update {
                it.copy(
                    selected = null,
                    shown = null,
                    lettering = emptyMap(),
                    working = false,
                    progressLabel = null,
                    progress = null,
                    error = null,
                )
            }
            render()
            return
        }
        // The page keeps the lettering it has until the new one is ready (reader SPEC §11): it never goes blank.
        mutableState.update { it.copy(selected = code, working = true, progress = null, notice = null, error = null) }
        render()
        job =
            viewModelScope.launch {
                val current = known[code]
                if (current?.status != TranslationStatus.Ready) {
                    when (val asked = repository.request(chapterId, code)) {
                        is AppResult.Err -> return@launch failed(asked.error)
                        is AppResult.Ok -> remember(asked.value)
                    }
                    if (known[code]?.status?.inProgress == true) {
                        mutableState.update { it.copy(notice = TranslationText.STARTED) }
                        if (!waitUntilDone(code)) return@launch
                    }
                    if (known[code]?.status == TranslationStatus.Failed) return@launch failed(null)
                }
                when (val pages = repository.pages(chapterId, code)) {
                    is AppResult.Err -> failed(pages.error)
                    is AppResult.Ok -> {
                        // The supported targets are written horizontally: vertical lettering is widened (verticalTarget false).
                        val lettering = pages.value.associate { it.index to LetteringLayout.page(it) }
                        mutableState.update {
                            it.copy(
                                working = false,
                                progressLabel = null,
                                progress = null,
                                lettering = lettering,
                                shown = code,
                                notice = null,
                            )
                        }
                    }
                }
                render()
            }
    }

    /** Polls the languages while the selected one is made; false when it failed or the screen moved on. */
    private suspend fun waitUntilDone(code: String): Boolean {
        while (true) {
            delay(POLL_MS)
            reloadLanguages()
            val status = known[code]?.status ?: return false
            if (status == TranslationStatus.Ready) return true
            if (!status.inProgress) return status != TranslationStatus.Failed
        }
    }

    private suspend fun reloadLanguages() {
        val result = repository.languages(chapterId)
        if (result is AppResult.Ok) {
            known = result.value.associateBy { it.lang }
            render()
        }
    }

    private fun remember(translation: ChapterTranslation) {
        known = known + (translation.lang to translation)
        render()
    }

    private fun failed(error: AppError?) {
        mutableState.update {
            it.copy(
                working = false,
                progressLabel = null,
                progress = null,
                notice = null,
                error = error?.let(TranslationText::error) ?: TranslationText.FAILED,
            )
        }
        render()
    }

    private fun render() {
        mutableState.update { state ->
            val selected = state.selected
            val needle = state.query.trim().lowercase()
            val languages =
                Language.ALL
                    .filter { it.matches(needle) }
                    .sortedBy { if (known[it.code]?.status == TranslationStatus.Ready) 0 else 1 }
                    .map { language ->
                        val translation = known[language.code]
                        LanguageOption(
                            code = language.code,
                            label = language.native,
                            englishName = language.name,
                            statusLabel = translation?.let(TranslationText::status),
                            ready = translation?.status == TranslationStatus.Ready,
                            selected = language.code == selected,
                        )
                    }
            val original = LanguageOption(null, TranslationText.ORIGINAL, "", null, ready = true, selected = selected == null)
            val running = selected?.let { known[it] }?.takeIf { it.status.inProgress }
            state.copy(
                options = if (needle.isEmpty()) listOf(original) + languages else languages,
                // The button names what the page shows; the dock names what is on its way.
                buttonLabel = state.shown?.let { Language.of(it)?.native } ?: TranslationText.ORIGINAL,
                progressLabel = if (state.working && running != null) TranslationText.status(running) else state.progressLabel,
                progress =
                    if (state.working && running != null) {
                        running.progress?.let { it.coerceIn(0, 100) / 100f }
                    } else {
                        state.progress
                    },
            )
        }
    }

    companion object {
        /** The only source whose chapters the server translates. */
        const val INTERNAL_SOURCE = "vovoscan"
        private const val POLL_MS = 4_000L
    }
}

/** The picker's search: the English name, the language's own name, or its code. */
private fun Language.matches(needle: String): Boolean =
    needle.isEmpty() || needle in name.lowercase() || needle in native.lowercase() || code.lowercase() == needle

object TranslationText {
    val ORIGINAL: String get() = t("translation.original")
    val READY: String get() = t("translation.ready")
    val QUEUED: String get() = t("translation.queued")
    val FAILED: String get() = t("translation.failed")
    val STARTED: String get() = t("translation.started")
    val OFFLINE: String get() = t("translation.offline")
    val NO_CREDITS: String get() = t("translation.noCredits")
    val PAUSED: String get() = t("translation.paused")
    val CHECKING: String get() = t("translation.offer.checking")
    val DAILY_CAP: String get() = t("translation.dailyCap")
    val NOT_READABLE: String get() = t("translation.notReadable")
    val KEEP_READING: String get() = t("translation.dock.keepReading")
    val NOT_NOW: String get() = t("translation.offer.cancel")
    val CANCEL: String get() = t("common.cancel")
    val CLOSE: String get() = t("common.close")
    val TRY_AGAIN: String get() = t("common.tryAgain")
    val ADD_KEY: String get() = t("translation.offer.addKey")
    val END_CARD: String get() = t("translation.endCard")

    fun status(translation: ChapterTranslation): String? =
        when (translation.status) {
            TranslationStatus.Ready -> READY
            TranslationStatus.Queued -> QUEUED
            TranslationStatus.Running ->
                translation.progress?.let { t("translation.progress", "percent" to it.coerceIn(0, 100)) } ?: t("translation.running")
            TranslationStatus.Failed -> t("translation.failedShort")
            TranslationStatus.None -> null
        }

    fun error(error: AppError): String =
        when {
            error == AppError.Network -> OFFLINE
            error is AppError.Rejected && error.code == "INSUFFICIENT_CREDITS" -> NO_CREDITS
            error is AppError.Rejected && error.code == "PLATFORM_PAUSED" -> PAUSED
            error is AppError.Rejected && error.code == "DAILY_CAP" -> DAILY_CAP
            error is AppError.Rejected && error.code == "NOT_READABLE" -> NOT_READABLE
            else -> error.userMessage()
        }
}
