package com.vovoscan.feature.catalog.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.Freshness
import com.vovoscan.core.common.Language
import com.vovoscan.core.common.StaleReason
import com.vovoscan.core.common.StripGenres
import com.vovoscan.core.common.i18n.I18n
import com.vovoscan.core.common.i18n.t
import com.vovoscan.core.common.userMessage
import com.vovoscan.feature.catalog.domain.CatalogRepository
import com.vovoscan.feature.catalog.domain.ChapterTranslationBadge
import com.vovoscan.feature.catalog.domain.ChapterTranslations
import com.vovoscan.feature.catalog.domain.ContentKind
import com.vovoscan.feature.catalog.domain.EpisodeFeature
import com.vovoscan.feature.catalog.domain.INTERNAL_SOURCE
import com.vovoscan.feature.catalog.domain.ReadingFormat
import com.vovoscan.feature.catalog.domain.ReadingResume
import com.vovoscan.feature.catalog.domain.ResumePoint
import com.vovoscan.feature.catalog.domain.TitleDetail
import com.vovoscan.feature.catalog.domain.TitleTranslationBadges
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChapterRow(
    val id: String,
    /** "Chapter 98" or "Chapter 98 · The last gate". */
    val label: String,
    /** "19 pages". */
    val meta: String,
    /** The chapter this account was reading last. */
    val current: Boolean = false,
    /** Its translation in the language last read in: "Français ✓", "Français ✦ 42%", "Français ✦ preparing". */
    val translation: String? = null,
    val translationReady: Boolean = false,
    /** Open it in this language (translated and ready); null: as it is. */
    val openLanguage: String? = null,
    /** Its episode's languages as badges: "▶ Original", "▶ Français", three then "+2"; empty without one (or `video`). */
    val episodes: List<String> = emptyList(),
    /** "Watch as an episode in: Original, Français, English" for screen readers; null without one. */
    val episodesA11y: String? = null,
)

/** Where a chapter row's pill leads. */
enum class ChapterTarget {
    /** The reader, on its language picker. */
    Translate,

    /** The chapter's voiced episode card. */
    Episode,
}

/** A chapter row's pill was tapped: the screen opens [target] for [chapterId] once, then answers `OpenHandled(seq)`. */
data class ChapterOpen(
    val seq: Long,
    val chapterId: String,
    val target: ChapterTarget,
)

/** The title page's main action: where reading picks up ("Continue reading") or starts ("Start reading"). */
data class ReadAction(
    val chapterId: String,
    /** The page to open at (0 = the first). */
    val page: Int,
    /** "Continue reading" or "Start reading". */
    val label: String,
    /** "Chapter 4 · The Well ✦ page 12" or "Chapter 1". */
    val detail: String,
    val resumes: Boolean,
    /** Open it in this language (the chapter is translated into the one last read in); null: as it is. */
    val language: String? = null,
)

data class TitleDetailUiState(
    val sourceKey: String = "",
    val titleId: String = "",
    val isBook: Boolean = false,
    val name: String = "",
    val coverUrl: String? = null,
    /** Not "description": on iOS that name is `toString()` (NSObject) and would be renamed. */
    val synopsis: String = "",
    /** "WEBTOON ✦ Ongoing ✦ A. Author". */
    val meta: String = "",
    val genres: List<String> = emptyList(),
    /** In the order chosen: oldest first (chapter 1 on top) by default. */
    val chapters: List<ChapterRow> = emptyList(),
    val newestFirst: Boolean = false,
    /** The chapter ids in reading order (oldest first), whatever the list's order. */
    val readingOrder: List<String> = emptyList(),
    val readAction: ReadAction? = null,
    val freshness: Freshness = Freshness.Refreshing,
    /** Title never opened and no network: nothing to show. */
    val unavailable: Boolean = false,
    val error: String? = null,
    /** Why the last reload failed while the saved page stays on screen. */
    val stale: StaleReason? = null,
    /** A pill tapped on a chapter row, waiting for the screen to open it. */
    val open: ChapterOpen? = null,
    /** Chapters with an episode (only with the `video` feature; 0 otherwise). */
    val episodeChapters: Int = 0,
    /** Only the chapters with an episode are listed. */
    val episodesOnly: Boolean = false,
) {
    /** The "▶ Episodes (3)" chip, shown only when a chapter has one. */
    val showEpisodesFilter: Boolean get() = episodeChapters > 0
    val episodesChip: String get() = t("catalog.chapterEpisodes", "count" to episodeChapters)

    /** "Offline: …", "Project Gutenberg isn't answering right now. Showing what's saved.", or rules that can't read it. */
    val notice: String? get() = if (unavailable) null else stale?.notice(hasSaved = true)

    /** A source that didn't answer may answer now: "Retry" (or "Try again" when nothing is saved). */
    val canRetry: Boolean get() = stale?.canRetry == true

    /**
     * A comic, at any source: each chapter can become a voiced episode (a source's chapter has its pages brought to the
     * server first, when asked). Not books.
     */
    val offersEpisodes: Boolean get() = !isBook && chapters.isNotEmpty()

    /** "Translate" on each chapter row of a comic, any source: the reader opens on its language picker. */
    val offersTranslation: Boolean get() = !isBook && chapters.isNotEmpty()
    val translateChip: String get() = t("translation.chip")

    /** "Chapter 98 · The last gate" for a chapter of this title; "" when unknown. */
    fun chapterLabel(chapterId: String): String = chapters.firstOrNull { it.id == chapterId }?.label.orEmpty()

    /** The chapter after [chapterId] in reading order, whatever the list's order; null for the latest. */
    fun nextChapterId(chapterId: String): String? {
        val index = readingOrder.indexOf(chapterId)
        return if (index >= 0) readingOrder.getOrNull(index + 1) else null
    }

    /** The order toggle: "Oldest first" / "Newest first", the order shown now. */
    val orderLabel: String get() = if (newestFirst) t("catalog.newestFirst") else t("catalog.oldestFirst")
}

sealed interface TitleDetailIntent {
    /** Chapter 1 on top, or the latest on top; remembered on this device for every title. */
    data object ToggleOrder : TitleDetailIntent

    /** "Translate" on a chapter row: the reader opens on its language picker. */
    data class Translate(
        val chapterId: String,
    ) : TitleDetailIntent

    /** "Episode" on a chapter row: the chapter's episode card opens. */
    data class Episode(
        val chapterId: String,
    ) : TitleDetailIntent

    /** The screen opened [seq]. */
    data class OpenHandled(
        val seq: Long,
    ) : TitleDetailIntent

    /** "▶ Episodes": only the chapters with an episode, or every chapter again. */
    data object ToggleEpisodes : TitleDetailIntent
}

/** Title page: name, description, chapters. Readable offline once it has been opened. */
class TitleDetailViewModel(
    private val sourceKey: String,
    private val titleId: String,
    private val repository: CatalogRepository,
    private val resume: ReadingResume = ReadingResume { flowOf(emptyList()) },
    private val translations: ChapterTranslations = ChapterTranslations.None,
    private val video: EpisodeFeature = EpisodeFeature.Off,
) : ViewModel() {
    private var videoOn = false
    private val mutableState = MutableStateFlow(TitleDetailUiState())
    val state: StateFlow<TitleDetailUiState> = mutableState.asStateFlow()
    private var known = false
    private var badges: TitleTranslationBadges? = null
    private var shown: Triple<TitleDetail, Boolean, ResumePoint?>? = null
    private var openSeq = 0L

    init {
        val point = resume.observe().map { points -> points.firstOrNull { it.sourceKey == sourceKey && it.titleId == titleId } }
        combine(
            repository.observeTitle(sourceKey, titleId),
            repository.observeNewestFirst(),
            point,
            video.enabled(),
        ) { detail, newestFirst, at, on ->
            videoOn = on
            Triple(detail, newestFirst, at)
        }.onEach { (detail, newestFirst, at) ->
            if (detail != null) {
                known = true
                shown = Triple(detail, newestFirst, at)
                mutableState.update { it.show(detail, newestFirst, at) }
            } else {
                mutableState.update { it.copy(newestFirst = newestFirst) }
            }
        }.launchIn(viewModelScope)
        refresh()
    }

    fun onIntent(intent: TitleDetailIntent) {
        when (intent) {
            TitleDetailIntent.ToggleOrder -> {
                val next = !state.value.newestFirst
                viewModelScope.launch { repository.saveNewestFirst(next) }
            }
            is TitleDetailIntent.Translate ->
                open(intent.chapterId, ChapterTarget.Translate) { it.offersTranslation }
            is TitleDetailIntent.Episode ->
                open(intent.chapterId, ChapterTarget.Episode) { it.offersEpisodes }
            is TitleDetailIntent.OpenHandled ->
                mutableState.update { if (it.open?.seq == intent.seq) it.copy(open = null) else it }
            TitleDetailIntent.ToggleEpisodes -> {
                val (detail, newestFirst, at) = shown ?: return
                mutableState.update { now ->
                    if (!now.showEpisodesFilter &&
                        !now.episodesOnly
                    ) {
                        now
                    } else {
                        now.copy(episodesOnly = !now.episodesOnly).show(detail, newestFirst, at)
                    }
                }
            }
        }
    }

    // Only a chapter on the page, and only where the pill is offered (never a book).
    private fun open(
        chapterId: String,
        target: ChapterTarget,
        offered: (TitleDetailUiState) -> Boolean,
    ) {
        mutableState.update { now ->
            if (!offered(now) || now.chapters.none { it.id == chapterId }) {
                now
            } else {
                now.copy(open = ChapterOpen(++openSeq, chapterId, target))
            }
        }
    }

    fun refresh() {
        mutableState.update { it.copy(freshness = Freshness.Refreshing, error = null, stale = null) }
        // The chapters' translations in the language last read in (our catalog; quietly nothing otherwise).
        viewModelScope.launch {
            val now = translations.of(sourceKey, titleId) ?: return@launch
            badges = now
            shown?.let { (detail, newestFirst, at) -> mutableState.update { it.show(detail, newestFirst, at) } }
        }
        viewModelScope.launch {
            val result = repository.refreshTitle(sourceKey, titleId)
            mutableState.update {
                when (result) {
                    is AppResult.Ok -> it.copy(freshness = Freshness.Fresh, unavailable = false)
                    is AppResult.Err -> {
                        val reason = StaleReason.of(result.error)
                        it.copy(
                            freshness = Freshness.Offline,
                            unavailable = !known,
                            stale = reason,
                            error = if (reason != null && known) null else reason?.notice(hasSaved = false) ?: result.error.userMessage(),
                        )
                    }
                }
            }
        }
    }

    // The saved copy can arrive after a refresh already failed (offline answers at once): it is no longer
    // "not saved", and the banner says why it isn't fresh.
    private fun TitleDetailUiState.show(
        detail: TitleDetail,
        newestFirst: Boolean,
        at: ResumePoint?,
    ): TitleDetailUiState {
        val isBook = detail.summary.kind == ContentKind.Book
        // The source lists newest to oldest; reading goes the other way.
        val inOrder = detail.chapters.asReversed()
        val rows =
            inOrder.map { chapter ->
                if (isBook) {
                    ChapterRow(
                        id = chapter.id,
                        label = chapter.name ?: t("reader.part", "n" to chapter.label),
                        meta = t("reader.modeBook"),
                        current = chapter.id == at?.chapterId,
                    )
                } else {
                    val badge = badges?.chapters?.get(chapter.id)
                    val language = badges?.language
                    ChapterRow(
                        id = chapter.id,
                        label = chapterLabel(chapter.label, chapter.name),
                        meta = if (chapter.pageCount == 0) "" else t("catalog.pages", "count" to chapter.pageCount),
                        current = chapter.id == at?.chapterId,
                        translation = if (badge != null && language != null) badgeLabel(badge, language) else null,
                        translationReady = badge?.ready == true,
                        openLanguage = language.takeIf { badge?.ready == true },
                        episodes = if (videoOn) episodeBadges(chapter.episodeLanguages) else emptyList(),
                        episodesA11y = if (videoOn) episodesA11y(chapter.episodeLanguages) else null,
                    )
                }
            }
        val watchable = rows.count { it.episodes.isNotEmpty() }
        // The filter goes off by itself once no chapter has an episode (the chip is gone).
        val onlyEpisodes = episodesOnly && watchable > 0
        val listed = if (onlyEpisodes) rows.filter { it.episodes.isNotEmpty() } else rows
        val resumed = at?.let { point -> rows.firstOrNull { it.id == point.chapterId } }
        val readAction =
            when {
                resumed != null && at != null ->
                    ReadAction(
                        chapterId = resumed.id,
                        page = at.page,
                        label = t("catalog.continueReading"),
                        detail =
                            if (at.page >
                                0
                            ) {
                                t("catalog.resumeAt", "chapter" to resumed.label, "page" to at.page + 1)
                            } else {
                                resumed.label
                            },
                        resumes = true,
                        language = resumed.openLanguage,
                    )
                rows.isNotEmpty() ->
                    ReadAction(
                        rows.first().id,
                        0,
                        t("catalog.startReading"),
                        rows.first().label,
                        resumes = false,
                        language = rows.first().openLanguage,
                    )
                else -> null
            }
        return copy(
            unavailable = false,
            error = if (stale != null) null else error,
            sourceKey = detail.summary.sourceKey,
            titleId = detail.summary.id,
            isBook = detail.summary.kind == ContentKind.Book,
            name = detail.summary.name,
            coverUrl = detail.summary.coverUrl,
            synopsis = detail.synopsis,
            meta =
                listOfNotNull(
                    when {
                        detail.summary.kind == ContentKind.Book -> t("catalog.badgeBook")
                        detail.summary.format == ReadingFormat.Webtoon -> t("catalog.badgeWebtoon")
                        // Saved before its genres were read for the format: they still say it is a strip.
                        detail.summary.sourceKey != INTERNAL_SOURCE && StripGenres.named(detail.genres) -> t("catalog.badgeWebtoon")
                        else -> t("catalog.badgeManga")
                    },
                    statusLabel(detail.summary.status),
                    detail.authors.joinToString(", ").ifBlank { null },
                ).joinToString(" ✦ "),
            genres = detail.genres,
            chapters = if (newestFirst) listed.asReversed() else listed,
            episodeChapters = watchable,
            episodesOnly = onlyEpisodes,
            newestFirst = newestFirst,
            readingOrder = rows.map { it.id },
            readAction = readAction,
        )
    }
}

/** At most this many language badges on a chapter row, then "+n". */
private const val EPISODE_BADGES = 3

/** "▶ Original", "▶ Français", "▶ English", "+2": an episode's languages, the original first. */
internal fun episodeBadges(languages: List<String>): List<String> {
    val names = episodeLanguageNames(languages)
    if (names.size <= EPISODE_BADGES) return names.map(::episodeBadgeLabel)
    return names.take(EPISODE_BADGES).map(::episodeBadgeLabel) + t("catalog.episodeMore", "count" to names.size - EPISODE_BADGES)
}

internal fun episodesA11y(languages: List<String>): String? =
    episodeLanguageNames(languages)
        .takeIf { it.isNotEmpty() }
        ?.let { names -> t("catalog.episodeA11y", "languages" to names.joinToString(", ") { it ?: t("episode.original") }) }

/** null = the original; else the language's own name. */
private fun episodeLanguageNames(languages: List<String>): List<String?> =
    languages
        .distinct()
        .sortedBy { if (it.isEmpty()) 0 else 1 }
        .map { code -> if (code.isEmpty()) null else Language.of(code)?.native ?: code }

private fun episodeBadgeLabel(name: String?): String =
    if (name == null) t("catalog.episodeOriginal") else t("catalog.episodeLanguage", "language" to name)

/** "Français ✓" (ready), "Français ✦ 42%" (being made), "Français ✦ preparing" (in the queue). */
internal fun badgeLabel(
    badge: ChapterTranslationBadge,
    language: String,
): String {
    val name = Language.of(language)?.native ?: language
    // A local: a property of another module's class can't be smart cast.
    val progress = badge.progress
    return when {
        badge.ready -> t("catalog.translationReady", "language" to name)
        progress != null -> t("catalog.translationProgress", "language" to name, "percent" to progress.coerceIn(0, 100))
        else -> t("catalog.translationQueued", "language" to name)
    }
}

/**
 * "Chapter 3 · The Well"; a name that only repeats the number ("Chapter 3", "Ch. 3", "3") is not shown twice.
 */
internal fun chapterLabel(
    label: String,
    name: String?,
): String {
    val heading = t("reader.chapter", "n" to label)
    val trimmed = name?.trim().orEmpty()
    val bare =
        trimmed
            .lowercase()
            .removePrefix("chapter")
            .removePrefix("chapitre")
            .removePrefix("ch.")
            .removePrefix("ch")
            .trim()
    return if (trimmed.isEmpty() || bare == label.lowercase()) heading else "$heading · $trimmed"
}

/** A source's status in the UI locale when it is a known one ("ongoing", "completed"…), else as the source wrote it. */
internal fun statusLabel(status: String): String {
    val key = "catalog.status" + status.trim().lowercase().replaceFirstChar { it.uppercase() }
    return if (I18n.has(key)) t(key) else status.replaceFirstChar { it.uppercase() }
}
