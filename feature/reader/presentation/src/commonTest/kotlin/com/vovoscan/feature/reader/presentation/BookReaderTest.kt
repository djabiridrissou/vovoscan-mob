package com.vovoscan.feature.reader.presentation

import androidx.lifecycle.ViewModelStore
import com.vovoscan.core.common.AppResult
import com.vovoscan.feature.reader.domain.Book
import com.vovoscan.feature.reader.domain.BookFont
import com.vovoscan.feature.reader.domain.BookLineSpacing
import com.vovoscan.feature.reader.domain.BookMargins
import com.vovoscan.feature.reader.domain.BookPosition
import com.vovoscan.feature.reader.domain.BookReadingRepository
import com.vovoscan.feature.reader.domain.BookSection
import com.vovoscan.feature.reader.domain.BookTheme
import com.vovoscan.feature.reader.domain.BookTypography
import com.vovoscan.feature.reader.domain.ChapterHeadings
import com.vovoscan.feature.reader.domain.CoachKind
import com.vovoscan.feature.reader.domain.DownloadableChapter
import com.vovoscan.feature.reader.domain.DownloadableTitle
import com.vovoscan.feature.reader.domain.ReaderChapter
import com.vovoscan.feature.reader.domain.ReaderPreferences
import com.vovoscan.feature.reader.domain.ReaderRepository
import com.vovoscan.feature.reader.domain.ReadingMode
import com.vovoscan.feature.reader.domain.TitleChapters
import com.vovoscan.feature.reader.domain.TocEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Books: typography, the position inside a section, the contents. */
@OptIn(ExperimentalCoroutinesApi::class)
class BookReaderTest {
    private val dispatcher = StandardTestDispatcher()

    private val chapter =
        ReaderChapter(
            "rules",
            "b1",
            "t1",
            "Letters",
            1.0,
            "Part one",
            ReadingMode.Book,
            null,
            "b2",
            emptyList(),
            "https://x/b.epub",
        )

    private val toc =
        listOf(
            TocEntry("Book One", 0, 0, null),
            TocEntry("Chapter 1. The Lantern", 1, 0, "c1"),
            TocEntry("Chapter 2. The Ferry", 1, 0, "c2"),
            TocEntry("Chapter 3. The Far Bank", 1, 2, null),
        )

    private inner class Reader(
        private val withToc: Boolean = true,
    ) : ReaderRepository {
        val book = MutableStateFlow<Book?>(null)

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
        ): Flow<Book?> = book

        override suspend fun loadBook(
            sourceKey: String,
            chapterId: String,
        ): AppResult<Unit> {
            book.value =
                Book(
                    listOf(BookSection(0, "Book One"), BookSection(1, "Plates"), BookSection(2, "Chapter 3. The Far Bank")),
                    if (withToc) toc else emptyList(),
                )
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

    // The first-open coach already closed for every kind (`ReaderCoachTest`).
    private val coached = ReaderPreferences(coachSeen = CoachKind.entries.toSet())

    private fun TestScope.reader(
        startPage: Int = 0,
        books: BookReadingRepository = BookReadingRepository.InMemory(),
        parts: TitleChapters = TitleChapters { _, _ -> flowOf(null) },
        reader: Reader = Reader(),
    ): ReaderViewModel {
        val vm =
            ReaderViewModel(
                "rules",
                "t1",
                "b1",
                startPage,
                reader,
                FakeSettings(coached),
                noHeadings,
                books = books,
                parts = parts,
            ) { _, _, _, _ -> }
        advanceUntilIdle()
        return vm
    }

    @Test
    fun typographyIsAppliedLiveWhereTheReaderWas() =
        runTest(dispatcher) {
            val books = BookReadingRepository.InMemory()
            val vm = reader(books = books)
            assertTrue("font-size: 18px" in vm.state.value.sectionDocument!!)
            vm.onIntent(BookIntent.SectionScrolled(0, 0.42f))
            val before =
                vm.state.value.book.opening
                    ?.seq ?: 0

            vm.onIntent(BookIntent.SetFont(BookFont.Serif))
            advanceUntilIdle()
            val state = vm.state.value
            assertEquals(BookFont.Serif, books.typography.value.font)
            assertTrue(BookStyle.fontStack(BookFont.Serif) in state.sectionDocument!!)
            // The section is set again and opens where the reader was in it.
            val opening = state.book.opening!!
            assertTrue(opening.seq > before)
            assertEquals(0, opening.section)
            assertEquals(0.42f, opening.fraction)
            assertTrue(
                state.book.settings.fonts
                    .single { it.selected }
                    .font == BookFont.Serif,
            )

            vm.onIntent(BookIntent.TextBigger)
            vm.onIntent(BookIntent.SetTheme(BookTheme.Sepia))
            advanceUntilIdle()
            assertEquals("110 %", vm.state.value.book.settings.textSize.label)
            assertTrue(vm.state.value.book.settings.textSize.canReset)
            assertEquals(0xF4ECD8, vm.state.value.book.pageBackground)
            assertTrue("font-size: 19.8px" in vm.state.value.sectionDocument!!)
        }

    @Test
    fun theTextFollowsTheDevicesTextSize() =
        runTest(dispatcher) {
            val vm = reader()
            vm.onIntent(BookIntent.SystemTextScale(1.5f))
            assertTrue("font-size: 27px" in vm.state.value.sectionDocument!!)
            // The same scale again sets nothing again.
            val document = vm.state.value.sectionDocument
            vm.onIntent(BookIntent.SystemTextScale(1.5f))
            assertTrue(document === vm.state.value.sectionDocument)
        }

    @Test
    fun reopensWhereTheReaderWasInTheSection() =
        runTest(dispatcher) {
            val books = BookReadingRepository.InMemory().apply { positions["rules/b1"] = BookPosition(1, 0.6f) }
            // The library's progress opens section 1 (2 / 3): the device knows where in it.
            val opening =
                reader(startPage = 1, books = books)
                    .state.value.book.opening!!
            assertEquals(1, opening.section)
            assertEquals(0.6f, opening.fraction)
            assertFalse(opening.fromEnd)
            // Another section opened (the library is further on, or the part from its start): its top.
            assertNull(
                reader(startPage = 0, books = books)
                    .state.value.book.opening
                    ?.fraction,
            )
            // Coming back from the next part: the end, whatever was saved.
            val end =
                reader(startPage = ReaderViewModel.START_AT_END, books = books)
                    .state.value.book.opening!!
            assertTrue(end.fromEnd)
            assertEquals(2, end.section)
        }

    @Test
    fun thePositionIsSavedOnceScrollingPausesAndWhenLeaving() =
        runTest(dispatcher) {
            val books = BookReadingRepository.InMemory()
            val vm = reader(books = books)
            vm.onIntent(BookIntent.SectionScrolled(0, 0.2f))
            advanceTimeBy(ReaderViewModel.POSITION_DELAY_MS / 2)
            vm.onIntent(BookIntent.SectionScrolled(0, 0.3f))
            advanceTimeBy(ReaderViewModel.POSITION_DELAY_MS / 2)
            assertNull(books.positions["rules/b1"])
            advanceTimeBy(ReaderViewModel.POSITION_DELAY_MS)
            assertEquals(BookPosition(0, 0.3f), books.positions["rules/b1"])
            // A report for another section than the one on screen (a web view still showing the last one) is ignored.
            vm.onIntent(BookIntent.SectionScrolled(2, 0.9f))
            advanceUntilIdle()
            assertEquals(BookPosition(0, 0.3f), books.positions["rules/b1"])
            // Leaving before the pause ends: saved at once.
            vm.onIntent(BookIntent.SectionScrolled(0, 0.75f))
            ViewModelStore().apply { put("reader", vm) }.clear()
            assertEquals(BookPosition(0, 0.75f), books.positions["rules/b1"])
        }

    @Test
    fun theSectionReadIsSavedInTheLibraryWhenLeavingBeforeThePause() =
        runTest(dispatcher) {
            val saved = mutableListOf<Int>()
            val vm = ReaderViewModel("rules", "t1", "b1", 0, Reader(), FakeSettings(coached), noHeadings) { _, _, _, page -> saved += page }
            advanceUntilIdle()
            vm.onIntent(ReaderIntent.Next)
            ViewModelStore().apply { put("reader", vm) }.clear()
            assertEquals(listOf(0, 1), saved) // the opening, then the section read
        }

    @Test
    fun anotherSectionOpensAtItsTopOrItsEnd() =
        runTest(dispatcher) {
            val books = BookReadingRepository.InMemory()
            val vm = reader(books = books)
            vm.onIntent(BookIntent.SectionScrolled(0, 0.5f))
            vm.onIntent(ReaderIntent.Next)
            advanceUntilIdle()
            var opening = vm.state.value.book.opening!!
            assertEquals(1, opening.section)
            assertNull(opening.fraction)
            assertFalse(opening.fromEnd)
            assertEquals(BookPosition(1, 0f), books.positions["rules/b1"])
            // Pulled back past the top: the previous section, from its end.
            vm.onIntent(ReaderIntent.PullReleased(-ReaderUiState.PULL_THRESHOLD))
            advanceUntilIdle()
            opening = vm.state.value.book.opening!!
            assertEquals(0, opening.section)
            assertTrue(opening.fromEnd)
        }

    @Test
    fun contentsListTheBooksEntriesAndOpenThem() =
        runTest(dispatcher) {
            val vm = reader()
            val rows = vm.state.value.book.contents
            assertEquals(
                listOf("Book One", "Chapter 1. The Lantern", "Chapter 2. The Ferry", "Plates", "Chapter 3. The Far Bank"),
                rows.map { it.label },
            )
            assertEquals(listOf(0, 1, 1, 0, 1), rows.map { it.depth })
            // The rows of the section being read are marked.
            assertEquals(listOf(true, true, true, false, false), rows.map { it.current })

            // An entry inside the section on screen: the same document, its element at the top.
            val document = vm.state.value.sectionDocument
            vm.onIntent(BookIntent.ChooseContents(rows[2].key))
            advanceUntilIdle()
            assertTrue(document === vm.state.value.sectionDocument)
            assertEquals(
                "c2",
                vm.state.value.book.opening!!
                    .anchor,
            )

            // Another section: it opens at its top.
            vm.onIntent(BookIntent.ChooseContents(rows[4].key))
            advanceUntilIdle()
            val state = vm.state.value
            assertEquals(2, state.currentIndex)
            assertEquals(2, state.book.opening!!.section)
            assertNull(state.book.opening!!.anchor)
            assertTrue("Section 2" in state.sectionDocument!!)
            assertEquals(listOf(false, false, false, false, true), state.book.contents.map { it.current })
        }

    @Test
    fun aBookInSeveralPartsListsItsParts() =
        runTest(dispatcher) {
            val title =
                DownloadableTitle(
                    "rules",
                    "t1",
                    "Letters",
                    isBook = true,
                    chapters =
                        listOf(
                            DownloadableChapter("b1", 1.0, "Part one"),
                            DownloadableChapter("b2", 2.0, "Part two"),
                        ),
                    resumeChapterId = "b1",
                )
            val vm = reader(parts = { _, _ -> flowOf(title) }, reader = Reader(withToc = false))
            val rows = vm.state.value.book.contents
            assertEquals(listOf("Part one", "Book One", "Plates", "Chapter 3. The Far Bank", "Part two"), rows.map { it.label })
            assertEquals(listOf(0, 1, 1, 1, 0), rows.map { it.depth })
            assertEquals(listOf(true, false), rows.filter { it.part }.map { it.enabled.not() })
            vm.onIntent(BookIntent.ChooseContents(rows.last().key))
            val command = vm.state.value.command as ReaderCommand.OpenChapter
            assertEquals("b2", command.chapterId)
            assertEquals(0, command.startPage)
        }

    @Test
    fun theStyleSheetFollowsTheTypography() {
        val page =
            BookStyle.document(
                "T",
                "<p>x</p>",
                BookTypography(
                    lineSpacing = BookLineSpacing.Compact,
                    margins = BookMargins.Wide,
                    justify = true,
                    theme = BookTheme.Light,
                ),
            )
        assertTrue("line-height: 1.45" in page, page)
        assertTrue("padding: 24px 36px 96px" in page)
        assertTrue("text-align: justify" in page)
        assertTrue("background: #fbfaf7" in page)
        assertTrue("color-scheme: light" in page)
        // Still nothing loaded from anywhere.
        assertTrue("default-src 'none'; img-src data:; style-src 'unsafe-inline'" in page)
        assertEquals("18", BookStyle.cssNumber(18f))
        assertEquals("20.7", BookStyle.cssNumber(20.7f))
        assertEquals("1.7", BookStyle.cssNumber(1.7f))
        assertEquals("#0b0b0e", BookStyle.hex(0x0B0B0E))
    }
}

class BookAnchorsTest {
    @Test
    fun anElementsShareOfTheText() {
        val page =
            BookStyle.document(
                "T",
                "<h2 id=\"c1\">One</h2><p>aaaaaaaaaa</p><img src=\"data:image/png;base64,QUFBQUFBQUFBQUFBQUFB\">" +
                    "<h2 id=\"c2\">Two</h2><p>bbbbbbbbbb</p>",
            )
        assertEquals(0f, BookAnchors.textFraction(page, "c1"))
        // 13 characters of text before "Two", 13 after it: the image and the style sheet don't count.
        assertEquals(0.5f, BookAnchors.textFraction(page, "c2"))
        assertNull(BookAnchors.textFraction(page, "gone"))
    }
}
