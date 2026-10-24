package com.vovoscan.feature.reader.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.Freshness
import com.vovoscan.core.common.StaleReason
import com.vovoscan.core.common.i18n.t
import com.vovoscan.core.common.userMessage
import com.vovoscan.feature.reader.domain.BookPosition
import com.vovoscan.feature.reader.domain.BookReadingRepository
import com.vovoscan.feature.reader.domain.BookSection
import com.vovoscan.feature.reader.domain.BookTypography
import com.vovoscan.feature.reader.domain.ChapterHeading
import com.vovoscan.feature.reader.domain.ChapterHeadings
import com.vovoscan.feature.reader.domain.ChapterPanels
import com.vovoscan.feature.reader.domain.CoachKind
import com.vovoscan.feature.reader.domain.CoachSync
import com.vovoscan.feature.reader.domain.ComicFormat
import com.vovoscan.feature.reader.domain.DownloadableChapter
import com.vovoscan.feature.reader.domain.Glide
import com.vovoscan.feature.reader.domain.Gutters
import com.vovoscan.feature.reader.domain.PanelBox
import com.vovoscan.feature.reader.domain.ReaderChapter
import com.vovoscan.feature.reader.domain.ReaderPage
import com.vovoscan.feature.reader.domain.ReaderPreferences
import com.vovoscan.feature.reader.domain.ReaderRepository
import com.vovoscan.feature.reader.domain.ReaderSettingsRepository
import com.vovoscan.feature.reader.domain.ReadingMode
import com.vovoscan.feature.reader.domain.ReadingModes
import com.vovoscan.feature.reader.domain.ReadingPosition
import com.vovoscan.feature.reader.domain.ReadingProgressSink
import com.vovoscan.feature.reader.domain.ReadingStops
import com.vovoscan.feature.reader.domain.ScrollAmount
import com.vovoscan.feature.reader.domain.Stop
import com.vovoscan.feature.reader.domain.StripLayout
import com.vovoscan.feature.reader.domain.TapAction
import com.vovoscan.feature.reader.domain.TapInvert
import com.vovoscan.feature.reader.domain.TapLayout
import com.vovoscan.feature.reader.domain.TapZoneMap
import com.vovoscan.feature.reader.domain.TapZones
import com.vovoscan.feature.reader.domain.TitleChapters
import com.vovoscan.feature.reader.domain.TocEntry
import com.vovoscan.feature.reader.domain.ZoneRect
import com.vovoscan.feature.reader.domain.chapterLabel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

/** One thing a comic reader shows in order: the previous-chapter card, the pages, the next-chapter card. */
sealed interface ReaderItem {
    /** Stable key for lists and pagers. */
    val key: String

    data class Page(
        val page: ReaderPage,
    ) : ReaderItem {
        override val key: String get() = "page-${page.index}"
    }

    data class Transition(
        val card: TransitionCard,
    ) : ReaderItem {
        override val key: String get() = if (card.forward) "next" else "previous"
    }
}

/**
 * Chapter transition: "Finished: Chapter 3" / "Next: Chapter 4 · The last gate" and "Next chapter →", or
 * "This is the last chapter for now." (no action).
 */
data class TransitionCard(
    /** true: after the last page (next chapter); false: before the first page (previous chapter). */
    val forward: Boolean,
    val heading: String,
    val target: String,
    val actionLabel: String?,
    val chapterId: String?,
)

/**
 * Book: what pulling past an edge of the section shows (`ReaderUiState.pullHint`). The hint grows with
 * [progress] (0..1, the threshold at 1); [armed]: past the threshold, letting go turns ([label] then says
 * "Release…"). Nothing on that side ([enabled] false): the hint only says so.
 */
data class PullHint(
    /** true: pulled up past the bottom (what comes next); false: pulled down past the top. */
    val forward: Boolean,
    val progress: Float,
    val armed: Boolean,
    val label: String,
    val enabled: Boolean,
)

/** Where a pull on a book section leads: the neighbouring section, else the neighbouring part (chapter). */
internal enum class PullTarget {
    NextSection,
    NextPart,
    PreviousSection,
    PreviousPart,
}

/** A tap-zone label on the overlay, at a fraction of the reading area. */
data class ZoneText(
    val action: TapAction,
    val text: String,
    val x: Float,
    val y: Float,
)

/** What the "tap zones" overlay draws: translucent rectangles (red next, cyan previous, ink menu) and labels. */
data class TapOverlay(
    val rects: List<ZoneRect>,
    val labels: List<ZoneText>,
) {
    companion object {
        fun of(map: TapZoneMap) = TapOverlay(map.rects, map.labels.map { ZoneText(it.action, ReaderText.action(it.action), it.x, it.y) })
    }
}

/**
 * What the native reader must do now, once. Each command has a new `seq`; the native side answers
 * `ReaderIntent.CommandHandled(seq)` once it has started it.
 */
sealed interface ReaderCommand {
    val seq: Int

    /**
     * Paged modes: show this item (index in `items`), animated. [atBottom]: a tall page (`TallPages`) shows its
     * bottom (reached going back); otherwise its top.
     */
    data class GoToItem(
        override val seq: Int,
        val item: Int,
        val animated: Boolean,
        val atBottom: Boolean = false,
    ) : ReaderCommand

    /**
     * Smooth scroll by this fraction of the reading area's height (negative: up): the strip (long strips), or the
     * current tall page inside its page (paged modes), never past the page's edge.
     */
    data class ScrollBy(
        override val seq: Int,
        val fraction: Float,
    ) : ReaderCommand

    /**
     * Continuous strips: scroll to [offset] (from the first page's top), with the shared spring (`Glide`) from the
     * current position and velocity, or at once when not [animated] (Reduce Motion, a restored position). A new one
     * while moving retargets.
     */
    data class GlideTo(
        override val seq: Int,
        val offset: Float,
        val animated: Boolean,
    ) : ReaderCommand

    /** "Watch the episode from here": the episode of this chapter, from the first beat of [page]. */
    data class OpenEpisode(
        override val seq: Int,
        val chapterId: String,
        val page: Int,
    ) : ReaderCommand

    /**
     * The page slider let go on a page: show this item (index in `items`) at once, its top at the top of the screen,
     * in every mode (a strip scrolls to the page's top, a tall page shows its top).
     */
    data class JumpTo(
        override val seq: Int,
        val item: Int,
    ) : ReaderCommand

    /** Replace this reader by another chapter, from `startPage` (`ReaderViewModel.START_AT_END`: its last page). */
    data class OpenChapter(
        override val seq: Int,
        val chapterId: String,
        val startPage: Int,
    ) : ReaderCommand
}

/** A chapter button of the reader's bar; [forward]: the next chapter (the red one). */
data class ChapterButton(
    val chapterId: String,
    val label: String,
    val forward: Boolean,
)

data class ReaderUiState(
    val titleName: String = "",
    /** "Chapter 2 · The last gate" or "Full text". */
    val chapterLabel: String = "",
    /** The mode in use: per-series choice, else the user's default for the format, else the format rule. */
    val mode: ReadingMode = ReadingMode.LongStrip,
    /** The comic's format as recognised (the source's word, else the pages' shape); null for a book. */
    val format: ComicFormat? = null,
    val pages: List<ReaderPage> = emptyList(),
    /** Comics: the previous-chapter card, the pages, the next-chapter card. */
    val items: List<ReaderItem> = emptyList(),
    /** Index in `items` of what is on screen (paged) or at the top of the screen (strips). */
    val currentItem: Int = 0,
    /** Book: table-of-contents sections. */
    val sections: List<BookSection> = emptyList(),
    /** Book: the current section, ready to display (sanitized and styled HTML). */
    val sectionDocument: String? = null,
    /** Book: show the section from its end (reached going back), else from its top. */
    val sectionFromEnd: Boolean = false,
    /** Book being downloaded and opened. */
    val preparingBook: Boolean = false,
    /** Current page (comic) or section (book); initially the resume position. */
    val currentIndex: Int = 0,
    val previousChapterId: String? = null,
    val nextChapterId: String? = null,
    /** Top and bottom bars: the Menu zone hides or shows them. */
    val chromeVisible: Boolean = true,
    val preferences: ReaderPreferences = ReaderPreferences(),
    val settings: ReaderSettingsSheet =
        ReaderSettingsSheet.of(ReadingMode.LongStrip, ComicFormat.Webtoon, null, ReaderPreferences()),
    /** The overlay to draw for a moment (first opening, after a change of zones), else null. */
    val tapOverlay: TapOverlay? = null,
    /** "How to read", over the page until a tap: the first chapter of its kind, or asked again with "?". */
    val coach: ReaderCoach? = null,
    val command: ReaderCommand? = null,
    /** Comics: pinched or double-tapped above 1× (as the native side reports it). Taps then only toggle the bars. */
    val zoomed: Boolean = false,
    /** Changes whenever the zoom must go back to 1× (another page in the paged modes, a jump, another mode). */
    val zoomReset: Int = 0,
    val freshness: Freshness = Freshness.Refreshing,
    /** Never opened and no network: nothing to show. */
    val unavailable: Boolean = false,
    val error: String? = null,
    /** Share of the strip read (0..1): the thin line at the top while the bars are hidden. */
    val progress: Float = 0f,
    /** Focus: the panel being read, at full light (the rest at `Glide.OTHER_LIGHT`). */
    val focus: PanelBox? = null,
    /** Pages to load and decode ahead (the next three). */
    val prefetch: List<String> = emptyList(),
    /** "Series ✦ Chapter 3 · name", shown over the first page for a moment when the chapter opens. */
    val titleCard: String? = null,
    /** Reduce Motion / Remove animations: jumps, no spring, no spotlight glide, no fade. */
    val reducedMotion: Boolean = false,
    /** Our catalogue's comics have episodes: "Watch the episode from here". */
    val voiceAvailable: Boolean = false,
    /** Book: the page's settings, the contents, how to open the section on screen. */
    val book: BookReaderState = BookReaderState(),
) {
    /** The focus switch shows on continuous strips only. */
    val canFocus: Boolean get() = isStrip

    val focusOn: Boolean get() = preferences.focus && isStrip

    val focusLabel: String get() = t("reader.focus")

    val focusHelp: String get() = t("reader.focusHelp")

    val voiceLabel: String get() = t("reader.withVoice")

    val otherLight: Float get() = Glide.OTHER_LIGHT

    val focusMs: Int get() = if (reducedMotion) 0 else Glide.FOCUS_MS

    /** Read out once: what the tap zones do. */
    val tapsHint: String get() = t("reader.tapsHint")

    /** "Page 5 of 19", for the accessibility live region (changes with the page only). */
    val pageAnnouncement: String
        get() = if (isBook || pages.isEmpty()) "" else t("reader.pageOf", "n" to currentIndex + 1, "total" to pages.size)

    val progressLabel: String get() = t("reader.progress", "n" to (progress * 100).toInt())

    val isBook: Boolean get() = mode == ReadingMode.Book

    val isPaged: Boolean get() = mode.isPaged

    val isStrip: Boolean get() = mode.isStrip

    /** "Webtoon ✦ top to bottom", "Manga ✦ right to left", shown on the title card. */
    val formatLabel: String? get() = format?.let { ReaderText.format(it, mode) }

    /** Paged modes: pages side by side (else one above the other). */
    val pagedHorizontally: Boolean get() = mode.isHorizontal

    /** Space between two pages of a long strip, in dp / pt. */
    val pageGap: Int get() = if (mode == ReadingMode.LongStripGaps) PAGE_GAP else 0

    /** Single taps wait for a possible second one only when double tap to zoom is on. */
    val doubleTapZoom: Boolean get() = preferences.doubleTapZoom

    val keepScreenOn: Boolean get() = preferences.keepAwake

    /** Android: the reader takes the volume keys. */
    val volumeKeys: Boolean get() = preferences.volumeKeys && !isBook

    private val count: Int get() = if (isBook) sections.size else pages.size

    /** "5 / 19" (pages) or "Section 2 / 12" (book). */
    val positionLabel: String
        get() =
            when {
                count == 0 -> ""
                isBook -> t("reader.section", "n" to currentIndex + 1, "total" to count)
                else -> "${currentIndex + 1} / $count"
            }

    val sectionTitle: String? get() = sections.getOrNull(currentIndex)?.title

    /**
     * The top bar's second line: the chapter ("Chapter 2 · The last gate"), or for a book the section being read
     * ("CHAPTER VI. Pig and Pepper"), the part's name until the sections are known.
     */
    val subtitle: String get() = if (isBook) sectionTitle?.takeIf { it.isNotBlank() } ?: chapterLabel else chapterLabel

    val canGoBack: Boolean get() = currentIndex > 0

    val canGoForward: Boolean get() = currentIndex < count - 1

    val atEnd: Boolean get() = count > 0 && currentIndex >= count - 1

    /** End-of-unit button. */
    val nextLabel: String? get() = nextChapterId?.let { t(if (isBook) "reader.nextPart" else "reader.nextChapter") }

    val rightToLeft: Boolean get() = mode == ReadingMode.PagedRightToLeft

    /** Comics: the page slider in the bottom bar (with the bars), mirrored in right to left ([rightToLeft]). */
    val showsSlider: Boolean get() = !isBook && pages.size > 1

    /** The slider's label for a page being dragged to ("12 / 19"), as [positionLabel] says it. */
    fun sliderLabel(page: Int): String = if (pages.isEmpty()) "" else "${page.coerceIn(0, pages.lastIndex) + 1} / ${pages.size}"

    /**
     * The bar's chapter buttons, in screen order. They open another chapter (pages turn with taps and swipes), so they
     * say "chapter"; pages read right to left put the next chapter on the left, its arrow pointing that way.
     */
    val chapterButtons: List<ChapterButton>
        get() {
            if (isBook) return emptyList()
            val previous =
                previousChapterId?.let {
                    ChapterButton(it, t(if (rightToLeft) "reader.previousChapterRtl" else "reader.previousChapter"), forward = false)
                }
            val next =
                nextChapterId?.let {
                    ChapterButton(
                        it,
                        t(if (rightToLeft) "reader.nextChapterRtl" else "reader.nextChapter"),
                        forward = true,
                    )
                }
            return if (rightToLeft) listOfNotNull(next, previous) else listOfNotNull(previous, next)
        }

    /**
     * Book: the hint for a pull past an edge of the section. [overscroll]: how far past the edge, as a fraction of
     * the reading area's height, positive past the bottom (pulled up), negative past the top (pulled down). The
     * targets are the buttons' (next section, else the next part; the same backwards). Null: no hint to show.
     */
    fun pullHint(overscroll: Float): PullHint? {
        val distance = abs(overscroll)
        if (!isBook || sectionDocument == null || distance < PULL_SHOW) return null
        val forward = overscroll > 0
        val target = pullTarget(forward)
        val armed = target != null && distance >= PULL_THRESHOLD
        val label =
            when (target) {
                PullTarget.NextSection -> t(if (armed) "reader.release.nextSection" else "reader.pull.nextSection")
                PullTarget.NextPart -> t(if (armed) "reader.release.nextPart" else "reader.pull.nextPart")
                PullTarget.PreviousSection -> t(if (armed) "reader.release.previousSection" else "reader.pull.previousSection")
                PullTarget.PreviousPart -> t(if (armed) "reader.release.previousPart" else "reader.pull.previousPart")
                null -> t(if (forward) "reader.lastSection" else "reader.firstSection")
            }
        return PullHint(forward, (distance / PULL_THRESHOLD).coerceIn(0f, 1f), armed, label, target != null)
    }

    internal fun pullTarget(forward: Boolean): PullTarget? =
        when {
            forward && canGoForward -> PullTarget.NextSection
            forward && atEnd && nextChapterId != null -> PullTarget.NextPart
            !forward && canGoBack -> PullTarget.PreviousSection
            !forward && sections.isNotEmpty() && previousChapterId != null -> PullTarget.PreviousPart
            else -> null
        }

    companion object {
        private const val PAGE_GAP = 16

        /** Book: past this pull (fraction of the reading area's height), letting go turns. */
        const val PULL_THRESHOLD = 0.15f

        /** Book: a smaller pull (a bounce) shows no hint. */
        const val PULL_SHOW = 0.01f
    }
}

sealed interface ReaderIntent {
    /** Most visible page on screen (comic), or chosen section (book). */
    data class PageVisible(
        val index: Int,
    ) : ReaderIntent

    /** Comics: the item (index in `items`) now on screen (paged) or at the top (strips). */
    data class ItemVisible(
        val item: Int,
    ) : ReaderIntent

    /** Long strips: whether the strip can't scroll further up / down. */
    data class StripEdges(
        val atTop: Boolean,
        val atBottom: Boolean,
    ) : ReaderIntent

    /**
     * Paged modes: the item on screen ([item], index in `items`) and, when it is a tall page shown fit to width
     * ([tall], `TallPages.fitsWidth`), whether its scroll inside the page is at its top / bottom. Sent when the
     * item becomes current and whenever these change.
     */
    data class PageScroll(
        val item: Int,
        val tall: Boolean,
        val atTop: Boolean,
        val atBottom: Boolean,
    ) : ReaderIntent

    /**
     * Book: the finger let go after pulling past an edge of the section ([overscroll] as in
     * `ReaderUiState.pullHint`): past the threshold, the next or previous section (or part) opens.
     */
    data class PullReleased(
        val overscroll: Float,
    ) : ReaderIntent

    /** A single tap where the finger went up, normalized 0..1 of the reading area (origin top left). */
    data class Tap(
        val x: Float,
        val y: Float,
    ) : ReaderIntent

    data class CommandHandled(
        val seq: Int,
    ) : ReaderIntent

    /** Continuous strips: the strip's width and the screen's height (dp / pt), whenever the layout changes. */
    data class StripMeasured(
        val width: Float,
        val viewport: Float,
    ) : ReaderIntent

    /**
     * Continuous strips: the scroll offset from the first page's top, on every change. [byUser]: a drag or a fling
     * (not a `GlideTo`); [settled]: the scroll stopped.
     */
    data class StripScrolled(
        val offset: Float,
        val byUser: Boolean,
        val settled: Boolean,
    ) : ReaderIntent

    /** A page's luminance downscaled to `Gutters.COLUMNS` columns (row after row, 0..255), when it has no analysis. */
    data class PageLuminance(
        val page: Int,
        val values: IntArray,
    ) : ReaderIntent {
        override fun equals(other: Any?): Boolean = other is PageLuminance && other.page == page && other.values.contentEquals(values)

        override fun hashCode(): Int = 31 * page + values.contentHashCode()
    }

    /** A page whose size the source didn't give: the image's own size once loaded (any unit, only the shape counts). */
    data class PageSized(
        val page: Int,
        val width: Int,
        val height: Int,
    ) : ReaderIntent

    data class ReducedMotion(
        val on: Boolean,
    ) : ReaderIntent

    /** The "?" in the bars: "How to read" again. */
    data object ShowCoach : ReaderIntent

    /** A tap anywhere while the coach is up: it closes, and reading starts. */
    data object DismissCoach : ReaderIntent

    data object ToggleFocus : ReaderIntent

    data object ReadWithVoice : ReaderIntent

    /** Comics: the reading area went above 1× ([zoomed]) or back to it (pinch, double tap, a reset). */
    data class ZoomChanged(
        val zoomed: Boolean,
    ) : ReaderIntent

    /** Comics: the page slider let go on this page (index in `pages`). */
    data class SeekTo(
        val page: Int,
    ) : ReaderIntent

    data object Next : ReaderIntent

    data object Previous : ReaderIntent

    data object ToggleChrome : ReaderIntent

    data object Retry : ReaderIntent

    data object OpenNextChapter : ReaderIntent

    /** The settings sheet opened: a change of tap zones is shown once it closes (the sheet would hide it). */
    data object SettingsOpened : ReaderIntent

    data object SettingsClosed : ReaderIntent

    /** Opens the previous chapter at its last page (reading backwards). */
    data object OpenPreviousChapter : ReaderIntent

    /** The mode for this series (kept on the device for `(sourceKey, titleId)`). */
    data class SetMode(
        val mode: ReadingMode,
    ) : ReaderIntent

    data object ResetSeriesMode : ReaderIntent

    data class SetDefaultMode(
        val format: ComicFormat,
        val mode: ReadingMode,
    ) : ReaderIntent

    data class SetTapLayout(
        val layout: TapLayout,
    ) : ReaderIntent

    data class SetInvert(
        val invert: TapInvert,
    ) : ReaderIntent

    data class SetScrollFraction(
        val amount: ScrollAmount,
    ) : ReaderIntent

    data class SetShowTapZones(
        val show: Boolean,
    ) : ReaderIntent

    data class SetDoubleTapZoom(
        val enabled: Boolean,
    ) : ReaderIntent

    data class SetKeepAwake(
        val enabled: Boolean,
    ) : ReaderIntent

    data class SetVolumeKeys(
        val enabled: Boolean,
    ) : ReaderIntent

    data class SetVolumeKeysInverted(
        val inverted: Boolean,
    ) : ReaderIntent
}

/**
 * Reader for one unit, shared by Compose and SwiftUI: comic (pages in a reading mode, tap zones, chapter
 * transitions) or book (sections of an EPUB opened on the device). Resumes at the saved page or section; progress
 * is saved after a short pause. The native side measures, draws, animates and reports taps; every decision is here.
 */
class ReaderViewModel(
    private val sourceKey: String,
    private val titleId: String,
    private val chapterId: String,
    startPage: Int,
    private val repository: ReaderRepository,
    private val settings: ReaderSettingsRepository,
    private val headings: ChapterHeadings,
    // Before `progress`, so callers can still pass the progress sink as a trailing lambda.
    private val panels: ChapterPanels = ChapterPanels.None,
    private val coachSync: CoachSync = CoachSync.None,
    /** Book: typography and the position inside a section, on this device. */
    private val books: BookReadingRepository = BookReadingRepository.InMemory(),
    /** Book: the title's parts (its chapters), for the contents of a book in several parts. */
    private val parts: TitleChapters = TitleChapters { _, _ -> flowOf(null) },
    private val progress: ReadingProgressSink,
) : ViewModel() {
    private val mutableState =
        MutableStateFlow(ReaderUiState(currentIndex = startPage.coerceAtLeast(0), sectionFromEnd = startPage == START_AT_END))
    val state: StateFlow<ReaderUiState> = mutableState.asStateFlow()
    private var saveJob: Job? = null

    /** A page or section is waiting for the pause before it is saved. */
    private var progressPending = false
    private var overlayJob: Job? = null
    private var bookStarted = false
    private var chapter: ReaderChapter? = null
    private var seriesMode: ReadingMode? = null
    private var nextHeading: ChapterHeading? = null
    private var previousHeading: ChapterHeading? = null
    private var headingsFor: Pair<String?, String?>? = null
    private var zonesShown: TapZoneMap? = null
    private var settingsOpen = false
    private var zonesChangedInSettings = false
    private var stripAtTop = false
    private var stripAtBottom = false

    /** Long strips: the item last reported at the top of the screen (null until the strip has moved). */
    private var stripItem: Int? = null
    private var pageScroll: ReaderIntent.PageScroll? = null
    private var seq = 0

    // Continuous strips (reader/SPEC.md): the layout, its stops and where the reader is in them.
    private var stripWidth = 0f
    private var viewport = 0f
    private var layout: StripLayout? = null
    private var stops: List<Stop> = emptyList()
    private var stopIndex = 0
    private var gliding = false
    private var offset = 0f
    private var lowest = 0f
    private var userScrolled = false
    private var analysis: Map<Int, List<PanelBox>> = emptyMap()
    private val bands = mutableMapOf<Int, List<PanelBox>>()

    /** Sizes of pages the source gave none for, from the loaded images (page index → width, height). */
    private val measuredSizes = mutableMapOf<Int, Pair<Int, Int>>()
    private var restore: ReadingPosition? = null
    private var restored = false
    private var hideJob: Job? = null
    private var positionJob: Job? = null
    private var titleJob: Job? = null

    init {
        combine(
            repository.observe(sourceKey, chapterId),
            settings.observePreferences(),
            settings.observeSeriesMode(sourceKey, titleId),
        ) { chapter, preferences, mode -> Triple(chapter, preferences, mode) }
            .onEach { (chapter, preferences, mode) -> show(chapter, preferences, mode) }
            .launchIn(viewModelScope)
        repository
            .observeBook(sourceKey, chapterId)
            .onEach { book ->
                if (book != null) {
                    toc = book.toc
                    mutableState.update {
                        it.copy(
                            sections = book.sections,
                            preparingBook = false,
                            currentIndex = it.currentIndex.coerceIn(0, book.sections.lastIndex),
                        )
                    }
                    updateContents()
                    loadSection(state.value.currentIndex)
                }
            }.launchIn(viewModelScope)
        books.observeTypography().onEach(::typographyChanged).launchIn(viewModelScope)
        refresh()
        viewModelScope.launch { syncCoach() }
        viewModelScope.launch {
            analysis = panels.panels(sourceKey, chapterId)
            rebuildStops()
        }
        viewModelScope.launch {
            restore = settings.position(sourceKey, chapterId)?.takeIf { it.page == startPage }
            restoreIfReady()
        }
    }

    fun onIntent(intent: ReaderIntent) {
        when (intent) {
            is ReaderIntent.PageVisible -> moveTo(intent.index)
            is ReaderIntent.ItemVisible -> {
                if (state.value.isStrip) stripItem = intent.item
                itemVisible(stripItemShown(intent.item))
            }
            is ReaderIntent.StripEdges -> {
                stripAtTop = intent.atTop
                stripAtBottom = intent.atBottom
                // Once the strip has moved, reaching its bottom (or leaving it) changes the page read.
                stripItem?.let { itemVisible(stripItemShown(it)) }
            }
            is ReaderIntent.PageScroll -> pageScroll = intent
            is ReaderIntent.PullReleased -> pullReleased(intent.overscroll)
            is ReaderIntent.Tap -> if (state.value.coach != null) dismissCoach() else tap(intent.x, intent.y)
            ReaderIntent.ShowCoach -> showCoach()
            ReaderIntent.DismissCoach -> dismissCoach()
            is ReaderIntent.CommandHandled -> mutableState.update { if (it.command?.seq == intent.seq) it.copy(command = null) else it }
            is ReaderIntent.StripMeasured -> measured(intent.width, intent.viewport)
            is ReaderIntent.StripScrolled -> scrolled(intent.offset, intent.byUser, intent.settled)
            is ReaderIntent.PageLuminance -> luminance(intent.page, intent.values)
            is ReaderIntent.PageSized -> pageSized(intent.page, intent.width, intent.height)
            is ReaderIntent.ReducedMotion -> mutableState.update { it.copy(reducedMotion = intent.on) }
            ReaderIntent.ToggleFocus -> {
                val on = !state.value.preferences.focus
                updatePreferences { it.copy(focus = on) }
                mutableState.update { it.copy(focus = if (on && it.isStrip) stops.getOrNull(stopIndex)?.panel else null) }
            }
            ReaderIntent.ReadWithVoice ->
                if (state.value.voiceAvailable) command { ReaderCommand.OpenEpisode(it, chapterId, state.value.currentIndex) }
            ReaderIntent.Next ->
                when {
                    state.value.coach != null -> dismissCoach()
                    state.value.isBook -> moveTo(state.value.currentIndex + 1)
                    else -> forward()
                }
            ReaderIntent.Previous ->
                when {
                    state.value.coach != null -> dismissCoach()
                    state.value.isBook -> moveTo(state.value.currentIndex - 1)
                    else -> backward()
                }
            is ReaderIntent.ZoomChanged -> mutableState.update { it.copy(zoomed = intent.zoomed) }
            is ReaderIntent.SeekTo -> seekTo(intent.page)
            ReaderIntent.ToggleChrome -> mutableState.update { it.copy(chromeVisible = !it.chromeVisible) }
            ReaderIntent.Retry -> refresh()
            ReaderIntent.OpenNextChapter -> openNext()
            ReaderIntent.OpenPreviousChapter -> openPrevious()
            ReaderIntent.SettingsOpened -> {
                settingsOpen = true
                zonesChangedInSettings = false
            }
            ReaderIntent.SettingsClosed -> {
                settingsOpen = false
                if (zonesChangedInSettings && state.value.preferences.showTapZones) zonesShown?.let(::flashOverlay)
                zonesChangedInSettings = false
            }
            is ReaderIntent.SetMode ->
                if (intent.mode != ReadingMode.Book) viewModelScope.launch { settings.setSeriesMode(sourceKey, titleId, intent.mode) }
            ReaderIntent.ResetSeriesMode -> viewModelScope.launch { settings.setSeriesMode(sourceKey, titleId, null) }
            is ReaderIntent.SetDefaultMode ->
                if (intent.mode != ReadingMode.Book) {
                    updatePreferences {
                        when (intent.format) {
                            ComicFormat.Manga -> it.copy(mangaMode = intent.mode)
                            ComicFormat.Webtoon -> it.copy(webtoonMode = intent.mode)
                            ComicFormat.Comic -> it
                        }
                    }
                }
            is ReaderIntent.SetTapLayout -> updatePreferences { it.copy(tapLayout = intent.layout) }
            is ReaderIntent.SetInvert -> updatePreferences { it.copy(tapInvert = intent.invert) }
            is ReaderIntent.SetScrollFraction -> updatePreferences { it.copy(scrollAmount = intent.amount) }
            is ReaderIntent.SetShowTapZones -> {
                updatePreferences { it.copy(showTapZones = intent.show) }
                // Turned on: show what it is about (once the sheet closes); turned off: hide it now.
                if (!intent.show) {
                    hideOverlay()
                } else if (settingsOpen) {
                    zonesChangedInSettings = true
                } else {
                    zonesShown?.let(::flashOverlay)
                }
            }
            is ReaderIntent.SetDoubleTapZoom -> updatePreferences { it.copy(doubleTapZoom = intent.enabled) }
            is ReaderIntent.SetKeepAwake -> updatePreferences { it.copy(keepAwake = intent.enabled) }
            is ReaderIntent.SetVolumeKeys -> updatePreferences { it.copy(volumeKeys = intent.enabled) }
            is ReaderIntent.SetVolumeKeysInverted -> updatePreferences { it.copy(volumeKeysInverted = intent.inverted) }
            is BookIntent -> onBookIntent(intent)
        }
    }

    /**
     * Android: a volume key while the reader is on screen. Volume down goes forward, up goes back (swapped when
     * inverted). Returns true when the reader used the key (else the system changes the volume).
     */
    fun onVolumeKey(up: Boolean): Boolean {
        val current = state.value
        if (!current.volumeKeys || current.items.isEmpty()) return false
        if (current.coach != null) {
            dismissCoach()
            return true
        }
        if (up xor current.preferences.volumeKeysInverted) backward() else forward()
        return true
    }

    private fun tap(
        x: Float,
        y: Float,
    ) {
        val current = state.value
        if (current.isBook || current.items.isEmpty()) return
        hideOverlay()
        // Zoomed: a tap only shows or hides the bars (the zones would turn the page away from what is being read).
        if (current.zoomed) {
            mutableState.update { it.copy(chromeVisible = !it.chromeVisible) }
            return
        }
        when (TapZones.resolve(current.preferences.tapLayout, current.preferences.tapInvert, current.mode, x, y)) {
            TapAction.Menu -> mutableState.update { it.copy(chromeVisible = !it.chromeVisible) }
            TapAction.Next -> forward()
            TapAction.Previous -> backward()
        }
    }

    private fun forward() {
        val current = state.value
        if (current.items.isEmpty()) return
        mutableState.update { it.copy(chromeVisible = false) }
        val tall = tallPage(current)
        when {
            // A tall page scrolls inside itself first, then the next page comes.
            tall != null && !tall.atBottom -> command { ReaderCommand.ScrollBy(it, current.preferences.scrollAmount.fraction) }
            current.isPaged ->
                if (current.currentItem < current.items.lastIndex) goTo(current.currentItem + 1) else openNext()
            stops.isNotEmpty() && layout != null -> nextStop(current)
            stripAtBottom -> openNext()
            else -> command { ReaderCommand.ScrollBy(it, current.preferences.scrollAmount.fraction) }
        }
    }

    private fun backward() {
        val current = state.value
        if (current.items.isEmpty()) return
        mutableState.update { it.copy(chromeVisible = false) }
        val tall = tallPage(current)
        when {
            tall != null && !tall.atTop -> command { ReaderCommand.ScrollBy(it, -current.preferences.scrollAmount.fraction) }
            // Going back, a tall page opens at its bottom (reading backwards).
            current.isPaged -> if (current.currentItem > 0) goTo(current.currentItem - 1, atBottom = true) else openPrevious()
            stops.isNotEmpty() && layout != null -> previousStop()
            stripAtTop -> openPrevious()
            else -> command { ReaderCommand.ScrollBy(it, -current.preferences.scrollAmount.fraction) }
        }
    }

    /** Next on a strip: the next stop (taps add up while moving), then the end card, then the next chapter. */
    private fun nextStop(current: ReaderUiState) {
        when {
            stopIndex < stops.lastIndex -> glideTo(stopIndex + 1)
            stripAtBottom -> openNext()
            else -> {
                // The end card: "Finished: Chapter n" and the next chapter; one more Next opens it.
                stripAtBottom = true
                mutableState.update { it.copy(chromeVisible = true) }
                command { ReaderCommand.GoToItem(it, current.items.lastIndex, animated = !current.reducedMotion) }
            }
        }
    }

    private fun previousStop() {
        when {
            offset > (stops.getOrNull(stopIndex)?.offset ?: 0f) + ReadingStops.SAME_PX && !gliding -> glideTo(stopIndex)
            stopIndex > 0 -> glideTo(stopIndex - 1)
            stripAtTop -> openPrevious()
            else -> command { ReaderCommand.GoToItem(it, 0, animated = !state.value.reducedMotion) }
        }
    }

    private fun glideTo(index: Int) {
        val stop = stops[index]
        val moves = abs(stop.offset - (if (gliding) stops[stopIndex].offset else offset)) > ReadingStops.SAME_PX
        stopIndex = index
        mutableState.update { it.copy(focus = if (it.focusOn) stop.panel else null) }
        if (!moves) return
        gliding = true
        userScrolled = false
        command { ReaderCommand.GlideTo(it, stop.offset, animated = !state.value.reducedMotion) }
    }

    private fun measured(
        width: Float,
        height: Float,
    ) {
        if (width <= 0f || height <= 0f || (width == stripWidth && height == viewport)) return
        stripWidth = width
        viewport = height
        rebuildStops()
    }

    /** Stops are computed once per layout (width, screen height, pages, panels), never while scrolling. */
    private fun rebuildStops() {
        val current = state.value
        if (!current.isStrip || current.pages.isEmpty() || stripWidth <= 0f) {
            layout = null
            stops = emptyList()
            return
        }
        val strip = StripLayout(current.pages, stripWidth, current.pageGap.toFloat())
        layout = strip
        stops = ReadingStops.of(strip, viewport, bands + analysis)
        stopIndex = ReadingStops.current(stops, offset)
        mutableState.update { it.copy(focus = if (it.focusOn) stops.getOrNull(stopIndex)?.panel else null) }
        restoreIfReady()
    }

    /**
     * A source read on the device rarely gives its pages' sizes: the strip's layout (stops, offsets, focus) takes each
     * image's own shape once it has loaded, the shape the page is drawn at.
     */
    private fun pageSized(
        page: Int,
        width: Int,
        height: Int,
    ) {
        if (width <= 0 || height <= 0 || measuredSizes[page] == (width to height)) return
        // Our own pages carry their size: theirs is the one drawn.
        if (chapter?.pages?.firstOrNull { it.index == page }?.hasSize != false) return
        measuredSizes[page] = width to height
        val current = state.value
        val wasStrip = current.format == ComicFormat.Webtoon
        val open = chapter ?: return
        if ((ComicFormat.fromPages(sized(open.pages)) == ComicFormat.Webtoon) != wasStrip) {
            // The images turn out to be a vertical strip (or not): the mode follows their shape.
            show(open, current.preferences, seriesMode)
        } else {
            mutableState.update { it.copy(pages = sized(open.pages)) }
        }
        rebuildStops()
    }

    private fun sized(pages: List<ReaderPage>): List<ReaderPage> =
        pages.map { page ->
            val size = measuredSizes[page.index]
            if (page.hasSize || size == null) page else page.copy(width = size.first, height = size.second)
        }

    private fun luminance(
        page: Int,
        values: IntArray,
    ) {
        if (page in analysis || page in bands || values.size < Gutters.COLUMNS) return
        val rows = (0 until values.size / Gutters.COLUMNS).map { r -> values.copyOfRange(r * Gutters.COLUMNS, (r + 1) * Gutters.COLUMNS) }
        bands[page] = Gutters.bands(page, rows)
        rebuildStops()
    }

    private fun restoreIfReady() {
        val position = restore ?: return
        val strip = layout ?: return
        if (restored || position.page !in strip.tops.indices) return
        restored = true
        val target = strip.tops[position.page] + position.offset * strip.heights[position.page]
        command { ReaderCommand.GlideTo(it, target, animated = false) }
    }

    private fun scrolled(
        at: Float,
        byUser: Boolean,
        settled: Boolean,
    ) {
        val strip = layout
        val before = offset
        offset = at
        if (byUser) {
            userScrolled = true
            gliding = false
        }
        if (settled) {
            gliding = false
            if (userScrolled) stopIndex = ReadingStops.current(stops, at)
            userScrolled = false
            mutableState.update { it.copy(focus = if (it.focusOn) stops.getOrNull(stopIndex)?.panel else null) }
        }
        if (strip == null || viewport <= 0f) return
        // Bars: a scroll down hides them after a moment; a scroll up of a quarter screen brings them back.
        if (at > before) {
            lowest = at
            if (state.value.chromeVisible && byUser) scheduleHide()
        } else if (lowest - at > CHROME_BACK * viewport) {
            lowest = at
            hideJob?.cancel()
            if (!state.value.chromeVisible) mutableState.update { it.copy(chromeVisible = true) }
        }
        stripAtBottom = stripAtBottom && at >= strip.total - viewport - ReadingStops.SAME_PX
        val read = ((at + viewport) / strip.total).coerceIn(0f, 1f)
        if (abs(read - state.value.progress) >= PROGRESS_STEP || read == 1f) mutableState.update { it.copy(progress = read) }
        val page = strip.pageAt(at.coerceAtLeast(0f))
        moveTo(page)
        positionJob?.cancel()
        positionJob =
            viewModelScope.launch {
                delay(POSITION_MS)
                val share = ((at - strip.tops[page]) / strip.heights[page]).coerceIn(0f, 1f)
                settings.savePosition(sourceKey, chapterId, ReadingPosition(page, share))
            }
    }

    private fun scheduleHide() {
        if (hideJob?.isActive == true) return
        hideJob =
            viewModelScope.launch {
                delay(CHROME_HIDE_MS)
                mutableState.update { it.copy(chromeVisible = false) }
            }
    }

    /**
     * Long strips: the item at the top of the screen, except at the bottom of the strip, where the last page is the
     * one read (it can never reach the top of the screen: the chapter ended on "6 / 7" and was saved so).
     */
    private fun stripItemShown(item: Int): Int {
        val current = state.value
        return if (current.isStrip && stripAtBottom) maxOf(item, current.pages.size) else item
    }

    /** Paged modes: the current item's scroll inside its page, when it is a tall page. */
    private fun tallPage(current: ReaderUiState): ReaderIntent.PageScroll? =
        pageScroll?.takeIf { current.isPaged && it.tall && it.item == current.currentItem }

    private fun goTo(
        item: Int,
        atBottom: Boolean = false,
    ) {
        // A volume key while zoomed: the next page starts at 1×.
        if (state.value.zoomed) resetZoom()
        itemVisible(item)
        command { ReaderCommand.GoToItem(it, item, animated = true, atBottom = atBottom) }
    }

    /**
     * The page slider: the page at once, from its top, in every mode; the zoom goes back to 1×. A strip counts the
     * jump as moved (its next reports say what is at the top of the screen).
     */
    private fun seekTo(page: Int) {
        val current = state.value
        if (current.isBook || current.pages.isEmpty() || current.items.isEmpty()) return
        val item = page.coerceIn(0, current.pages.lastIndex) + 1
        resetZoom()
        if (current.isStrip) stripItem = item
        itemVisible(item)
        // The continuous strip moves by its own offsets: the page's top at the top of the screen, at once.
        val strip = layout
        if (current.isStrip && strip != null && page in strip.tops.indices) {
            val top = strip.tops[page.coerceIn(0, current.pages.lastIndex)]
            stopIndex = ReadingStops.current(stops, top)
            gliding = true
            userScrolled = false
            command { ReaderCommand.GlideTo(it, top, animated = false) }
            return
        }
        command { ReaderCommand.JumpTo(it, item) }
    }

    /** Back to 1× (the native side follows `zoomReset`); taps turn pages again. */
    private fun resetZoom() {
        mutableState.update { it.copy(zoomed = false, zoomReset = it.zoomReset + 1) }
    }

    /** Book: letting go past the threshold opens what the hint announced (the buttons' targets). */
    private fun pullReleased(overscroll: Float) {
        val current = state.value
        val hint = current.pullHint(overscroll) ?: return
        if (!hint.armed) return
        when (current.pullTarget(hint.forward)) {
            PullTarget.NextSection -> moveTo(current.currentIndex + 1)
            PullTarget.NextPart -> openNext()
            PullTarget.PreviousSection -> moveTo(current.currentIndex - 1, fromEnd = true)
            PullTarget.PreviousPart -> openPrevious()
            null -> Unit
        }
    }

    private fun openNext() {
        val next = state.value.nextChapterId ?: return
        command { ReaderCommand.OpenChapter(it, next, 0) }
    }

    private fun openPrevious() {
        val previous = state.value.previousChapterId ?: return
        command { ReaderCommand.OpenChapter(it, previous, START_AT_END) }
    }

    private fun command(make: (Int) -> ReaderCommand) {
        seq += 1
        val next = make(seq)
        mutableState.update { it.copy(command = next) }
    }

    private fun itemVisible(item: Int) {
        val current = state.value
        if (item !in current.items.indices) return
        // Another item: its scroll inside the page is unknown until the screen reports it.
        if (pageScroll?.item != item) pageScroll = null
        if (item != current.currentItem) mutableState.update { it.copy(currentItem = item) }
        (current.items[item] as? ReaderItem.Page)?.let { moveTo(it.page.index) }
    }

    private var opened = false

    private fun show(
        chapter: ReaderChapter?,
        preferences: ReaderPreferences,
        seriesMode: ReadingMode?,
    ) {
        this.chapter = chapter
        this.seriesMode = seriesMode
        if (chapter == null) {
            mutableState.update { it.copy(preferences = preferences) }
            return
        }
        if (!opened) {
            // Opening a chapter is reading it: "Continue reading" brings the reader back here even before a page turns.
            opened = true
            progress.save(sourceKey, titleId, chapterId, state.value.currentIndex)
        }
        val pages = sized(chapter.pages)
        // The format as recognised: what the source says, unless the pages themselves are a vertical strip.
        val strip = ComicFormat.fromPages(pages) == ComicFormat.Webtoon
        val format = if (strip) ComicFormat.Webtoon else chapter.format
        val mode = ReadingModes.resolve(chapter.mode, format, seriesMode, preferences, strip)
        val isBook = mode == ReadingMode.Book
        // Another layout: the strip's last report no longer says where the reader is, and the zoom starts again.
        if (mode != state.value.mode) {
            stripItem = null
            if (state.value.zoomed) resetZoom()
        }
        mutableState.update {
            // A chapter seen before its pages (saved for the first time, or a source that sends them later) keeps the
            // resume page: clamped to an empty list, it opened at page 1.
            val index =
                when {
                    isBook || chapter.pages.isEmpty() -> it.currentIndex
                    else -> it.currentIndex.coerceIn(0, chapter.pages.lastIndex)
                }
            val items = if (isBook) emptyList() else items(chapter)
            it.copy(
                titleName = chapter.titleName,
                chapterLabel = if (isBook) chapter.label else listOfNotNull(chapter.label, chapter.name).joinToString(" · "),
                mode = mode,
                format = if (isBook) null else format,
                pages = pages,
                items = items,
                currentIndex = index,
                // The previous-chapter card comes first: page i is item i + 1. The same item stays on screen when
                // the mode or a setting changes (a chapter card included).
                currentItem =
                    when {
                        items.isEmpty() -> 0
                        it.items.size == items.size -> it.currentItem
                        else -> index + 1
                    },
                previousChapterId = chapter.previousChapterId,
                nextChapterId = chapter.nextChapterId,
                preferences = preferences,
                settings =
                    ReaderSettingsSheet.of(
                        if (isBook) chapter.format.defaultMode else mode,
                        format,
                        seriesMode,
                        preferences,
                    ),
                unavailable = false,
            )
        }
        if (isBook) {
            if (!bookStarted) prepareBook()
            coachIfNew(mode, preferences)
            return
        }
        loadHeadings(chapter)
        updateZones(mode, preferences)
        coachIfNew(mode, preferences)
        mutableState.update {
            it.copy(voiceAvailable = sourceKey == INTERNAL_SOURCE && chapter.pages.isNotEmpty(), prefetch = prefetch(it))
        }
        rebuildStops()
        showTitleCard(chapter)
    }

    /** The title card fades in over the first page when the chapter opens, and out after a moment. */
    private fun showTitleCard(chapter: ReaderChapter) {
        if (titleJob != null) return
        val card = t("reader.titleCard", "title" to chapter.titleName, "chapter" to state.value.chapterLabel)
        mutableState.update { it.copy(titleCard = card) }
        titleJob =
            viewModelScope.launch {
                delay(TITLE_MS)
                mutableState.update { it.copy(titleCard = null) }
            }
    }

    private fun prefetch(state: ReaderUiState): List<String> =
        if (state.isBook || state.currentIndex >= state.pages.size) {
            // A book, or a resume page whose pages are not there yet (`START_AT_END` included).
            emptyList()
        } else {
            state.pages
                .drop(state.currentIndex + 1)
                .take(PREFETCH)
                .map { it.url }
        }

    private fun items(chapter: ReaderChapter): List<ReaderItem> {
        if (chapter.pages.isEmpty()) return emptyList()
        val here = chapterLabel(chapter.number)
        val previous =
            TransitionCard(
                forward = false,
                heading = t("reader.current", "chapter" to here),
                target =
                    chapter.previousChapterId?.let {
                        previousHeading?.label?.let { label -> t("reader.previous", "chapter" to label) } ?: t("reader.previousUnknown")
                    } ?: t("reader.firstChapter"),
                actionLabel = chapter.previousChapterId?.let { t("reader.previousChapter") },
                chapterId = chapter.previousChapterId,
            )
        val next =
            TransitionCard(
                forward = true,
                heading = t("reader.finished", "chapter" to here),
                target =
                    chapter.nextChapterId?.let {
                        nextHeading?.label?.let { label -> t("reader.next", "chapter" to label) } ?: t("reader.nextUnknown")
                    } ?: t("reader.lastChapter"),
                actionLabel = chapter.nextChapterId?.let { t("reader.nextChapter") },
                chapterId = chapter.nextChapterId,
            )
        return listOf(ReaderItem.Transition(previous)) + chapter.pages.map { ReaderItem.Page(it) } + ReaderItem.Transition(next)
    }

    /** Number and name of the neighbouring chapters, when the device knows them (once per pair of ids). */
    private fun loadHeadings(chapter: ReaderChapter) {
        val ids = chapter.previousChapterId to chapter.nextChapterId
        if (headingsFor == ids) return
        headingsFor = ids
        viewModelScope.launch {
            previousHeading = ids.first?.let { headings.heading(sourceKey, titleId, it) }
            nextHeading = ids.second?.let { headings.heading(sourceKey, titleId, it) }
            val latest = this@ReaderViewModel.chapter ?: return@launch
            if (state.value.items.isNotEmpty()) mutableState.update { it.copy(items = items(latest)) }
        }
    }

    /**
     * The overlay: on the reader's first opening on this device, then whenever the zones change (after the
     * settings sheet closes when the change was made there).
     */
    private fun updateZones(
        mode: ReadingMode,
        preferences: ReaderPreferences,
    ) {
        val map = TapZones.map(preferences.tapLayout, preferences.tapInvert, mode)
        val before = zonesShown
        zonesShown = map
        if (!preferences.showTapZones || map == before) return
        if (before == null) {
            // The first chapter of its kind opens with the coach, which draws the zones itself.
            if (!preferences.tapZonesSeen && coachKind() in preferences.coachSeen) {
                flashOverlay(map)
                updatePreferences { it.copy(tapZonesSeen = true) }
            }
        } else if (settingsOpen) {
            zonesChangedInSettings = true
        } else {
            flashOverlay(map)
        }
    }

    /** The first chapter of each kind opens with "How to read" (once per kind on this device). */
    private fun coachIfNew(
        mode: ReadingMode,
        preferences: ReaderPreferences,
    ) {
        if (coachChecked) return
        val current = state.value
        // Decided once there is something to read: a chapter still loading must not use up its first open.
        if (current.unavailable || (current.items.isEmpty() && !current.isBook)) return
        coachChecked = true
        if (coachKind() !in preferences.coachSeen + accountGuides) showCoach()
    }

    private var coachChecked = false

    private fun showCoach() {
        val current = state.value
        if (current.unavailable || (current.items.isEmpty() && !current.isBook)) return
        val kind = coachKind()
        val zones = if (current.isBook) null else TapZones.map(current.preferences.tapLayout, current.preferences.tapInvert, current.mode)
        val title = current.formatLabel ?: ReaderText.mode(current.mode)
        hideOverlay()
        mutableState.update {
            it.copy(coach = ReaderCoach.of(kind, current.mode, title, zones, it.preferences.doubleTapZoom), chromeVisible = true)
        }
    }

    private fun dismissCoach() {
        val coach = state.value.coach ?: return
        mutableState.update { it.copy(coach = null) }
        updatePreferences { it.copy(coachSeen = it.coachSeen + coach.kind, tapZonesSeen = true) }
        if (coach.kind != CoachKind.Book) viewModelScope.launch { coachSync.mark(coach.kind) }
    }

    /** The guides the account has seen (on any device), once known. */
    private var accountGuides: Set<CoachKind> = emptySet()

    private fun coachKind(): CoachKind = CoachKind.of(state.value.format, state.value.isBook)

    /**
     * The account's guides: seen on another device → seen here (and a guide up for one goes away); seen here while
     * offline → told to the account now.
     */
    private suspend fun syncCoach() {
        val account = coachSync.seen() ?: return
        accountGuides = account
        val local = settings.observePreferences().first().coachSeen
        (local - account - CoachKind.Book).forEach { coachSync.mark(it) }
        if (!local.containsAll(account)) updatePreferences { it.copy(coachSeen = it.coachSeen + account) }
        val shown = state.value.coach
        if (shown != null && shown.kind in account) mutableState.update { it.copy(coach = null) }
    }

    private fun flashOverlay(map: TapZoneMap) {
        overlayJob?.cancel()
        mutableState.update { it.copy(tapOverlay = TapOverlay.of(map)) }
        overlayJob =
            viewModelScope.launch {
                delay(OVERLAY_MS)
                mutableState.update { it.copy(tapOverlay = null) }
            }
    }

    private fun hideOverlay() {
        if (state.value.tapOverlay == null) return
        overlayJob?.cancel()
        mutableState.update { it.copy(tapOverlay = null) }
    }

    private fun updatePreferences(transform: (ReaderPreferences) -> ReaderPreferences) {
        viewModelScope.launch { settings.updatePreferences(transform) }
    }

    private fun prepareBook() {
        if (!partsStarted) observeParts()
        bookStarted = true
        mutableState.update { it.copy(preparingBook = it.sections.isEmpty()) }
        viewModelScope.launch {
            val result = repository.loadBook(sourceKey, chapterId)
            if (result is AppResult.Err) {
                bookStarted = false
                mutableState.update {
                    it.copy(
                        preparingBook = false,
                        unavailable = it.sections.isEmpty(),
                        error = result.error.userMessage(),
                    )
                }
            }
        }
    }

    private fun loadSection(index: Int) {
        viewModelScope.launch {
            val html = repository.sectionHtml(sourceKey, chapterId, index) ?: return@launch
            if (state.value.currentIndex != index) return@launch
            sectionHtml = index to html
            val opening = if (resumeChecked) null else resumeOpening(index)
            mutableState.update {
                if (it.currentIndex != index) return@update it
                it.copy(
                    sectionDocument = bookDocument(index, html),
                    book =
                        if (opening !=
                            null
                        ) {
                            it.book.copy(opening = opening)
                        } else {
                            it.book
                        },
                )
            }
        }
    }

    private fun refresh() {
        mutableState.update { it.copy(freshness = Freshness.Refreshing, error = null) }
        viewModelScope.launch {
            val result = repository.refresh(sourceKey, titleId, chapterId)
            mutableState.update {
                when (result) {
                    is AppResult.Ok -> it.copy(freshness = Freshness.Fresh)
                    is AppResult.Err -> {
                        val nothing = it.pages.isEmpty() && it.sections.isEmpty()
                        it.copy(
                            freshness = Freshness.Offline,
                            unavailable = nothing && it.titleName.isEmpty(),
                            // Saved pages on screen: offline or a source that didn't answer is no error; with nothing
                            // saved, it says which ("You're offline.", "Project Gutenberg isn't answering right now.").
                            error =
                                StaleReason.of(result.error).let { reason ->
                                    if (reason != null && !nothing) null else reason?.notice(hasSaved = false) ?: result.error.userMessage()
                                },
                        )
                    }
                }
            }
        }
    }

    private fun moveTo(
        index: Int,
        fromEnd: Boolean = false,
    ) {
        val current = state.value
        val count = if (current.isBook) current.sections.size else current.pages.size
        if (index == current.currentIndex || index !in 0 until count) return
        mutableState.update {
            it
                .copy(
                    currentIndex = index,
                    currentItem = if (it.items.isEmpty()) it.currentItem else index + 1,
                    sectionDocument = if (it.isBook) null else it.sectionDocument,
                    sectionFromEnd = it.isBook && fromEnd,
                ).let { moved -> moved.copy(prefetch = prefetch(moved)) }
        }
        if (current.isBook) {
            sectionMoved(index, fromEnd)
            loadSection(index)
        }
        saveJob?.cancel()
        progressPending = true
        saveJob =
            viewModelScope.launch {
                delay(SAVE_DELAY_MS)
                progressPending = false
                progress.save(sourceKey, titleId, chapterId, index)
            }
    }

    override fun onCleared() {
        // When leaving the reader, the last page seen is saved without waiting for the pause to end. A flag, not the
        // job: `viewModelScope` is already cancelled when `onCleared` runs, so the job never looked active here.
        if (progressPending) {
            progressPending = false
            progress.save(sourceKey, titleId, chapterId, state.value.currentIndex)
        }
        // The same for the position inside a book's section.
        if (positionPending) {
            positionPending = false
            books.savePosition(sourceKey, chapterId, BookPosition(state.value.currentIndex, sectionFraction))
        }
        super.onCleared()
    }

    // Books: typography, the position inside the section, the contents.

    private var typography = BookTypography()
    private var systemTextScale = 1f
    private var toc: List<TocEntry> = emptyList()
    private var titleParts: List<DownloadableChapter> = emptyList()
    private var partsStarted = false

    /** The current section's HTML (sanitized, unstyled): styled again when the typography changes. */
    private var sectionHtml: Pair<Int, String>? = null

    /** Where the top of the screen is in the current section, as the native side last reported it. */
    private var sectionFraction = 0f
    private var resumeChecked = false
    private var bookPositionJob: Job? = null
    private var positionPending = false
    private var openingSeq = 0

    private fun onBookIntent(intent: BookIntent) {
        when (intent) {
            is BookIntent.SystemTextScale ->
                if (intent.scale > 0f && intent.scale != systemTextScale) {
                    systemTextScale = intent.scale
                    restyle()
                }
            BookIntent.TextSmaller -> updateTypography { it.smaller() }
            BookIntent.TextBigger -> updateTypography { it.bigger() }
            BookIntent.ResetTextSize -> updateTypography { it.copy(textPercent = BookTypography().textPercent) }
            is BookIntent.SetLineSpacing -> updateTypography { it.copy(lineSpacing = intent.spacing) }
            is BookIntent.SetMargins -> updateTypography { it.copy(margins = intent.margins) }
            is BookIntent.SetFont -> updateTypography { it.copy(font = intent.font) }
            is BookIntent.SetJustify -> updateTypography { it.copy(justify = intent.justify) }
            is BookIntent.SetTheme -> updateTypography { it.copy(theme = intent.theme) }
            is BookIntent.SectionScrolled -> sectionScrolled(intent.section, intent.fraction)
            is BookIntent.ChooseContents -> chooseContents(intent.key)
        }
    }

    private fun updateTypography(transform: (BookTypography) -> BookTypography) {
        viewModelScope.launch { books.updateTypography(transform) }
    }

    private fun typographyChanged(next: BookTypography) {
        val changed = next != typography
        typography = next
        mutableState.update {
            it.copy(book = it.book.copy(settings = BookSettingsSheet.of(next), pageBackground = BookPalette.of(next.theme).background))
        }
        if (changed) restyle()
    }

    private fun bookDocument(
        index: Int,
        html: String,
    ): String {
        val title =
            state.value.sections
                .getOrNull(index)
                ?.title
                .orEmpty()
        return BookStyle.document(title, html, typography, systemTextScale)
    }

    /** The section on screen set again (typography, the device's text size), kept where the reader was in it. */
    private fun restyle() {
        val (index, html) = sectionHtml ?: return
        val current = state.value
        if (!current.isBook || current.currentIndex != index || current.sectionDocument == null) return
        mutableState.update {
            it.copy(sectionDocument = bookDocument(index, html), book = it.book.copy(opening = opening(index, fraction = sectionFraction)))
        }
    }

    /**
     * The first section shown: its end when the reader came back from the next part, else where the reader was in it
     * on this device, if it is the one the reader opens (the library's progress says which section).
     */
    private suspend fun resumeOpening(index: Int): SectionOpening? {
        resumeChecked = true
        if (state.value.sectionFromEnd) return opening(index, fromEnd = true)
        val saved = books.position(sourceKey, chapterId)?.takeIf { it.section == index && it.fraction > 0f } ?: return null
        sectionFraction = saved.fraction
        return opening(index, fraction = saved.fraction)
    }

    private fun opening(
        section: Int,
        fraction: Float? = null,
        anchor: String? = null,
        fromEnd: Boolean = false,
    ): SectionOpening {
        openingSeq += 1
        return SectionOpening(openingSeq, section, fraction, anchor, fromEnd)
    }

    /** Another section: it opens at its top (or its end going back), and that is where the reader now is. */
    private fun sectionMoved(
        index: Int,
        fromEnd: Boolean,
    ) {
        resumeChecked = true
        sectionHtml = null
        mutableState.update { it.copy(book = it.book.copy(opening = opening(index, fromEnd = fromEnd))) }
        updateContents()
        // Its end is not known before it is laid out: the native side reports it once there.
        sectionScrolled(index, 0f)
    }

    private fun sectionScrolled(
        section: Int,
        fraction: Float,
    ) {
        val current = state.value
        if (!current.isBook || section != current.currentIndex || fraction.isNaN()) return
        sectionFraction = fraction.coerceIn(0f, 1f)
        bookPositionJob?.cancel()
        positionPending = true
        bookPositionJob =
            viewModelScope.launch {
                delay(POSITION_DELAY_MS)
                positionPending = false
                books.savePosition(sourceKey, chapterId, BookPosition(section, sectionFraction))
            }
    }

    private fun chooseContents(key: String) {
        when (val target = BookContents.target(key, toc)) {
            is BookContents.Target.Part -> if (target.chapterId != chapterId) command { ReaderCommand.OpenChapter(it, target.chapterId, 0) }
            is BookContents.Target.Section -> {
                val current = state.value
                if (target.index !in current.sections.indices) return
                if (target.index != current.currentIndex) moveTo(target.index)
                // Its top, or the entry's element inside it: applied once the section is there.
                mutableState.update {
                    it.copy(
                        book =
                            it.book.copy(
                                opening =
                                    opening(
                                        target.index,
                                        fraction =
                                            if (target.anchor ==
                                                null
                                            ) {
                                                0f
                                            } else {
                                                null
                                            },
                                        anchor = target.anchor,
                                    ),
                            ),
                    )
                }
                if (target.anchor == null) sectionScrolled(target.index, 0f)
            }
            null -> Unit
        }
    }

    private fun observeParts() {
        partsStarted = true
        parts
            .observe(sourceKey, titleId)
            .onEach { title ->
                titleParts = title?.takeIf { it.isBook }?.chapters.orEmpty()
                updateContents()
            }.launchIn(viewModelScope)
    }

    private fun updateContents() {
        mutableState.update {
            it.copy(book = it.book.copy(contents = BookContents.rows(it.sections, toc, it.currentIndex, chapterId, titleParts)))
        }
    }

    companion object {
        /** `startPage` that opens a chapter at its last page (going back from the next chapter). */
        const val START_AT_END = Int.MAX_VALUE

        private const val SAVE_DELAY_MS = 800L

        /** Book: the position inside the section is saved once scrolling has paused this long. */
        const val POSITION_DELAY_MS = 1000L
        const val OVERLAY_MS = 1500L

        // reader/SPEC.md §6-7.
        const val CHROME_HIDE_MS = 2500L
        private const val CHROME_BACK = 0.25f
        const val TITLE_MS = 1600L
        private const val POSITION_MS = 500L
        private const val PROGRESS_STEP = 0.002f
        private const val PREFETCH = 3
        private const val INTERNAL_SOURCE = "vovoscan"
    }
}
