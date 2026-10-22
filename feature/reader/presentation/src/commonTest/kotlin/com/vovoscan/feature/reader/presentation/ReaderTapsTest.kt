package com.vovoscan.feature.reader.presentation

import com.vovoscan.core.common.AppResult
import com.vovoscan.feature.reader.domain.Book
import com.vovoscan.feature.reader.domain.ChapterHeading
import com.vovoscan.feature.reader.domain.ChapterHeadings
import com.vovoscan.feature.reader.domain.CoachKind
import com.vovoscan.feature.reader.domain.ComicFormat
import com.vovoscan.feature.reader.domain.ReaderChapter
import com.vovoscan.feature.reader.domain.ReaderPage
import com.vovoscan.feature.reader.domain.ReaderPreferences
import com.vovoscan.feature.reader.domain.ReaderRepository
import com.vovoscan.feature.reader.domain.ReadingMode
import com.vovoscan.feature.reader.domain.ScrollAmount
import com.vovoscan.feature.reader.domain.TapAction
import com.vovoscan.feature.reader.domain.TapLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderTapsTest {
    private val dispatcher = StandardTestDispatcher()

    private val manga =
        ReaderChapter(
            sourceKey = "vovoscan",
            id = "c3",
            titleId = "t1",
            titleName = "Paper Tide",
            number = 3.0,
            name = null,
            mode = ReadingMode.PagedRightToLeft,
            previousChapterId = "c2",
            nextChapterId = "c4",
            pages = (0 until 5).map { ReaderPage(it, "https://api.test/p$it", 1000, 1500) },
            documentUrl = null,
            format = ComicFormat.Manga,
        )
    private val webtoon = manga.copy(mode = ReadingMode.LongStrip, format = ComicFormat.Webtoon)

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

    private val headings =
        ChapterHeadings { _, _, id ->
            when (id) {
                "c4" -> ChapterHeading(4.0, "The last gate")
                "c2" -> ChapterHeading(2.0, null)
                else -> null
            }
        }

    // Overlay already seen: the tests that are not about it start without it. Manga read as right-to-left pages (the
    // default is now the continuous strip, `ImmersiveReaderTest`).
    // The first-open coach already closed for every kind (`ReaderCoachTest`).
    private val paged = ReaderPreferences(mangaMode = ReadingMode.PagedRightToLeft, coachSeen = CoachKind.entries.toSet())
    private val seen = paged.copy(tapZonesSeen = true)

    // Vertical pages, where a tall page scrolls inside its page (`TallPages`).
    private val vertical = seen.copy(mangaMode = ReadingMode.PagedVertical)

    // Webtoons read as a long strip (the user's default for the format).
    private val strips = seen.copy(webtoonMode = ReadingMode.LongStrip)

    // Manga read right to left (the user's default for the format; the format rule is vertical pages).
    private val rtl = seen.copy(mangaMode = ReadingMode.PagedRightToLeft)

    private fun TestScope.reader(
        chapter: ReaderChapter = manga,
        startPage: Int = 0,
        settings: FakeSettings = FakeSettings(seen),
    ): ReaderViewModel {
        val vm = ReaderViewModel("vovoscan", "t1", chapter.id, startPage, StaticReader(chapter), settings, headings) { _, _, _, _ -> }
        advanceUntilIdle()
        return vm
    }

    private val ReaderViewModel.command get() = state.value.command

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun itemsAreTheTwoCardsAroundThePages() =
        runTest(dispatcher) {
            val vm = reader(startPage = 2)
            val items = vm.state.value.items
            assertEquals(7, items.size)
            assertEquals(3, vm.state.value.currentItem)
            val previous = assertIs<ReaderItem.Transition>(items.first()).card
            val next = assertIs<ReaderItem.Transition>(items.last()).card
            assertEquals("Current: Chapter 3", previous.heading)
            assertEquals("Previous: Chapter 2", previous.target)
            assertEquals("← Previous chapter", previous.actionLabel)
            assertEquals("Finished: Chapter 3", next.heading)
            assertEquals("Next: Chapter 4 · The last gate", next.target)
            assertEquals("Next chapter →", next.actionLabel)
            assertEquals(1, assertIs<ReaderItem.Page>(items[2]).page.index)
        }

    @Test
    fun lastChapterForNow() =
        runTest(dispatcher) {
            val vm = reader(manga.copy(nextChapterId = null, previousChapterId = null))
            val next =
                assertIs<ReaderItem.Transition>(
                    vm.state.value.items
                        .last(),
                ).card
            assertEquals("This is the last chapter for now.", next.target)
            assertNull(next.actionLabel)
            assertEquals(
                "This is the first chapter.",
                assertIs<ReaderItem.Transition>(
                    vm.state.value.items
                        .first(),
                ).card.target,
            )
        }

    @Test
    fun rightToLeftPagesTurnOnASingleTap() =
        runTest(dispatcher) {
            val vm = reader(settings = FakeSettings(rtl))
            assertEquals(ReadingMode.PagedRightToLeft, vm.state.value.mode)
            assertTrue(vm.state.value.chromeVisible)
            // Right to left: the left side goes forward.
            vm.onIntent(ReaderIntent.Tap(0.1f, 0.5f))
            assertEquals(ReaderCommand.GoToItem(1, item = 2, animated = true), vm.command)
            assertEquals(1, vm.state.value.currentIndex)
            assertFalse(vm.state.value.chromeVisible)
            vm.onIntent(ReaderIntent.Tap(0.1f, 0.5f))
            assertEquals(3, (vm.command as ReaderCommand.GoToItem).item)
            vm.onIntent(ReaderIntent.Tap(0.9f, 0.5f))
            assertEquals(2, (vm.command as ReaderCommand.GoToItem).item)
            assertEquals(1, vm.state.value.currentIndex)
            // The middle: the bars.
            vm.onIntent(ReaderIntent.Tap(0.5f, 0.5f))
            assertTrue(vm.state.value.chromeVisible)
        }

    @Test
    fun leftToRightAndInvert() =
        runTest(dispatcher) {
            val settings = FakeSettings(seen.copy(mangaMode = ReadingMode.PagedLeftToRight))
            val vm = reader(settings = settings)
            assertEquals(ReadingMode.PagedLeftToRight, vm.state.value.mode)
            vm.onIntent(ReaderIntent.Tap(0.9f, 0.5f))
            assertEquals(2, (vm.command as ReaderCommand.GoToItem).item)
            vm.onIntent(ReaderIntent.SetInvert(com.vovoscan.feature.reader.domain.TapInvert.Horizontal))
            advanceUntilIdle()
            vm.onIntent(ReaderIntent.Tap(0.9f, 0.5f))
            assertEquals(1, (vm.command as ReaderCommand.GoToItem).item)
        }

    @Test
    fun pastTheLastPageTheTransitionThenTheNextChapter() =
        runTest(dispatcher) {
            val vm = reader(startPage = 4, settings = FakeSettings(rtl))
            vm.onIntent(ReaderIntent.Tap(0.1f, 0.5f))
            assertEquals(6, (vm.command as ReaderCommand.GoToItem).item)
            assertEquals(6, vm.state.value.currentItem)
            assertEquals(4, vm.state.value.currentIndex)
            vm.onIntent(ReaderIntent.Tap(0.1f, 0.5f))
            assertEquals(ReaderCommand.OpenChapter(2, "c4", 0), vm.command)
        }

    @Test
    fun beforeTheFirstPageThePreviousChapterFromItsEnd() =
        runTest(dispatcher) {
            val vm = reader(settings = FakeSettings(rtl))
            vm.onIntent(ReaderIntent.Tap(0.9f, 0.5f))
            assertEquals(0, (vm.command as ReaderCommand.GoToItem).item)
            vm.onIntent(ReaderIntent.Tap(0.9f, 0.5f))
            assertEquals(ReaderCommand.OpenChapter(2, "c2", ReaderViewModel.START_AT_END), vm.command)
        }

    @Test
    fun swipesReportTheItemOnScreen() =
        runTest(dispatcher) {
            val vm = reader()
            vm.onIntent(ReaderIntent.ItemVisible(4))
            assertEquals(3, vm.state.value.currentIndex)
            vm.onIntent(ReaderIntent.ItemVisible(6))
            assertEquals(6, vm.state.value.currentItem)
            assertEquals(3, vm.state.value.currentIndex)
            vm.onIntent(ReaderIntent.ItemVisible(99))
            assertEquals(6, vm.state.value.currentItem)
        }

    @Test
    fun longStripScrollsByAFractionOfTheScreen() =
        runTest(dispatcher) {
            val vm = reader(webtoon, settings = FakeSettings(strips))
            assertEquals(ReadingMode.LongStrip, vm.state.value.mode)
            assertTrue(vm.state.value.isStrip)
            // L-shaped by default: the bottom row goes forward, the top row back.
            vm.onIntent(ReaderIntent.Tap(0.5f, 0.9f))
            assertEquals(ReaderCommand.ScrollBy(1, 0.75f), vm.command)
            vm.onIntent(ReaderIntent.Tap(0.5f, 0.1f))
            assertEquals(ReaderCommand.ScrollBy(2, -0.75f), vm.command)
            vm.onIntent(ReaderIntent.SetScrollFraction(ScrollAmount.Half))
            advanceUntilIdle()
            vm.onIntent(ReaderIntent.Tap(0.9f, 0.5f))
            assertEquals(ReaderCommand.ScrollBy(3, 0.5f), vm.command)
            // At the bottom, Next opens the next chapter; at the top, Previous opens the previous one.
            vm.onIntent(ReaderIntent.StripEdges(atTop = false, atBottom = true))
            vm.onIntent(ReaderIntent.Tap(0.5f, 0.9f))
            assertEquals(ReaderCommand.OpenChapter(4, "c4", 0), vm.command)
            vm.onIntent(ReaderIntent.StripEdges(atTop = true, atBottom = false))
            vm.onIntent(ReaderIntent.Tap(0.5f, 0.1f))
            assertEquals(ReaderCommand.OpenChapter(5, "c2", ReaderViewModel.START_AT_END), vm.command)
            vm.onIntent(ReaderIntent.CommandHandled(5))
            assertNull(vm.command)
        }

    @Test
    fun noNextChapterNothingHappensAtTheEnd() =
        runTest(dispatcher) {
            val vm = reader(webtoon.copy(nextChapterId = null), settings = FakeSettings(strips))
            vm.onIntent(ReaderIntent.StripEdges(atTop = false, atBottom = true))
            vm.onIntent(ReaderIntent.Tap(0.5f, 0.9f))
            assertNull(vm.command)
        }

    @Test
    fun modeForTheSeriesKeepsThePage() =
        runTest(dispatcher) {
            val settings = FakeSettings(seen)
            val vm = reader(webtoon, startPage = 3, settings = settings)
            // A webtoon reads continuously by default (reader/SPEC.md §1b).
            assertEquals(ReadingMode.LongStrip, vm.state.value.mode)
            assertEquals(4, vm.state.value.currentItem)
            assertEquals(0, vm.state.value.pageGap)
            // A webtoon only scrolls (reader/SPEC.md §1b): its sheet offers the strip modes, a paged choice is ignored.
            assertEquals(
                listOf(ReadingMode.LongStrip, ReadingMode.LongStripGaps),
                vm.state.value.settings.seriesModes
                    .map { it.mode },
            )
            vm.onIntent(ReaderIntent.SetMode(ReadingMode.PagedVertical))
            advanceUntilIdle()
            assertEquals(ReadingMode.LongStrip, vm.state.value.mode)
            vm.onIntent(ReaderIntent.SetMode(ReadingMode.LongStripGaps))
            advanceUntilIdle()
            assertEquals(ReadingMode.LongStripGaps, vm.state.value.mode)
            assertEquals(ReadingMode.LongStripGaps, settings.series.value["vovoscan/t1"])
            assertEquals(3, vm.state.value.currentIndex)
            assertEquals(4, vm.state.value.currentItem)
            assertTrue(vm.state.value.settings.canResetSeries)
            assertTrue(
                vm.state.value.settings.seriesModes
                    .single { it.selected }
                    .mode == ReadingMode.LongStripGaps,
            )
            assertEquals("Default for this series: Continuous.", vm.state.value.settings.seriesHint)
            // The format default applies once the series goes back to it.
            vm.onIntent(ReaderIntent.SetDefaultMode(ComicFormat.Webtoon, ReadingMode.LongStripGaps))
            vm.onIntent(ReaderIntent.ResetSeriesMode)
            advanceUntilIdle()
            assertEquals(ReadingMode.LongStripGaps, vm.state.value.mode)
            assertEquals(16, vm.state.value.pageGap)
            assertFalse(vm.state.value.settings.canResetSeries)
            assertEquals(
                ReadingMode.LongStripGaps,
                vm.state.value.settings.webtoonModes
                    .single { it.selected }
                    .mode,
            )
            assertEquals(3, vm.state.value.currentIndex)
        }

    @Test
    fun overlayOnTheFirstOpeningThenAfterAChange() =
        runTest(dispatcher) {
            val settings = FakeSettings(paged)
            val vm = ReaderViewModel("vovoscan", "t1", "c3", 0, StaticReader(manga), settings, headings) { _, _, _, _ -> }
            runCurrent()
            val overlay = assertNotNull(vm.state.value.tapOverlay)
            // Right to left, right and left: Next on the left.
            assertEquals(TapAction.Next, overlay.rects.first { it.left == 0f }.action)
            assertEquals(setOf("Previous", "Next", "Menu"), overlay.labels.map { it.text }.toSet())
            assertTrue(settings.preferences.value.tapZonesSeen)
            advanceTimeBy(ReaderViewModel.OVERLAY_MS + 1)
            assertNull(vm.state.value.tapOverlay)

            vm.onIntent(ReaderIntent.SetTapLayout(TapLayout.Kindle))
            runCurrent()
            assertEquals(
                TapAction.Menu,
                vm.state.value.tapOverlay!!
                    .rects
                    .first()
                    .action,
            )
            advanceUntilIdle()
            assertNull(vm.state.value.tapOverlay)

            // Opened again: already seen.
            val again = ReaderViewModel("vovoscan", "t1", "c3", 0, StaticReader(manga), settings, headings) { _, _, _, _ -> }
            runCurrent()
            assertNull(again.state.value.tapOverlay)
        }

    @Test
    fun overlayWaitsForTheSettingsSheetToClose() =
        runTest(dispatcher) {
            val vm = reader()
            vm.onIntent(ReaderIntent.SettingsOpened)
            vm.onIntent(ReaderIntent.SetTapLayout(TapLayout.Edge))
            vm.onIntent(ReaderIntent.SetMode(ReadingMode.LongStrip))
            runCurrent()
            assertNull(vm.state.value.tapOverlay)
            vm.onIntent(ReaderIntent.SettingsClosed)
            runCurrent()
            // The zones in use now: Edge in a long strip (middle bottom goes back).
            assertEquals(
                TapAction.Previous,
                vm.state.value.tapOverlay!!
                    .rects
                    .single { it.top > 0.6f && it.left > 0.3f && it.right < 0.7f }
                    .action,
            )
            // Closed without a change of zones: nothing.
            advanceUntilIdle()
            vm.onIntent(ReaderIntent.SettingsOpened)
            vm.onIntent(ReaderIntent.SetKeepAwake(false))
            vm.onIntent(ReaderIntent.SettingsClosed)
            runCurrent()
            assertNull(vm.state.value.tapOverlay)
        }

    @Test
    fun overlayTurnedOff() =
        runTest(dispatcher) {
            val vm = reader(settings = FakeSettings(paged.copy(showTapZones = false)))
            assertNull(vm.state.value.tapOverlay)
            vm.onIntent(ReaderIntent.SetTapLayout(TapLayout.Edge))
            runCurrent()
            assertNull(vm.state.value.tapOverlay)
            vm.onIntent(ReaderIntent.SetShowTapZones(true))
            runCurrent()
            assertNotNull(vm.state.value.tapOverlay)
        }

    @Test
    fun volumeKeys() =
        runTest(dispatcher) {
            val settings = FakeSettings(seen)
            val vm = reader(settings = settings)
            assertFalse(vm.onVolumeKey(up = false))
            assertNull(vm.command)
            vm.onIntent(ReaderIntent.SetVolumeKeys(true))
            advanceUntilIdle()
            assertTrue(vm.onVolumeKey(up = false))
            assertEquals(2, (vm.command as ReaderCommand.GoToItem).item)
            assertTrue(vm.onVolumeKey(up = true))
            assertEquals(1, (vm.command as ReaderCommand.GoToItem).item)
            vm.onIntent(ReaderIntent.SetVolumeKeysInverted(true))
            advanceUntilIdle()
            vm.onVolumeKey(up = true)
            assertEquals(2, (vm.command as ReaderCommand.GoToItem).item)
        }

    @Test
    fun aSeriesModeSetBeforeIsKept() =
        runTest(dispatcher) {
            val settings = FakeSettings(seen)
            settings.setSeriesMode("vovoscan", "t1", ReadingMode.LongStrip)
            assertEquals(ReadingMode.LongStrip, reader(webtoon, settings = settings).state.value.mode)
            settings.setSeriesMode("vovoscan", "t1", null)
            settings.updatePreferences { it.copy(webtoonMode = ReadingMode.LongStripGaps) }
            assertEquals(ReadingMode.LongStripGaps, reader(webtoon, settings = settings).state.value.mode)
            // Manga: continuous by the format rule (reader/SPEC.md §1b), right to left once chosen as the manga default.
            settings.updatePreferences { it.copy(mangaMode = null) }
            assertEquals(ReadingMode.LongStrip, reader(manga, settings = settings).state.value.mode)
            settings.updatePreferences { it.copy(mangaMode = ReadingMode.PagedRightToLeft) }
            assertEquals(ReadingMode.PagedRightToLeft, reader(manga, settings = settings).state.value.mode)
        }

    @Test
    fun aTallPageScrollsInsideThenTurns() =
        runTest(dispatcher) {
            val vm = reader(startPage = 1, settings = FakeSettings(vertical))
            assertEquals(ReadingMode.PagedVertical, vm.state.value.mode)
            assertEquals(2, vm.state.value.currentItem)
            val next = 0.5f to 0.9f
            val previous = 0.5f to 0.1f
            // A tall page at its top: Next scrolls inside it.
            vm.onIntent(ReaderIntent.PageScroll(item = 2, tall = true, atTop = true, atBottom = false))
            vm.onIntent(ReaderIntent.Tap(next.first, next.second))
            assertEquals(ReaderCommand.ScrollBy(1, 0.75f), vm.command)
            assertEquals(2, vm.state.value.currentItem)
            // At the top, Previous goes to the previous page, at its bottom.
            vm.onIntent(ReaderIntent.Tap(previous.first, previous.second))
            assertEquals(ReaderCommand.GoToItem(2, item = 1, animated = true, atBottom = true), vm.command)
            assertEquals(1, vm.state.value.currentItem)
            // The page we left reported long ago: ignored for the new one until it reports.
            vm.onIntent(ReaderIntent.Tap(next.first, next.second))
            assertEquals(ReaderCommand.GoToItem(3, item = 2, animated = true), vm.command)
            vm.onIntent(ReaderIntent.PageScroll(item = 2, tall = true, atTop = false, atBottom = false))
            vm.onIntent(ReaderIntent.Tap(previous.first, previous.second))
            assertEquals(ReaderCommand.ScrollBy(4, -0.75f), vm.command)
            vm.onIntent(ReaderIntent.Tap(next.first, next.second))
            assertEquals(ReaderCommand.ScrollBy(5, 0.75f), vm.command)
            // At its bottom, Next turns; the tap scroll amount is the strips' setting.
            vm.onIntent(ReaderIntent.PageScroll(item = 2, tall = true, atTop = false, atBottom = true))
            vm.onIntent(ReaderIntent.Tap(next.first, next.second))
            assertEquals(ReaderCommand.GoToItem(6, item = 3, animated = true), vm.command)
            vm.onIntent(ReaderIntent.SetScrollFraction(ScrollAmount.Most))
            advanceUntilIdle()
            vm.onIntent(ReaderIntent.PageScroll(item = 3, tall = true, atTop = true, atBottom = false))
            vm.onIntent(ReaderIntent.Tap(next.first, next.second))
            assertEquals(ReaderCommand.ScrollBy(7, 0.9f), vm.command)
            // A report for another item than the current one changes nothing.
            vm.onIntent(ReaderIntent.PageScroll(item = 4, tall = true, atTop = true, atBottom = false))
            vm.onIntent(ReaderIntent.Tap(previous.first, previous.second))
            assertEquals(ReaderCommand.GoToItem(8, item = 2, animated = true, atBottom = true), vm.command)
        }

    @Test
    fun shortPagesTurnAtOnce() =
        runTest(dispatcher) {
            val vm = reader(startPage = 1, settings = FakeSettings(vertical))
            vm.onIntent(ReaderIntent.PageScroll(item = 2, tall = false, atTop = true, atBottom = true))
            vm.onIntent(ReaderIntent.Tap(0.5f, 0.9f))
            assertEquals(3, (vm.command as ReaderCommand.GoToItem).item)
        }

    @Test
    fun aTallPageInHorizontalPagesAndTheVolumeKeys() =
        runTest(dispatcher) {
            val vm = reader(startPage = 1, settings = FakeSettings(rtl.copy(volumeKeys = true)))
            assertTrue(vm.state.value.pagedHorizontally)
            vm.onIntent(ReaderIntent.PageScroll(item = 2, tall = true, atTop = true, atBottom = false))
            // Right to left: the left side goes forward, and scrolls the tall page first.
            vm.onIntent(ReaderIntent.Tap(0.1f, 0.5f))
            assertEquals(ReaderCommand.ScrollBy(1, 0.75f), vm.command)
            assertTrue(vm.onVolumeKey(up = false))
            assertEquals(ReaderCommand.ScrollBy(2, 0.75f), vm.command)
            assertTrue(vm.onVolumeKey(up = true))
            assertEquals(ReaderCommand.GoToItem(3, item = 1, animated = true, atBottom = true), vm.command)
        }

    @Test
    fun aTallLastPageThenTheChapterCard() =
        runTest(dispatcher) {
            val vm = reader(startPage = 4, settings = FakeSettings(vertical))
            vm.onIntent(ReaderIntent.PageScroll(item = 5, tall = true, atTop = false, atBottom = true))
            vm.onIntent(ReaderIntent.Tap(0.5f, 0.9f))
            assertEquals(6, (vm.command as ReaderCommand.GoToItem).item)
            vm.onIntent(ReaderIntent.Tap(0.5f, 0.9f))
            assertEquals(ReaderCommand.OpenChapter(2, "c4", 0), vm.command)
        }

    @Test
    fun settingsSheetAndDefaults() =
        runTest(dispatcher) {
            val vm = reader()
            val sheet = vm.state.value.settings
            assertEquals(
                listOf("Right to left", "Left to right", "Vertical pages", "Continuous", "Continuous with gaps"),
                sheet.seriesModes.map { it.label },
            )
            assertEquals(TapLayout.Default, sheet.layouts.single { it.selected }.layout)
            assertEquals("75 %", sheet.scrollAmounts.single { it.selected }.label)
            assertEquals(listOf("50 %", "75 %", "90 %"), sheet.scrollAmounts.map { it.label })
            assertTrue(sheet.showTapZones)
            assertFalse(sheet.doubleTapZoom)
            assertTrue(sheet.keepAwake)
            assertFalse(sheet.volumeKeys)
            assertTrue(vm.state.value.keepScreenOn)
            vm.onIntent(ReaderIntent.SetDoubleTapZoom(true))
            vm.onIntent(ReaderIntent.SetKeepAwake(false))
            advanceUntilIdle()
            assertTrue(vm.state.value.doubleTapZoom)
            assertFalse(vm.state.value.keepScreenOn)
        }
}
