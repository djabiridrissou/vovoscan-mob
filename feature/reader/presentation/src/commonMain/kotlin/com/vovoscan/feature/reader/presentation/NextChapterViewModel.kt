package com.vovoscan.feature.reader.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.Language
import com.vovoscan.core.common.i18n.t
import com.vovoscan.feature.reader.domain.EndState
import com.vovoscan.feature.reader.domain.NextChapterRules
import com.vovoscan.feature.reader.domain.NextChapters
import com.vovoscan.feature.reader.domain.NextChaptersRepository
import com.vovoscan.feature.reader.domain.Price
import com.vovoscan.feature.reader.domain.Upcoming
import com.vovoscan.feature.reader.domain.chapterLabel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What a button of the next-chapter UI does; the screen runs it (open a chapter, the keys, a link) or sends an intent. */
enum class NextActionKind {
    /** Open [NextButton.chapterId] in [NextButton.language] (null: the original), replacing this reader. */
    Open,

    /** [NextChapterIntent.PrepareNext] (end card) or [NextChapterIntent.AcceptOffer] (bubble). */
    Prepare,

    /** The account's keys: nothing else can pay. */
    AddKey,

    /** [NextButton.url] in the browser: the source links the next chapter. */
    OpenUrl,
}

data class NextButton(
    val label: String,
    val kind: NextActionKind,
    val chapterId: String? = null,
    val language: String? = null,
    val url: String? = null,
    val enabled: Boolean = true,
)

/**
 * The end card's way on while reading in a language (next-chapter contract rule 1): always one, whatever the next
 * chapter's state. Null in the original or before the list is known: the card's own "Next chapter" stands.
 */
data class NextEndCard(
    /** "Chapter 4 isn't in Français yet." / "Preparing Chapter 4 in Français ✦ 42%" / "This is the latest…". */
    val note: String?,
    val primary: NextButton?,
    /** "Read it in the original". */
    val secondary: NextButton?,
    /** 0..1 while the next chapter is prepared. */
    val progress: Float?,
    val error: String?,
)

/** The suggestion balloon (rule 2): past 40 % of a chapter read in a language. */
data class NextBubble(
    /** "Preparing Chapter 4 in Français ✦ 42%" / "Preparing 5 chapters in Français…". */
    val busyLine: String?,
    val progress: Float?,
    /** Takes back what was queued and has not started. */
    val cancelLabel: String?,
    /** "Chapter 4 isn't in Français yet. Prepare it while you read?" / "Prepare the next 5 too?". */
    val offerLine: String?,
    val offer: NextButton?,
    val error: String?,
    val hideLabel: String,
)

/** "Chapter 4 is ready in Français." with "Read it →"; gone after 8 s. */
data class NextToast(
    val line: String,
    val button: NextButton,
)

/** "Always prepare the next chapter of this series in Français" (settings sheet and end card). */
data class NextAutoSwitch(
    val label: String,
    val on: Boolean,
    val enabled: Boolean,
)

data class NextChapterUiState(
    /** Our catalog's chapters only: a source's chapter has no translations on the server until it is brought in. */
    val available: Boolean = false,
    val language: String? = null,
    val endCard: NextEndCard? = null,
    val bubble: NextBubble? = null,
    val toast: NextToast? = null,
    val autoSwitch: NextAutoSwitch? = null,
    /** A request is on its way: the buttons wait. */
    val busy: Boolean = false,
)

sealed interface NextChapterIntent {
    /** The language whose lettering is on the page (null: the original). */
    data class LanguageShown(
        val code: String?,
    ) : NextChapterIntent

    /** How much of the chapter is read, 0..1 (the bubble at 40 %, the series setting at 70 %). */
    data class Reached(
        val share: Float,
    ) : NextChapterIntent

    /** The end card is on screen: the balloon steps aside for it. */
    data class AtEnd(
        val atEnd: Boolean,
    ) : NextChapterIntent

    /** The end card's "Prepare it": the next chapter, at the price shown. */
    data object PrepareNext : NextChapterIntent

    /** The balloon's offer: the next chapter, then the next 5, then 10, at the price shown. */
    data object AcceptOffer : NextChapterIntent

    data object Cancel : NextChapterIntent

    data class SetAuto(
        val on: Boolean,
    ) : NextChapterIntent

    data object HideBubble : NextChapterIntent

    data object DismissToast : NextChapterIntent
}

/**
 * What comes after a chapter of our catalog in the reading language (next-chapter contract, the web's `next.tsx`): the
 * end card's way on, the balloon that prepares ahead, the toast when one is ready, the series setting. The rules are
 * `NextChapterRules`; this follows the list (every 3 s while something is prepared) and acts on it. The server refuses
 * to spend above the figure shown (`maxCredits`). Online only: offline, the reader's own "Next chapter" stands.
 */
class NextChapterViewModel(
    sourceKey: String,
    private val chapterId: String,
    private val repository: NextChaptersRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(NextChapterUiState(available = sourceKey == INTERNAL))
    val state: StateFlow<NextChapterUiState> = mutableState.asStateFlow()

    private var next: NextChapters? = null
    private var reach = 0f
    private var atEnd = false
    private var bubbleClosed = false
    private var autoAsked = false
    private var error: String? = null
    private var ready: List<Upcoming> = emptyList()
    private var loadJob: Job? = null
    private var pollJob: Job? = null
    private var toastJob: Job? = null

    fun onIntent(intent: NextChapterIntent) {
        if (!state.value.available) return
        when (intent) {
            is NextChapterIntent.LanguageShown -> choose(intent.code)
            is NextChapterIntent.Reached -> {
                if (intent.share <= reach) return
                reach = intent.share
                maybeAuto()
                render()
            }
            is NextChapterIntent.AtEnd -> {
                atEnd = intent.atEnd
                render()
            }
            NextChapterIntent.PrepareNext -> {
                val now = next ?: return
                val first = now.upcoming.firstOrNull() ?: return
                prefetch(1, NextChapterRules.priceOf(now, listOf(first)))
            }
            NextChapterIntent.AcceptOffer -> {
                val offer = next?.let(NextChapterRules::offer) ?: return
                prefetch(offer.count, offer.price)
            }
            NextChapterIntent.Cancel -> {
                val lang = state.value.language ?: return
                act { repository.cancel(chapterId, lang) }
            }
            is NextChapterIntent.SetAuto -> setAuto(intent.on)
            NextChapterIntent.HideBubble -> {
                bubbleClosed = true
                render()
            }
            NextChapterIntent.DismissToast -> {
                toastJob?.cancel()
                ready = emptyList()
                render()
            }
        }
    }

    private fun choose(code: String?) {
        val lang = code?.takeIf { Language.of(it) != null }
        if (lang == state.value.language && (lang == null || next != null || loadJob?.isActive == true)) return
        loadJob?.cancel()
        pollJob?.cancel()
        toastJob?.cancel()
        next = null
        ready = emptyList()
        error = null
        bubbleClosed = false
        autoAsked = false
        mutableState.update { it.copy(language = lang, busy = false) }
        render()
        if (lang == null) return
        loadJob = viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val lang = state.value.language ?: return
        val result = repository.next(chapterId, lang)
        // A failure keeps what is on screen: the reader's own "Next chapter" stands until the list is known.
        if (result is AppResult.Ok && state.value.language == lang) take(result.value)
    }

    private fun take(now: NextChapters) {
        val done = NextChapterRules.newlyReady(next, now)
        next = now
        if (done.isNotEmpty()) {
            ready = done
            toastJob?.cancel()
            toastJob =
                viewModelScope.launch {
                    delay(TOAST_MS)
                    ready = emptyList()
                    render()
                }
        }
        follow()
        maybeAuto()
        render()
    }

    /** While something is prepared, the list is read again every 3 s. */
    private fun follow() {
        val waiting = next?.let { NextChapterRules.preparing(it).isNotEmpty() } == true
        if (!waiting) {
            pollJob?.cancel()
            pollJob = null
            return
        }
        if (pollJob?.isActive == true) return
        pollJob =
            viewModelScope.launch {
                while (true) {
                    delay(NextChapterRules.POLL_MS)
                    load()
                    if (next?.let { NextChapterRules.preparing(it).isEmpty() } != false) break
                }
            }
    }

    /** The series setting (rule 4): at 70 %, the next chapter, once, quietly (an error never covers the page). */
    private fun maybeAuto() {
        val lang = state.value.language ?: return
        val now = next ?: return
        if (autoAsked || reach < NextChapterRules.AUTO_AT || !NextChapterRules.shouldAuto(now, lang)) return
        autoAsked = true
        val credits = now.upcoming.first().credits
        viewModelScope.launch {
            val result = repository.prefetch(chapterId, lang, 1, credits, auto = true)
            if (result is AppResult.Ok && state.value.language == lang) take(result.value)
        }
    }

    private fun prefetch(
        count: Int,
        price: Price,
    ) {
        val lang = state.value.language ?: return
        if (price.blocked) return
        act { repository.prefetch(chapterId, lang, count, price.credits) }
    }

    /** The switch moves at once; the answer confirms it, a refusal puts it back. */
    private fun setAuto(on: Boolean) {
        val lang = state.value.language ?: return
        val before = next ?: return
        next = before.copy(auto = on)
        render()
        act(onError = { next = next?.copy(auto = before.auto) }) { repository.setAuto(chapterId, lang, on) }
    }

    private fun act(
        onError: () -> Unit = {},
        run: suspend () -> AppResult<NextChapters>,
    ) {
        if (state.value.busy) return
        val lang = state.value.language
        error = null
        mutableState.update { it.copy(busy = true) }
        render()
        viewModelScope.launch {
            val result = run()
            mutableState.update { it.copy(busy = false) }
            if (state.value.language != lang) return@launch
            when (result) {
                is AppResult.Ok -> take(result.value)
                is AppResult.Err -> {
                    onError()
                    error = NextText.error(result.error)
                    render()
                    // The price moved: the new one is shown before anything is asked again.
                    if ((result.error as? AppError.Rejected)?.code == QUOTE_CHANGED) load()
                }
            }
        }
    }

    private fun render() {
        val lang = state.value.language
        val now = next
        if (lang == null || now == null) {
            mutableState.update { it.copy(endCard = null, bubble = null, toast = null, autoSwitch = null) }
            return
        }
        val busy = state.value.busy
        val language = Language.of(lang)?.native ?: lang
        mutableState.update {
            it.copy(
                endCard = endCard(now, lang, language, busy),
                bubble = bubble(now, language, busy),
                toast =
                    ready.firstOrNull()?.let { first ->
                        NextToast(
                            line = t("next.ready", "chapter" to name(first), "language" to language),
                            button = NextButton(t("next.readIt"), NextActionKind.Open, first.id, lang),
                        )
                    },
                autoSwitch = NextAutoSwitch(t("next.auto", "language" to language), now.auto, enabled = !busy),
            )
        }
    }

    private fun endCard(
        now: NextChapters,
        lang: String,
        language: String,
        busy: Boolean,
    ): NextEndCard {
        fun original(next: Upcoming) = NextButton(t("next.original"), NextActionKind.Open, next.id, null)
        return when (val end = NextChapterRules.endState(now, lang)) {
            is EndState.Go ->
                NextEndCard(
                    note = null,
                    primary =
                        NextButton(
                            t("next.go", "chapter" to name(end.next)),
                            NextActionKind.Open,
                            end.next.id,
                            lang.takeIf { end.translated },
                        ),
                    secondary = null,
                    progress = null,
                    error = null,
                )
            is EndState.Prepare ->
                NextEndCard(
                    note = t("next.notYet", "chapter" to name(end.next), "language" to language),
                    primary =
                        if (end.price.blocked) {
                            NextButton(NextText.price(end.price), NextActionKind.AddKey)
                        } else {
                            NextButton(t("next.prepare", "price" to NextText.price(end.price)), NextActionKind.Prepare, enabled = !busy)
                        },
                    secondary = original(end.next),
                    progress = null,
                    error = error,
                )
            is EndState.Preparing ->
                NextEndCard(
                    note = preparingLine(end.next, language),
                    primary = null,
                    secondary = original(end.next),
                    progress = end.progress.coerceIn(0, 100) / 100f,
                    error = null,
                )
            // Its bubbles were never read, so it can't be translated yet; the original always reads.
            is EndState.Unread ->
                NextEndCard(
                    note = t("next.unread", "chapter" to name(end.next), "language" to language),
                    primary = NextButton(t("next.go", "chapter" to name(end.next)), NextActionKind.Open, end.next.id, null),
                    secondary = null,
                    progress = null,
                    error = null,
                )
            is EndState.Link -> NextEndCard(null, NextButton(t("next.link"), NextActionKind.OpenUrl, url = end.url), null, null, null)
            EndState.Latest -> NextEndCard(t("next.latest"), null, null, null, null)
        }
    }

    private fun bubble(
        now: NextChapters,
        language: String,
        busy: Boolean,
    ): NextBubble? {
        if (bubbleClosed || atEnd || reach < NextChapterRules.SUGGEST_AT) return null
        val first = now.upcoming.firstOrNull()?.takeIf { it.readable } ?: return null
        val preparing = NextChapterRules.preparing(now)
        val offer = NextChapterRules.offer(now)
        if (preparing.isEmpty() && offer == null) return null
        val single = preparing.singleOrNull()
        return NextBubble(
            busyLine =
                when {
                    preparing.isEmpty() -> null
                    single != null -> preparingLine(single, language)
                    else -> t("next.preparingMany", "count" to preparing.size, "language" to language)
                },
            progress = single?.let { (it.translation?.progress ?: 0).coerceIn(0, 100) / 100f },
            cancelLabel = t("common.cancel").takeIf { preparing.any { it.translation?.cancellable == true } },
            offerLine =
                offer?.let {
                    if (it.first) {
                        t("next.offerFirst", "chapter" to name(first), "language" to language)
                    } else {
                        t("next.offerMore", "count" to it.count)
                    }
                },
            offer =
                offer?.let {
                    if (it.price.blocked) {
                        NextButton(NextText.price(it.price), NextActionKind.AddKey)
                    } else {
                        val price = NextText.price(it.price)
                        val label =
                            if (it.first) {
                                t("next.offerFirstButton", "price" to price)
                            } else {
                                t(
                                    "next.offerMoreButton",
                                    "count" to it.count,
                                    "price" to price,
                                )
                            }
                        NextButton(label, NextActionKind.Prepare, enabled = !busy)
                    }
                },
            error = error,
            hideLabel = t("next.hide"),
        )
    }

    private fun preparingLine(
        next: Upcoming,
        language: String,
    ): String =
        t(
            "next.preparing",
            "chapter" to name(next),
            "language" to language,
            "percent" to (next.translation?.progress ?: 0).coerceIn(0, 100),
        )

    private fun name(next: Upcoming): String =
        next.title?.takeIf { it.isNotBlank() }?.let { "${chapterLabel(next.number)} ✦ $it" } ?: chapterLabel(next.number)

    companion object {
        const val INTERNAL = "vovoscan"
        const val TOAST_MS = 8_000L
        private const val QUOTE_CHANGED = "QUOTE_CHANGED"
    }
}

object NextText {
    fun price(price: Price): String =
        when (price.kind) {
            Price.Kind.Free -> t("next.price.free")
            Price.Kind.OwnKey -> t("next.price.ownKey")
            Price.Kind.Credits -> t("next.price.credits", "credits" to price.credits)
            Price.Kind.NeedsKey -> t("next.price.needsKey")
        }

    fun error(error: AppError): String =
        when {
            error is AppError.Rejected && error.code == "QUOTE_CHANGED" -> t("next.quoteChanged")
            error is AppError.Rejected && error.code == "NO_FUNDING" -> t("next.price.needsKey")
            error is AppError.Rejected && error.code == "NOTHING_TO_PREFETCH" -> t("next.failed")
            else -> TranslationText.error(error)
        }
}
