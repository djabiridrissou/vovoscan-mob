package com.vovoscan.feature.reader.presentation

import com.vovoscan.core.common.AppResult
import com.vovoscan.feature.reader.domain.Book
import com.vovoscan.feature.reader.domain.ChapterHeadings
import com.vovoscan.feature.reader.domain.ChapterPanels
import com.vovoscan.feature.reader.domain.ComicFormat
import com.vovoscan.feature.reader.domain.Gutters
import com.vovoscan.feature.reader.domain.PanelBox
import com.vovoscan.feature.reader.domain.ReaderChapter
import com.vovoscan.feature.reader.domain.ReaderPage
import com.vovoscan.feature.reader.domain.ReaderPreferences
import com.vovoscan.feature.reader.domain.ReaderRepository
import com.vovoscan.feature.reader.domain.ReaderSettingsRepository
import com.vovoscan.feature.reader.domain.ReadingMode
import com.vovoscan.feature.reader.domain.ReadingPosition
import com.vovoscan.feature.reader.domain.Resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The continuous reader (reader/SPEC.md): stops, the end card, bars, focus, position, handoff. */
@OptIn(ExperimentalCoroutinesApi::class)
class ImmersiveReaderTest {
    private val dispatcher = StandardTestDispatcher()

    // A manga: read as one continuous strip by default. Five pages 1000 x 1500, so 600 tall at a 400 wide strip.
    private val manga =
        ReaderChapter(
            sourceKey = "vovoscan",
            id = "c3",
            titleId = "t1",
            titleName = "Paper Tide",
            number = 3.0,
            name = null,
            mode = ReadingMode.LongStrip,
            previousChapterId = "c2",
            nextChapterId = "c4",
            pages = (0 until 5).map { ReaderPage(it, "https://api.test/p$it", 1000, 1500) },
            documentUrl = null,
            format = ComicFormat.Manga,
        )

    private class StaticReader(
        private val chapter: ReaderChapter,
    ) : ReaderRepository {
        override fun observe(
            sourceKey: String,
            chapterId: String,
        ): Flow<ReaderChapter?> = flowOf(chapter)

        override suspend fun refresh(
            sourceKey: String,
            titleId: String,
            chapterId: String,
        ) = AppResult.Ok(Unit)

        override fun observeBook(
            sourceKey: String,
            chapterId: String,
        ): Flow<Book?> = flowOf(null)

        override suspend fun loadBook(
            sourceKey: String,
            chapterId: String,
        ) = AppResult.Ok(Unit)

        override suspend fun sectionHtml(
            sourceKey: String,
            chapterId: String,
            index: Int,
        ) = null

        override suspend fun clearLocal() = Unit

        override suspend fun clearSource(sourceKey: String) = Unit
    }

    private class PositionSettings(
        initial: ReaderPreferences,
    ) : ReaderSettingsRepository by FakeSettings(initial) {
        val positions = mutableMapOf<String, ReadingPosition>()

        override suspend fun position(
            sourceKey: String,
            chapterId: String,
        ) = positions["$sourceKey/$chapterId"]

        override suspend fun savePosition(
            sourceKey: String,
            chapterId: String,
            position: ReadingPosition,
        ) {
            positions["$sourceKey/$chapterId"] = position
        }
    }

    private fun TestScope.reader(
        settings: PositionSettings =
            PositionSettings(
                ReaderPreferences(
                    coachSeen = ALL_COACHES,
                    tapZonesSeen = true,
                    mangaMode = CONTINUOUS,
                ),
            ),
        panels: ChapterPanels = ChapterPanels.None,
        sourceKey: String = "vovoscan",
        startPage: Int = 0,
        chapter: ReaderChapter = manga,
    ): ReaderViewModel {
        val vm =
            ReaderViewModel(
                sourceKey,
                "t1",
                manga.id,
                startPage,
                StaticReader(chapter),
                settings,
                ChapterHeadings {
                    _,
                    _,
                    _,
                    ->
                    null
                },
                panels,
            ) {
                _,
                _,
                _,
                _,
                ->
            }
        advanceUntilIdle()
        vm.onIntent(ReaderIntent.StripMeasured(width = 400f, viewport = 800f))
        return vm
    }

    private fun ReaderViewModel.glide(): ReaderCommand.GlideTo = assertIs(state.value.command)

    // Stops of the five pages (each fits a screen: centred): 0, 500, 1100, 1700, 2200 (the end).
    private fun ReaderViewModel.next() = onIntent(ReaderIntent.Next)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun aMangaReadContinuouslyIsOneStrip() =
        runTest(dispatcher) {
            val vm = reader()
            assertTrue(vm.state.value.isStrip)
            assertTrue(vm.state.value.canFocus)
        }

    @Test
    fun aMangaOpensInPagesRightToLeftUntilTheReaderChoosesOtherwise() =
        runTest(dispatcher) {
            val vm = reader(settings = PositionSettings(ReaderPreferences(coachSeen = ALL_COACHES, tapZonesSeen = true)))
            assertEquals(ReadingMode.PagedRightToLeft, vm.state.value.mode)
            assertFalse(vm.state.value.isStrip)
        }

    @Test
    fun aStripIsRecognisedFromItsImagesAndNeverReadAsPages() =
        runTest(dispatcher) {
            // The source calls it a manga and the reader chose paged manga: until the images arrive, that is the mode.
            val unsized = manga.copy(sourceKey = "inkwell-press", pages = manga.pages.map { it.copy(width = 0, height = 0) })
            val settings =
                PositionSettings(ReaderPreferences(coachSeen = ALL_COACHES, tapZonesSeen = true, mangaMode = ReadingMode.PagedRightToLeft))
            val vm = reader(settings = settings, sourceKey = "inkwell-press", chapter = unsized)
            assertEquals(ReadingMode.PagedRightToLeft, vm.state.value.mode)
            assertEquals("Manga ✦ right to left", vm.state.value.formatLabel)
            // The first image is an 800 x 14 000 strip: a Korean webtoon, read top to bottom at full width.
            vm.onIntent(ReaderIntent.PageSized(0, 800, 14_000))
            assertEquals(ReadingMode.LongStrip, vm.state.value.mode)
            assertEquals(ComicFormat.Webtoon, vm.state.value.format)
            assertEquals("Webtoon ✦ top to bottom", vm.state.value.formatLabel)
        }

    @Test
    fun pagesOfUnknownSizeTakeTheirImagesShape() =
        runTest(dispatcher) {
            // A source read on the device gives no sizes: placeholders (0.7) until each image has loaded.
            val strip = manga.copy(sourceKey = "inkwell-press", pages = manga.pages.map { it.copy(width = 0, height = 0) })
            val vm = reader(sourceKey = "inkwell-press", chapter = strip)
            // Page 0 is a 400 x 1600 strip: taller than the screen, read in two steps (from -24 clamped to 0, then 616).
            vm.onIntent(ReaderIntent.PageSized(0, 400, 1600))
            assertTrue(
                vm.state.value.pages[0]
                    .hasSize,
            )
            assertEquals(
                1600f / 400f,
                vm.state.value.pages[0]
                    .height
                    .toFloat() /
                    vm.state.value.pages[0]
                        .width,
            )
            vm.next()
            assertEquals(616f, vm.glide().offset)
            // Our own pages keep the size the API gave.
            val own = reader()
            own.onIntent(ReaderIntent.PageSized(0, 400, 1600))
            assertEquals(
                1500,
                own.state.value.pages[0]
                    .height,
            )
        }

    @Test
    fun aPageKeepsItsMeasuredSizeOnScreenOnceItsImageIsLetGo() =
        runTest(dispatcher) {
            // The native strip draws the items: once an image far from the one read is let go, the page must keep its
            // measured height there too, or the pages above shrink on screen while the stops still count them tall
            // (one Next then glided past dozens of pages).
            val strip = manga.copy(sourceKey = "inkwell-press", pages = manga.pages.map { it.copy(width = 0, height = 0) })
            val vm = reader(sourceKey = "inkwell-press", chapter = strip)
            vm.onIntent(ReaderIntent.PageSized(0, 400, 1600))
            vm.onIntent(ReaderIntent.PageSized(1, 400, 1600))
            val drawn = vm.state.value.items.filterIsInstance<ReaderItem.Page>().map { it.page }
            assertEquals(vm.state.value.pages, drawn)
            assertEquals(1600, drawn[1].height)
            assertFalse(drawn[2].hasSize)
        }

    @Test
    fun aTapGlidesToTheNextStopAndTapsAddUp() =
        runTest(dispatcher) {
            val vm = reader()
            vm.next()
            assertEquals(500f, vm.glide().offset)
            assertTrue(vm.glide().animated)
            // A second tap while moving goes to the stop after the target.
            vm.onIntent(ReaderIntent.StripScrolled(240f, byUser = false, settled = false))
            vm.next()
            assertEquals(1100f, vm.glide().offset)
            vm.onIntent(ReaderIntent.StripScrolled(1100f, byUser = false, settled = true))
            vm.onIntent(ReaderIntent.Previous)
            assertEquals(500f, vm.glide().offset)
        }

    @Test
    fun afterTheLastStopTheEndCardThenTheNextChapter() =
        runTest(dispatcher) {
            val vm = reader()
            repeat(4) { vm.next() }
            assertEquals(2200f, vm.glide().offset)
            vm.onIntent(ReaderIntent.StripScrolled(2200f, byUser = false, settled = true))
            vm.next()
            val card = assertIs<ReaderCommand.GoToItem>(vm.state.value.command)
            assertEquals(vm.state.value.items.lastIndex, card.item)
            assertTrue(vm.state.value.chromeVisible)
            vm.next()
            assertEquals("c4", assertIs<ReaderCommand.OpenChapter>(vm.state.value.command).chapterId)
        }

    @Test
    fun aDragMovesTheCurrentStopWhereTheReaderStopped() =
        runTest(dispatcher) {
            val vm = reader()
            vm.onIntent(ReaderIntent.StripScrolled(1200f, byUser = true, settled = false))
            vm.onIntent(ReaderIntent.StripScrolled(1200f, byUser = true, settled = true))
            vm.next()
            assertEquals(1700f, vm.glide().offset)
            assertEquals(2, vm.state.value.currentIndex)
        }

    @Test
    fun reducedMotionJumps() =
        runTest(dispatcher) {
            val vm = reader()
            vm.onIntent(ReaderIntent.ReducedMotion(true))
            vm.next()
            assertFalse(vm.glide().animated)
            assertEquals(0, vm.state.value.focusMs)
        }

    @Test
    fun panelsFromTheAnalysisMakeTheStopsAndTheFocusFollowsThem() =
        runTest(dispatcher) {
            // Page 1: two panels side by side (right first: a manga), then one below.
            val row = listOf(PanelBox(1, 0.5f, 0f, 0.5f, 0.4f), PanelBox(1, 0f, 0f, 0.5f, 0.4f), PanelBox(1, 0f, 0.5f, 1f, 0.5f))
            val settings =
                PositionSettings(ReaderPreferences(coachSeen = ALL_COACHES, tapZonesSeen = true, focus = true, mangaMode = CONTINUOUS))
            val vm = reader(settings = settings, panels = ChapterPanels { _, _ -> mapOf(1 to row) })
            assertEquals(PanelBox.page(0), vm.state.value.focus)
            vm.next()
            val first = vm.glide().offset
            assertEquals(
                0.5f,
                vm.state.value.focus
                    ?.x,
            )
            vm.onIntent(ReaderIntent.StripScrolled(first, byUser = false, settled = true))
            // The left panel shares the scroll: only the spotlight moves, no new command.
            vm.onIntent(ReaderIntent.CommandHandled(vm.glide().seq))
            vm.next()
            assertNull(vm.state.value.command)
            assertEquals(
                0f,
                vm.state.value.focus
                    ?.x,
            )
            vm.onIntent(ReaderIntent.ToggleFocus)
            advanceUntilIdle()
            assertNull(vm.state.value.focus)
            assertFalse(settings.preferences().focus)
        }

    @Test
    fun withoutAnalysisThePageGuttersMakeTheStops() =
        runTest(dispatcher) {
            val vm = reader()
            val busy = IntArray(Gutters.COLUMNS) { if (it % 2 == 0) 0 else 255 }
            val white = IntArray(Gutters.COLUMNS) { 250 }
            // Page 0 (72 rows at 48 columns): two bands, the top 34 rows and the bottom 34.
            val rows = List(34) { busy } + List(4) { white } + List(34) { busy }
            vm.onIntent(ReaderIntent.PageLuminance(0, rows.flatMap { it.toList() }.toIntArray()))
            // The lower band of page 0 (317..600) is already whole at the first stop: Next only moves the reading
            // place; the one after goes to page 1.
            vm.next()
            assertNull(vm.state.value.command)
            vm.next()
            assertEquals(500f, vm.glide().offset)
        }

    @Test
    fun barsHideAfterAScrollDownAndComeBackOnAScrollUp() =
        runTest(dispatcher) {
            val vm = reader()
            assertTrue(vm.state.value.chromeVisible)
            vm.onIntent(ReaderIntent.StripScrolled(100f, byUser = true, settled = false))
            advanceTimeBy(ReaderViewModel.CHROME_HIDE_MS + 1)
            assertFalse(vm.state.value.chromeVisible)
            vm.onIntent(ReaderIntent.StripScrolled(900f, byUser = true, settled = false))
            vm.onIntent(ReaderIntent.StripScrolled(650f, byUser = true, settled = false))
            assertTrue(vm.state.value.chromeVisible)
        }

    @Test
    fun progressPositionAndPrefetch() =
        runTest(dispatcher) {
            val settings = PositionSettings(ReaderPreferences(coachSeen = ALL_COACHES, tapZonesSeen = true, mangaMode = CONTINUOUS))
            val vm = reader(settings = settings)
            assertEquals(listOf("https://api.test/p1", "https://api.test/p2", "https://api.test/p3"), vm.state.value.prefetch)
            vm.onIntent(ReaderIntent.StripScrolled(1500f, byUser = true, settled = true))
            assertEquals(2300f / 3000f, vm.state.value.progress, 0.001f)
            assertEquals(2, vm.state.value.currentIndex)
            assertEquals(listOf("https://api.test/p3", "https://api.test/p4"), vm.state.value.prefetch)
            advanceTimeBy(600)
            // The reading line, a third of the screen down (1500 + 800 / 3), as a share of page 2 (1200 to 1800).
            val saved = assertNotNull(settings.positions["vovoscan/c3"])
            assertEquals(2, saved.page)
            assertEquals((1500f + 800f / 3f - 1200f) / 600f, saved.offset, 0.001f)
        }

    @Test
    fun aSavedPositionIsRestoredInsideItsPage() =
        runTest(dispatcher) {
            val settings = PositionSettings(ReaderPreferences(coachSeen = ALL_COACHES, tapZonesSeen = true, mangaMode = CONTINUOUS))
            settings.positions["vovoscan/c3"] = ReadingPosition(2, 0.25f)
            val vm = reader(settings = settings, startPage = 2)
            val restore = vm.glide()
            // The reading line in page 2 (stop 1100): one stop back, page 1's.
            assertEquals(500f, restore.offset, 0.01f)
            assertFalse(restore.animated)
            // The panel read there is marked for a moment, then the mark goes.
            assertEquals(
                2,
                vm.state.value.focus
                    ?.page,
            )
            advanceTimeBy(Resume.MARK_MS + 1)
            assertNull(vm.state.value.focus)
        }

    @Test
    fun aResumeNeverSavesItsOwnPlacementBack() =
        runTest(dispatcher) {
            val settings = PositionSettings(ReaderPreferences(coachSeen = ALL_COACHES, tapZonesSeen = true, mangaMode = CONTINUOUS))
            settings.positions["vovoscan/c3"] = ReadingPosition(2, 0.25f)
            val vm = reader(settings = settings, startPage = 2)
            val restore = vm.glide()
            vm.onIntent(ReaderIntent.CommandHandled(restore.seq))
            vm.onIntent(ReaderIntent.StripScrolled(restore.offset, byUser = false, settled = true))
            advanceUntilIdle()
            assertEquals(ReadingPosition(2, 0.25f), settings.positions["vovoscan/c3"])
            // The reader moves on: from then on the position follows.
            vm.onIntent(ReaderIntent.StripScrolled(1700f, byUser = true, settled = true))
            advanceUntilIdle()
            assertNotEquals(ReadingPosition(2, 0.25f), settings.positions["vovoscan/c3"])
        }

    @Test
    fun theTitleCardShowsForAMoment() =
        runTest(dispatcher) {
            val vm =
                ReaderViewModel(
                    "vovoscan",
                    "t1",
                    manga.id,
                    0,
                    StaticReader(manga),
                    FakeSettings(ReaderPreferences(coachSeen = ALL_COACHES, mangaMode = CONTINUOUS)),
                    ChapterHeadings {
                        _,
                        _,
                        _,
                        ->
                        null
                    },
                ) {
                    _,
                    _,
                    _,
                    _,
                    ->
                }
            dispatcher.scheduler.runCurrent()
            assertEquals("Paper Tide ✦ Chapter 3", vm.state.value.titleCard)
            advanceTimeBy(ReaderViewModel.TITLE_MS + 1)
            assertNull(vm.state.value.titleCard)
        }

    @Test
    fun ourComicsHandOverToTheEpisodeAtThePageBeingRead() =
        runTest(dispatcher) {
            val vm = reader()
            assertTrue(vm.state.value.voiceAvailable)
            vm.onIntent(ReaderIntent.StripScrolled(1300f, byUser = true, settled = true))
            vm.onIntent(ReaderIntent.ReadWithVoice)
            val open = assertIs<ReaderCommand.OpenEpisode>(vm.state.value.command)
            assertEquals("c3" to 2, open.chapterId to open.page)
            assertFalse(reader(sourceKey = "gutenberg").state.value.voiceAvailable)
            assertNotNull(vm.state.value.pageAnnouncement)
        }
}

private suspend fun ReaderSettingsRepository.preferences(): ReaderPreferences = observePreferences().first()
