package com.vovoscan.feature.reader.presentation

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.Freshness
import com.vovoscan.feature.reader.domain.Book
import com.vovoscan.feature.reader.domain.BookSection
import com.vovoscan.feature.reader.domain.ChapterHeadings
import com.vovoscan.feature.reader.domain.ReaderChapter
import com.vovoscan.feature.reader.domain.ReaderPage
import com.vovoscan.feature.reader.domain.ReaderPreferences
import com.vovoscan.feature.reader.domain.ReaderRepository
import com.vovoscan.feature.reader.domain.ReadingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private val comic =
        ReaderChapter(
            "vovoscan",
            "c1",
            "t1",
            "Paper Tide",
            1.0,
            null,
            ReadingMode.PagedRightToLeft,
            null,
            "c2",
            (0 until 5).map { ReaderPage(it, "https://api.test/p$it", 1000, 1500) },
            null,
        )
    private val book =
        comic.copy(
            id = "b1",
            titleName = "Letters",
            name = "Full text",
            mode = ReadingMode.Book,
            pages = emptyList(),
            documentUrl = "https://x/b.epub",
        )

    private class FakeReader(
        initial: ReaderChapter?,
        private val refresh: AppResult<Unit>,
    ) : ReaderRepository {
        val stored = MutableStateFlow(initial)
        val bookFlow = MutableStateFlow<Book?>(null)
        var loads = 0

        override fun observe(
            sourceKey: String,
            chapterId: String,
        ): Flow<ReaderChapter?> = stored

        override suspend fun refresh(
            sourceKey: String,
            titleId: String,
            chapterId: String,
        ) = refresh

        override fun observeBook(
            sourceKey: String,
            chapterId: String,
        ): Flow<Book?> = bookFlow

        override suspend fun loadBook(
            sourceKey: String,
            chapterId: String,
        ): AppResult<Unit> {
            loads++
            bookFlow.value = Book(listOf(BookSection(0, "The River Opens"), BookSection(1, "The Rain Returns")))
            return AppResult.Ok(Unit)
        }

        override suspend fun sectionHtml(
            sourceKey: String,
            chapterId: String,
            index: Int,
        ) = "<p>Section $index</p>"

        override suspend fun clearLocal() = Unit

        override suspend fun clearSource(sourceKey: String) = Unit
    }

    private val noHeadings = ChapterHeadings { _, _, _ -> null }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun resumesAtSavedPage() =
        runTest(dispatcher) {
            val vm =
                ReaderViewModel(
                    "vovoscan",
                    "t1",
                    "c1",
                    startPage = 3,
                    FakeReader(comic, AppResult.Ok(Unit)),
                    FakeSettings(ReaderPreferences(coachSeen = ALL_COACHES, mangaMode = ReadingMode.PagedRightToLeft)),
                    noHeadings,
                ) { _, _, _, _ -> }
            advanceUntilIdle()
            assertEquals("4 / 5", vm.state.value.positionLabel)
            assertTrue(vm.state.value.rightToLeft)
            assertEquals("Next chapter →", vm.state.value.nextLabel)
            // The bar's buttons open chapters: right to left, the next one on the left, its arrow pointing left.
            assertEquals(
                listOf("← Next chapter"),
                vm.state.value.chapterButtons
                    .map { it.label },
            )
        }

    @Test
    fun chapterButtonsFollowTheReadingDirection() =
        runTest(dispatcher) {
            val middle = comic.copy(previousChapterId = "c0")
            val rtl =
                ReaderViewModel(
                    "vovoscan",
                    "t1",
                    "c1",
                    0,
                    FakeReader(middle, AppResult.Ok(Unit)),
                    FakeSettings(ReaderPreferences(coachSeen = ALL_COACHES, mangaMode = ReadingMode.PagedRightToLeft)),
                    noHeadings,
                ) { _, _, _, _ -> }
            advanceUntilIdle()
            assertEquals(
                listOf("c2" to "← Next chapter", "c0" to "Previous chapter →"),
                rtl.state.value.chapterButtons.map {
                    it.chapterId to
                        it.label
                },
            )
            val ltr =
                ReaderViewModel(
                    "vovoscan",
                    "t1",
                    "c1",
                    0,
                    FakeReader(middle, AppResult.Ok(Unit)),
                    FakeSettings(ReaderPreferences(coachSeen = ALL_COACHES, mangaMode = ReadingMode.PagedLeftToRight)),
                    noHeadings,
                ) { _, _, _, _ -> }
            advanceUntilIdle()
            assertEquals(
                listOf("← Previous chapter", "Next chapter →"),
                ltr.state.value.chapterButtons
                    .map { it.label },
            )
            assertEquals(
                listOf(false, true),
                ltr.state.value.chapterButtons
                    .map { it.forward },
            )
        }

    /**
     * A chapter first seen without its pages (saved for the first time: the chapter row before its page rows, or a
     * source that sends the list later) keeps the saved page: the resume position is not clamped to an empty list.
     */
    @Test
    fun resumeSurvivesAChapterSeenBeforeItsPages() =
        runTest(dispatcher) {
            val unsized = comic.copy(pages = (0 until 7).map { ReaderPage(it, "https://img.test/$it", 0, 0) })
            val reader = FakeReader(unsized.copy(pages = emptyList()), AppResult.Ok(Unit))
            val saved = mutableListOf<Int>()
            val vm =
                ReaderViewModel("rules", "t1", "c1", startPage = 4, reader, FakeSettings(), noHeadings) { _, _, _, page -> saved += page }
            advanceUntilIdle()
            assertEquals(4, vm.state.value.currentIndex)
            reader.stored.value = unsized
            advanceUntilIdle()
            assertEquals("5 / 7", vm.state.value.positionLabel)
            // Page i is item i + 1 (the previous-chapter card comes first).
            assertEquals(5, vm.state.value.currentItem)
            // The same pages again (new links, the images' sizes still unknown): the page on screen stays.
            reader.stored.value = unsized.copy(pages = unsized.pages.map { it.copy(url = it.url + "?sig=2") })
            advanceUntilIdle()
            assertEquals(5, vm.state.value.currentItem)
            // Opening the chapter records it once ("Continue reading"), at the resume page, never at page 1.
            assertEquals(listOf(4), saved)
        }

    /** Opening at the end (going back from the next chapter) waits for the pages too. */
    @Test
    fun startAtEndSurvivesAChapterSeenBeforeItsPages() =
        runTest(dispatcher) {
            val reader = FakeReader(comic.copy(pages = emptyList()), AppResult.Ok(Unit))
            val vm =
                ReaderViewModel(
                    "rules",
                    "t1",
                    "c1",
                    startPage = ReaderViewModel.START_AT_END,
                    reader,
                    FakeSettings(),
                    noHeadings,
                ) { _, _, _, _ -> }
            advanceUntilIdle()
            reader.stored.value = comic
            advanceUntilIdle()
            assertEquals("5 / 5", vm.state.value.positionLabel)
            assertEquals(5, vm.state.value.currentItem)
        }

    @Test
    fun progressIsSavedAfterAPause() =
        runTest(dispatcher) {
            val saved = mutableListOf<Pair<String, Int>>()
            val vm =
                ReaderViewModel(
                    "gutenberg",
                    "t1",
                    "c1",
                    0,
                    FakeReader(comic, AppResult.Ok(Unit)),
                    FakeSettings(ReaderPreferences(coachSeen = ALL_COACHES)),
                    noHeadings,
                ) { source, _, _, page ->
                    saved +=
                        source to page
                }
            advanceUntilIdle()
            vm.onIntent(ReaderIntent.PageVisible(1))
            advanceTimeBy(200)
            vm.onIntent(ReaderIntent.PageVisible(2))
            advanceUntilIdle()
            // Opening the chapter counts (for "Continue reading"), then the page the reader settled on.
            assertEquals(listOf("gutenberg" to 0, "gutenberg" to 2), saved)
        }

    @Test
    fun openingAChapterRecordsItOnceEvenWhenTheModeChanges() =
        runTest(dispatcher) {
            val saved = mutableListOf<Int>()
            val settings = FakeSettings(ReaderPreferences(coachSeen = ALL_COACHES))
            val vm =
                ReaderViewModel("vovoscan", "t1", "c1", 3, FakeReader(comic, AppResult.Ok(Unit)), settings, noHeadings) { _, _, _, page ->
                    saved +=
                        page
                }
            advanceUntilIdle()
            vm.onIntent(ReaderIntent.SetMode(ReadingMode.PagedVertical))
            advanceUntilIdle()
            assertEquals(listOf(3), saved)
        }

    @Test
    fun bookOpensAndReadsBySection() =
        runTest(dispatcher) {
            val reader = FakeReader(book, AppResult.Ok(Unit))
            val vm =
                ReaderViewModel(
                    "vovoscan",
                    "t9",
                    "b1",
                    startPage = 1,
                    reader,
                    FakeSettings(ReaderPreferences(coachSeen = ALL_COACHES)),
                    noHeadings,
                ) { _, _, _, _ -> }
            advanceUntilIdle()
            assertEquals(1, reader.loads)
            assertEquals("Section 2 / 2", vm.state.value.positionLabel)
            assertEquals("The Rain Returns", vm.state.value.sectionTitle)
            assertTrue(
                vm.state.value.sectionDocument!!
                    .contains("<p>Section 1</p>"),
            )
            assertTrue(
                vm.state.value.sectionDocument!!
                    .contains("Content-Security-Policy"),
            )
            vm.onIntent(ReaderIntent.Previous)
            advanceUntilIdle()
            assertTrue(
                vm.state.value.sectionDocument!!
                    .contains("<p>Section 0</p>"),
            )
        }

    @Test
    fun pullingPastTheEndOfASectionOpensTheNext() =
        runTest(dispatcher) {
            val vm =
                ReaderViewModel(
                    "vovoscan",
                    "t9",
                    "b1",
                    startPage = 0,
                    FakeReader(book, AppResult.Ok(Unit)),
                    FakeSettings(ReaderPreferences(coachSeen = ALL_COACHES)),
                    noHeadings,
                ) { _, _, _, _ -> }
            advanceUntilIdle()
            val threshold = ReaderUiState.PULL_THRESHOLD
            // A bounce shows nothing; the hint grows with the pull, then says to let go.
            assertNull(vm.state.value.pullHint(0.005f))
            val half = vm.state.value.pullHint(threshold / 2)!!
            assertEquals(
                PullHint(forward = true, progress = 0.5f, armed = false, label = "Pull for the next section", enabled = true),
                half,
            )
            val past = vm.state.value.pullHint(threshold * 1.5f)!!
            assertEquals("Release for the next section", past.label)
            assertTrue(past.armed)
            assertEquals(1f, past.progress)
            // At the top of the first section, and no previous part.
            assertEquals(
                PullHint(forward = false, progress = 1f, armed = false, label = "This is the first section.", enabled = false),
                vm.state.value.pullHint(-threshold * 2),
            )
            // Let go before the threshold: nothing; past it: the next section, from its top.
            vm.onIntent(ReaderIntent.PullReleased(threshold * 0.8f))
            advanceUntilIdle()
            assertEquals(0, vm.state.value.currentIndex)
            vm.onIntent(ReaderIntent.PullReleased(threshold * 1.2f))
            advanceUntilIdle()
            assertEquals(1, vm.state.value.currentIndex)
            assertFalse(vm.state.value.sectionFromEnd)
            assertTrue(
                vm.state.value.sectionDocument!!
                    .contains("<p>Section 1</p>"),
            )
            // The last section of a book in several parts: the next part (the buttons' target).
            assertEquals(
                "Release for the next part",
                vm.state.value
                    .pullHint(threshold)!!
                    .label,
            )
            assertEquals(
                "Release for the previous section",
                vm.state.value
                    .pullHint(-threshold)!!
                    .label,
            )
            vm.onIntent(ReaderIntent.PullReleased(-threshold))
            advanceUntilIdle()
            assertEquals(0, vm.state.value.currentIndex)
            // Going back: the previous section from its end.
            assertTrue(vm.state.value.sectionFromEnd)
            vm.onIntent(ReaderIntent.PullReleased(threshold))
            // While the section loads, a pull does nothing.
            vm.onIntent(ReaderIntent.PullReleased(threshold))
            assertEquals(1, vm.state.value.currentIndex)
            advanceUntilIdle()
            vm.onIntent(ReaderIntent.PullReleased(threshold))
            assertEquals(ReaderCommand.OpenChapter(1, "c2", 0), vm.state.value.command)
        }

    @Test
    fun pullingAtTheEdgesOfABookInOnePart() =
        runTest(dispatcher) {
            val single = book.copy(previousChapterId = "b0", nextChapterId = null)
            val vm =
                ReaderViewModel(
                    "vovoscan",
                    "t9",
                    "b1",
                    startPage = 1,
                    FakeReader(single, AppResult.Ok(Unit)),
                    FakeSettings(ReaderPreferences(coachSeen = ALL_COACHES)),
                    noHeadings,
                ) { _, _, _, _ -> }
            advanceUntilIdle()
            val threshold = ReaderUiState.PULL_THRESHOLD
            val last = vm.state.value.pullHint(threshold)!!
            assertEquals("This is the last section.", last.label)
            assertFalse(last.armed)
            vm.onIntent(ReaderIntent.PullReleased(threshold * 2))
            advanceUntilIdle()
            assertNull(vm.state.value.command)
            assertEquals(1, vm.state.value.currentIndex)
            vm.onIntent(ReaderIntent.Previous)
            advanceUntilIdle()
            // The button opens a section at its top; at the first section, a pull down goes to the previous part's end.
            assertFalse(vm.state.value.sectionFromEnd)
            assertEquals(
                "Pull for the previous part",
                vm.state.value
                    .pullHint(-threshold / 2)!!
                    .label,
            )
            vm.onIntent(ReaderIntent.PullReleased(-threshold))
            assertEquals(ReaderCommand.OpenChapter(1, "b0", ReaderViewModel.START_AT_END), vm.state.value.command)
        }

    @Test
    fun aBookOpenedFromItsEnd() =
        runTest(dispatcher) {
            val vm =
                ReaderViewModel(
                    "vovoscan",
                    "t9",
                    "b1",
                    startPage = ReaderViewModel.START_AT_END,
                    FakeReader(book, AppResult.Ok(Unit)),
                    FakeSettings(ReaderPreferences(coachSeen = ALL_COACHES)),
                    noHeadings,
                ) { _, _, _, _ -> }
            advanceUntilIdle()
            assertEquals("Section 2 / 2", vm.state.value.positionLabel)
            assertTrue(vm.state.value.sectionFromEnd)
            // Comics have no pull.
            val comicReader =
                ReaderViewModel(
                    "vovoscan",
                    "t1",
                    "c1",
                    0,
                    FakeReader(comic, AppResult.Ok(Unit)),
                    FakeSettings(ReaderPreferences(coachSeen = ALL_COACHES)),
                    noHeadings,
                ) { _, _, _, _ -> }
            advanceUntilIdle()
            assertNull(comicReader.state.value.pullHint(1f))
        }

    @Test
    fun neverOpenedAndOffline() =
        runTest(dispatcher) {
            val vm =
                ReaderViewModel(
                    "vovoscan",
                    "t1",
                    "c1",
                    0,
                    FakeReader(null, AppResult.Err(AppError.Network)),
                    FakeSettings(ReaderPreferences(coachSeen = ALL_COACHES)),
                    noHeadings,
                ) { _, _, _, _ -> }
            advanceUntilIdle()
            assertTrue(vm.state.value.unavailable)
            assertEquals(Freshness.Offline, vm.state.value.freshness)
        }
}
