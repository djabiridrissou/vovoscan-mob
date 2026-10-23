package com.vovoscan.feature.reader.presentation

import com.vovoscan.core.common.AppResult
import com.vovoscan.feature.reader.domain.Book
import com.vovoscan.feature.reader.domain.ChapterHeadings
import com.vovoscan.feature.reader.domain.ComicFormat
import com.vovoscan.feature.reader.domain.ReaderChapter
import com.vovoscan.feature.reader.domain.ReaderPage
import com.vovoscan.feature.reader.domain.ReaderPreferences
import com.vovoscan.feature.reader.domain.ReaderRepository
import com.vovoscan.feature.reader.domain.ReadingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The comic reader's zoom (what a tap does while zoomed, when it goes back to 1×) and the page slider. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderZoomSliderTest {
    private val dispatcher = StandardTestDispatcher()

    private val chapter =
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
            pages = (0 until 19).map { ReaderPage(it, "https://api.test/p$it", 1000, 1500) },
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

    private val seen = ReaderPreferences(tapZonesSeen = true)
    private val rtl = seen.copy(mangaMode = ReadingMode.PagedRightToLeft)
    private val strip = seen.copy(mangaMode = ReadingMode.LongStrip)
    private val saved = mutableListOf<Int>()

    private fun TestScope.reader(
        preferences: ReaderPreferences = rtl,
        startPage: Int = 0,
        settings: FakeSettings = FakeSettings(preferences),
    ): ReaderViewModel {
        val vm =
            ReaderViewModel("vovoscan", "t1", chapter.id, startPage, StaticReader(chapter), settings, ChapterHeadings { _, _, _ -> null }) {
                _,
                _,
                _,
                page,
                ->
                saved += page
            }
        advanceUntilIdle()
        return vm
    }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun zoomedTapsOnlyToggleTheBars() =
        runTest(dispatcher) {
            val vm = reader(startPage = 4)
            vm.onIntent(ReaderIntent.ZoomChanged(zoomed = true))
            assertTrue(vm.state.value.zoomed)
            assertTrue(vm.state.value.chromeVisible)
            // The "next" side of right to left: no page turn while zoomed, the bars go.
            vm.onIntent(ReaderIntent.Tap(0.1f, 0.5f))
            assertNull(vm.state.value.command)
            assertEquals(4, vm.state.value.currentIndex)
            assertFalse(vm.state.value.chromeVisible)
            vm.onIntent(ReaderIntent.Tap(0.9f, 0.5f))
            assertTrue(vm.state.value.chromeVisible)
            assertNull(vm.state.value.command)
            // Back at 1×: the zones turn pages again.
            vm.onIntent(ReaderIntent.ZoomChanged(zoomed = false))
            vm.onIntent(ReaderIntent.Tap(0.1f, 0.5f))
            assertEquals(6, assertIs<ReaderCommand.GoToItem>(vm.state.value.command).item)
        }

    @Test
    fun zoomedStripTapsDoNotScroll() =
        runTest(dispatcher) {
            val vm = reader(strip)
            assertTrue(vm.state.value.isStrip)
            vm.onIntent(ReaderIntent.ZoomChanged(zoomed = true))
            vm.onIntent(ReaderIntent.Tap(0.5f, 0.9f))
            assertNull(vm.state.value.command)
            assertFalse(vm.state.value.chromeVisible)
        }

    @Test
    fun aVolumeKeyWhileZoomedTurnsAndResetsTheZoom() =
        runTest(dispatcher) {
            val vm = reader(rtl.copy(volumeKeys = true))
            vm.onIntent(ReaderIntent.ZoomChanged(zoomed = true))
            val reset = vm.state.value.zoomReset
            assertTrue(vm.onVolumeKey(up = false))
            assertEquals(2, assertIs<ReaderCommand.GoToItem>(vm.state.value.command).item)
            assertFalse(vm.state.value.zoomed)
            assertEquals(reset + 1, vm.state.value.zoomReset)
        }

    @Test
    fun anotherModeStartsUnzoomed() =
        runTest(dispatcher) {
            val settings = FakeSettings(rtl)
            val vm = reader(settings = settings)
            vm.onIntent(ReaderIntent.ZoomChanged(zoomed = true))
            val reset = vm.state.value.zoomReset
            vm.onIntent(ReaderIntent.SetMode(ReadingMode.PagedVertical))
            advanceUntilIdle()
            assertEquals(ReadingMode.PagedVertical, vm.state.value.mode)
            assertFalse(vm.state.value.zoomed)
            assertEquals(reset + 1, vm.state.value.zoomReset)
        }

    @Test
    fun sliderJumpsToThePageInPagedModes() =
        runTest(dispatcher) {
            val vm = reader(startPage = 4)
            val state = vm.state.value
            assertTrue(state.showsSlider)
            assertTrue(state.rightToLeft)
            assertEquals("5 / 19", state.positionLabel)
            assertEquals("12 / 19", state.sliderLabel(11))
            vm.onIntent(ReaderIntent.ZoomChanged(zoomed = true))
            vm.onIntent(ReaderIntent.SeekTo(11))
            assertEquals(ReaderCommand.JumpTo(1, item = 12), vm.state.value.command)
            assertEquals(11, vm.state.value.currentIndex)
            assertEquals(12, vm.state.value.currentItem)
            assertEquals("12 / 19", vm.state.value.positionLabel)
            assertFalse(vm.state.value.zoomed)
            // The bars stay: the reader may move the slider again.
            assertTrue(vm.state.value.chromeVisible)
            advanceUntilIdle()
            assertEquals(listOf(4, 11), saved) // the opening, then the page jumped to
            // Out of range: clamped to the first and last pages.
            vm.onIntent(ReaderIntent.SeekTo(99))
            assertEquals(ReaderCommand.JumpTo(2, item = 19), vm.state.value.command)
            vm.onIntent(ReaderIntent.SeekTo(-3))
            assertEquals(ReaderCommand.JumpTo(3, item = 1), vm.state.value.command)
            assertEquals("1 / 19", vm.state.value.positionLabel)
        }

    @Test
    fun sliderJumpsInAStripAndLaterReportsFollowFromThere() =
        runTest(dispatcher) {
            val vm = reader(strip, startPage = 2)
            assertFalse(vm.state.value.rightToLeft)
            vm.onIntent(ReaderIntent.ItemVisible(3))
            vm.onIntent(ReaderIntent.SeekTo(14))
            assertEquals(ReaderCommand.JumpTo(1, item = 15), vm.state.value.command)
            assertEquals(14, vm.state.value.currentIndex)
            // The strip's edges change on the way: the page stays the one jumped to, not the one reported before.
            vm.onIntent(ReaderIntent.StripEdges(atTop = false, atBottom = false))
            assertEquals(14, vm.state.value.currentIndex)
            vm.onIntent(ReaderIntent.ItemVisible(16))
            assertEquals(15, vm.state.value.currentIndex)
        }

    @Test
    fun noSliderForASinglePage() =
        runTest(dispatcher) {
            val single = chapter.copy(pages = chapter.pages.take(1))
            val vm =
                ReaderViewModel(
                    "vovoscan",
                    "t1",
                    single.id,
                    0,
                    StaticReader(single),
                    FakeSettings(rtl),
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
            advanceUntilIdle()
            assertFalse(vm.state.value.showsSlider)
        }
}
