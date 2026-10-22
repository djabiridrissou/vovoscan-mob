package com.vovoscan.feature.reader.domain

import com.vovoscan.core.common.AppResult

/**
 * What comes after a chapter of our catalog in a reading language (next-chapter contract, shared with the web's
 * `next-logic.ts`): the end card's state, the suggestion bubble's offer (the next chapter, then the next 5, then 10,
 * read from the data so it survives a relaunch), the price shown before anything spends, and which chapters prepared
 * ahead just turned ready. Pure.
 */
enum class Funding {
    /** The caller's own key pays. */
    OwnKey,
    Credits,

    /** No key of one's own and no platform funding. */
    None,
    ;

    companion object {
        fun fromWire(value: String): Funding =
            when (value) {
                "byok" -> OwnKey
                "credits" -> Credits
                else -> None
            }
    }
}

data class UpcomingTranslation(
    val status: TranslationStatus,
    val progress: Int,
    val error: String?,
    /** Queued by the caller and not started: Cancel takes it back. */
    val cancellable: Boolean,
)

data class Upcoming(
    val id: String,
    val number: Double,
    val title: String?,
    val pageCount: Int,
    /** Nobody read its bubbles yet: the reader never starts a paid reading by itself. */
    val readable: Boolean,
    val translation: UpcomingTranslation?,
    /** 0: free (one's own key, already made or running). */
    val credits: Int,
) {
    internal val missing: Boolean get() = readable && (translation == null || translation.status == TranslationStatus.Failed)
    internal val running: Boolean
        get() = translation?.status == TranslationStatus.Queued || translation?.status == TranslationStatus.Running
}

data class NextChapters(
    /** null: read in the original. */
    val language: String?,
    val seriesId: String?,
    val funding: Funding,
    /** Spendable credits; null with one's own key. */
    val available: Int?,
    /** "Always prepare the next chapter of this series" in [language]. */
    val auto: Boolean,
    /** The next chapter first, then those after it (10 at most), in reading order. */
    val upcoming: List<Upcoming>,
    /** No next chapter here but the source links one. */
    val nextUrl: String? = null,
)

/** Who pays, in the card's words: "Free", "Free with your key", "12 credits", "Needs your own key". */
data class Price(
    val credits: Int,
    val kind: Kind,
) {
    enum class Kind { Free, OwnKey, Credits, NeedsKey }

    /** Nothing can pay: no key of one's own, no platform funding. */
    val blocked: Boolean get() = kind == Kind.NeedsKey
}

sealed interface EndState {
    data class Go(
        val next: Upcoming,
        val translated: Boolean,
    ) : EndState

    data class Prepare(
        val next: Upcoming,
        val price: Price,
    ) : EndState

    data class Preparing(
        val next: Upcoming,
        val progress: Int,
    ) : EndState

    /** Its bubbles were never read: its chapter page offers that, never the reader. */
    data class Unread(
        val next: Upcoming,
    ) : EndState

    data class Link(
        val url: String,
    ) : EndState

    data object Latest : EndState
}

/** The bubble's offer: [count] chapters (the real count left: "Next 2" when only 2 exist). */
data class Offer(
    val count: Int,
    val first: Boolean,
    val price: Price,
)

object NextChapterRules {
    /** The bubble shows once this share of the chapter is read; the series setting prepares the next at [AUTO_AT]. */
    const val SUGGEST_AT = 0.4f
    const val AUTO_AT = 0.7f

    /** While something is being prepared, the list is read again this often. */
    const val POLL_MS = 3_000L

    private val STEPS = listOf(1, 5, 10)

    fun endState(
        next: NextChapters,
        lang: String?,
    ): EndState {
        val first = next.upcoming.firstOrNull() ?: return next.nextUrl?.let { EndState.Link(it) } ?: EndState.Latest
        return when {
            !first.readable -> EndState.Unread(first)
            lang == null -> EndState.Go(first, translated = false)
            first.translation?.status == TranslationStatus.Ready -> EndState.Go(first, translated = true)
            first.running -> EndState.Preparing(first, first.translation?.progress ?: 0)
            else -> EndState.Prepare(first, priceOf(next, listOf(first)))
        }
    }

    fun priceOf(
        next: NextChapters,
        items: List<Upcoming>,
    ): Price {
        val todo = items.filter { it.missing }
        val credits = todo.sumOf { it.credits }
        return when {
            todo.isEmpty() -> Price(0, Price.Kind.Free)
            next.funding == Funding.None -> Price(credits, Price.Kind.NeedsKey)
            credits == 0 -> Price(0, Price.Kind.OwnKey)
            else -> Price(credits, Price.Kind.Credits)
        }
    }

    /** The next chapter, then the next 5, then 10; null once they are all asked for. */
    fun offer(next: NextChapters): Offer? {
        val first = next.upcoming.firstOrNull() ?: return null
        if (!first.readable) return null
        for (step in STEPS) {
            val window = next.upcoming.take(step)
            if (window.none { it.missing }) continue
            return Offer(window.size, step == 1, priceOf(next, window))
        }
        return null
    }

    fun preparing(next: NextChapters): List<Upcoming> = next.upcoming.filter { it.running }

    /**
     * At [AUTO_AT]: the series setting prepares the next chapter, only when it is missing, affordable, and nothing of
     * the caller's waits already (the server checks the same; this only spares the request).
     */
    fun shouldAuto(
        next: NextChapters,
        lang: String?,
    ): Boolean {
        val first = next.upcoming.firstOrNull() ?: return false
        return lang != null &&
            next.auto &&
            first.missing &&
            !priceOf(next, listOf(first)).blocked &&
            next.upcoming.none { it.running && it.translation?.cancellable == true }
    }

    /** What was being prepared and is ready now (the toast). */
    fun newlyReady(
        before: NextChapters?,
        now: NextChapters,
    ): List<Upcoming> {
        if (before == null || before.language != now.language) return emptyList()
        val was =
            before.upcoming
                .filter { it.running }
                .map { it.id }
                .toSet()
        return now.upcoming.filter { it.id in was && it.translation?.status == TranslationStatus.Ready }
    }
}

/** `api/v1/translations/chapters/{id}/next` and `…/prefetch` (our catalog's chapters). Online only. */
interface NextChaptersRepository {
    /** [lang] null: the original. */
    suspend fun next(
        chapterId: String,
        lang: String?,
    ): AppResult<NextChapters>

    /** 409 `QUOTE_CHANGED` when the price went above [maxCredits]; 402 `NO_FUNDING`; 409 `NOTHING_TO_PREFETCH`. */
    suspend fun prefetch(
        chapterId: String,
        lang: String,
        count: Int,
        maxCredits: Int,
        auto: Boolean = false,
    ): AppResult<NextChapters>

    /** Takes back what was queued and has not started. */
    suspend fun cancel(
        chapterId: String,
        lang: String,
    ): AppResult<NextChapters>

    suspend fun setAuto(
        chapterId: String,
        lang: String,
        auto: Boolean,
    ): AppResult<NextChapters>
}
