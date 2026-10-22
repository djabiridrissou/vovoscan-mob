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
) {
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

    data object DismissMessage : TranslationIntent
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
            is TranslationIntent.Choose -> choose(intent.code)
            TranslationIntent.Retry -> choose(state.value.selected)
            TranslationIntent.DismissMessage -> mutableState.update { it.copy(notice = null, error = null) }
        }
    }

    private fun choose(code: String?) {
        job?.cancel()
        if (code == null || Language.of(code) == null) {
            mutableState.update { it.copy(selected = null, lettering = emptyMap(), working = false, progressLabel = null, error = null) }
            render()
            return
        }
        mutableState.update { it.copy(selected = code, lettering = emptyMap(), working = true, notice = null, error = null) }
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
                        mutableState.update { it.copy(working = false, progressLabel = null, lettering = lettering, notice = null) }
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
                buttonLabel = selected?.let { Language.of(it)?.native } ?: TranslationText.ORIGINAL,
                progressLabel = if (state.working && running != null) TranslationText.status(running) else state.progressLabel,
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
    val DAILY_CAP: String get() = t("translation.dailyCap")
    val NOT_READABLE: String get() = t("translation.notReadable")

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
