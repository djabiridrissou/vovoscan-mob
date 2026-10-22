package com.vovoscan.feature.reader.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.Freshness
import com.vovoscan.core.common.userMessage
import com.vovoscan.feature.reader.domain.BookSection
import com.vovoscan.feature.reader.domain.ChapterHeading
import com.vovoscan.feature.reader.domain.ChapterHeadings
import com.vovoscan.feature.reader.domain.ComicFormat
import com.vovoscan.feature.reader.domain.ReaderChapter
import com.vovoscan.feature.reader.domain.ReaderPage
import com.vovoscan.feature.reader.domain.ReaderPreferences
import com.vovoscan.feature.reader.domain.ReaderRepository
import com.vovoscan.feature.reader.domain.ReaderSettingsRepository
import com.vovoscan.feature.reader.domain.ReadingMode
import com.vovoscan.feature.reader.domain.ReadingModes
import com.vovoscan.feature.reader.domain.ReadingProgressSink
import com.vovoscan.feature.reader.domain.ScrollAmount
import com.vovoscan.feature.reader.domain.TapAction
import com.vovoscan.feature.reader.domain.TapInvert
import com.vovoscan.feature.reader.domain.TapLayout
import com.vovoscan.feature.reader.domain.TapZoneMap
import com.vovoscan.feature.reader.domain.TapZones
import com.vovoscan.feature.reader.domain.ZoneRect
import com.vovoscan.feature.reader.domain.chapterLabel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

    /** Paged modes: show this item (index in `items`), animated. */
    data class GoToItem(
        override val seq: Int,
        val item: Int,
        val animated: Boolean,
    ) : ReaderCommand

    /** Long strips: smooth scroll by this fraction of the reading area's height (negative: up). */
    data class ScrollBy(
        override val seq: Int,
        val fraction: Float,
    ) : ReaderCommand

    /** Replace this reader by another chapter, from `startPage` (`ReaderViewModel.START_AT_END`: its last page). */
    data class OpenChapter(
        override val seq: Int,
        val chapterId: String,
        val startPage: Int,
    ) : ReaderCommand
}

data class ReaderUiState(
    val titleName: String = "",
    /** "Chapter 2 · The last gate" or "Full text". */
    val chapterLabel: String = "",
    /** The mode in use: per-series choice, else the user's default for the format, else the format rule. */
    val mode: ReadingMode = ReadingMode.LongStrip,
    val pages: List<ReaderPage> = emptyList(),
    /** Comics: the previous-chapter card, the pages, the next-chapter card. */
    val items: List<ReaderItem> = emptyList(),
    /** Index in `items` of what is on screen (paged) or at the top of the screen (strips). */
    val currentItem: Int = 0,
    /** Book: table-of-contents sections. */
    val sections: List<BookSection> = emptyList(),
    /** Book: the current section, ready to display (sanitized and styled HTML). */
    val sectionDocument: String? = null,
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
    val command: ReaderCommand? = null,
    val freshness: Freshness = Freshness.Refreshing,
    /** Never opened and no network: nothing to show. */
    val unavailable: Boolean = false,
    val error: String? = null,
) {
    val isBook: Boolean get() = mode == ReadingMode.Book

    val isPaged: Boolean get() = mode.isPaged

    val isStrip: Boolean get() = mode.isStrip

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
                isBook -> "Section ${currentIndex + 1} / $count"
                else -> "${currentIndex + 1} / $count"
            }

    val sectionTitle: String? get() = sections.getOrNull(currentIndex)?.title

    val canGoBack: Boolean get() = currentIndex > 0

    val canGoForward: Boolean get() = currentIndex < count - 1

    val atEnd: Boolean get() = count > 0 && currentIndex >= count - 1

    /** End-of-unit button. */
    val nextLabel: String? get() = nextChapterId?.let { if (isBook) "Next part →" else "Next chapter →" }

    val rightToLeft: Boolean get() = mode == ReadingMode.PagedRightToLeft

    private companion object {
        const val PAGE_GAP = 16
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

    /** A single tap where the finger went up, normalized 0..1 of the reading area (origin top left). */
    data class Tap(
        val x: Float,
        val y: Float,
    ) : ReaderIntent

    data class CommandHandled(
        val seq: Int,
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
    private val progress: ReadingProgressSink,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ReaderUiState(currentIndex = startPage.coerceAtLeast(0)))
    val state: StateFlow<ReaderUiState> = mutableState.asStateFlow()
    private var saveJob: Job? = null
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
    private var seq = 0

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
                    mutableState.update {
                        it.copy(
                            sections = book.sections,
                            preparingBook = false,
                            currentIndex = it.currentIndex.coerceIn(0, book.sections.lastIndex),
                        )
                    }
                    loadSection(state.value.currentIndex)
                }
            }.launchIn(viewModelScope)
        refresh()
    }

    fun onIntent(intent: ReaderIntent) {
        when (intent) {
            is ReaderIntent.PageVisible -> moveTo(intent.index)
            is ReaderIntent.ItemVisible -> itemVisible(intent.item)
            is ReaderIntent.StripEdges -> {
                stripAtTop = intent.atTop
                stripAtBottom = intent.atBottom
            }
            is ReaderIntent.Tap -> tap(intent.x, intent.y)
            is ReaderIntent.CommandHandled -> mutableState.update { if (it.command?.seq == intent.seq) it.copy(command = null) else it }
            ReaderIntent.Next -> if (state.value.isBook) moveTo(state.value.currentIndex + 1) else forward()
            ReaderIntent.Previous -> if (state.value.isBook) moveTo(state.value.currentIndex - 1) else backward()
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
        }
    }

    /**
     * Android: a volume key while the reader is on screen. Volume down goes forward, up goes back (swapped when
     * inverted). Returns true when the reader used the key (else the system changes the volume).
     */
    fun onVolumeKey(up: Boolean): Boolean {
        val current = state.value
        if (!current.volumeKeys || current.items.isEmpty()) return false
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
        when {
            current.isPaged ->
                if (current.currentItem < current.items.lastIndex) goTo(current.currentItem + 1) else openNext()
            stripAtBottom -> openNext()
            else -> command { ReaderCommand.ScrollBy(it, current.preferences.scrollAmount.fraction) }
        }
    }

    private fun backward() {
        val current = state.value
        if (current.items.isEmpty()) return
        mutableState.update { it.copy(chromeVisible = false) }
        when {
            current.isPaged -> if (current.currentItem > 0) goTo(current.currentItem - 1) else openPrevious()
            stripAtTop -> openPrevious()
            else -> command { ReaderCommand.ScrollBy(it, -current.preferences.scrollAmount.fraction) }
        }
    }

    private fun goTo(item: Int) {
        itemVisible(item)
        command { ReaderCommand.GoToItem(it, item, animated = true) }
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
        if (item != current.currentItem) mutableState.update { it.copy(currentItem = item) }
        (current.items[item] as? ReaderItem.Page)?.let { moveTo(it.page.index) }
    }

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
        val mode = ReadingModes.resolve(chapter.mode, chapter.format, seriesMode, preferences)
        val isBook = mode == ReadingMode.Book
        mutableState.update {
            val index = if (isBook) it.currentIndex else it.currentIndex.coerceIn(0, (chapter.pages.size - 1).coerceAtLeast(0))
            val items = if (isBook) emptyList() else items(chapter)
            it.copy(
                titleName = chapter.titleName,
                chapterLabel = if (isBook) chapter.label else listOfNotNull(chapter.label, chapter.name).joinToString(" · "),
                mode = mode,
                pages = chapter.pages,
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
                        chapter.format,
                        seriesMode,
                        preferences,
                    ),
                unavailable = false,
            )
        }
        if (isBook) {
            if (!bookStarted) prepareBook()
            return
        }
        loadHeadings(chapter)
        updateZones(mode, preferences)
    }

    private fun items(chapter: ReaderChapter): List<ReaderItem> {
        if (chapter.pages.isEmpty()) return emptyList()
        val here = chapterLabel(chapter.number)
        val previous =
            TransitionCard(
                forward = false,
                heading = "Current: $here",
                target =
                    chapter.previousChapterId?.let { "Previous: ${previousHeading?.label ?: "the chapter before"}" }
                        ?: "This is the first chapter.",
                actionLabel = chapter.previousChapterId?.let { "← Previous chapter" },
                chapterId = chapter.previousChapterId,
            )
        val next =
            TransitionCard(
                forward = true,
                heading = "Finished: $here",
                target =
                    chapter.nextChapterId?.let { "Next: ${nextHeading?.label ?: "the next chapter"}" }
                        ?: "This is the last chapter for now.",
                actionLabel = chapter.nextChapterId?.let { "Next chapter →" },
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
            if (!preferences.tapZonesSeen) {
                flashOverlay(map)
                updatePreferences { it.copy(tapZonesSeen = true) }
            }
        } else if (settingsOpen) {
            zonesChangedInSettings = true
        } else {
            flashOverlay(map)
        }
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
            val title =
                state.value.sections
                    .getOrNull(index)
                    ?.title
                    .orEmpty()
            mutableState.update { if (it.currentIndex == index) it.copy(sectionDocument = BookStyle.document(title, html)) else it }
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
                            error = if (result.error == AppError.Network && !nothing) null else result.error.userMessage(),
                        )
                    }
                }
            }
        }
    }

    private fun moveTo(index: Int) {
        val current = state.value
        val count = if (current.isBook) current.sections.size else current.pages.size
        if (index == current.currentIndex || index !in 0 until count) return
        mutableState.update {
            it.copy(
                currentIndex = index,
                currentItem = if (it.items.isEmpty()) it.currentItem else index + 1,
                sectionDocument = if (it.isBook) null else it.sectionDocument,
            )
        }
        if (current.isBook) loadSection(index)
        saveJob?.cancel()
        saveJob =
            viewModelScope.launch {
                delay(SAVE_DELAY_MS)
                progress.save(sourceKey, titleId, chapterId, index)
            }
    }

    override fun onCleared() {
        // When leaving the reader, the last page seen is saved without waiting for the pause to end.
        if (saveJob?.isActive == true) {
            saveJob?.cancel()
            progress.save(sourceKey, titleId, chapterId, state.value.currentIndex)
        }
        super.onCleared()
    }

    companion object {
        /** `startPage` that opens a chapter at its last page (going back from the next chapter). */
        const val START_AT_END = Int.MAX_VALUE

        private const val SAVE_DELAY_MS = 800L
        const val OVERLAY_MS = 1500L
    }
}
