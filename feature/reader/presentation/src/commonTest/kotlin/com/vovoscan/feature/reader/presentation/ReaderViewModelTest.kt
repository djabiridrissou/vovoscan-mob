package com.vovoscan.feature.reader.presentation

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.Freshness
import com.vovoscan.feature.reader.domain.Book
import com.vovoscan.feature.reader.domain.BookSection
import com.vovoscan.feature.reader.domain.ReaderChapter
import com.vovoscan.feature.reader.domain.ReaderPage
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
    }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun resumesAtSavedPage() =
        runTest(dispatcher) {
            val vm = ReaderViewModel("vovoscan", "t1", "c1", startPage = 3, FakeReader(comic, AppResult.Ok(Unit))) { _, _, _, _ -> }
            advanceUntilIdle()
            assertEquals("4 / 5", vm.state.value.positionLabel)
            assertTrue(vm.state.value.rightToLeft)
            assertEquals("Next chapter →", vm.state.value.nextLabel)
        }

    @Test
    fun progressIsSavedAfterAPause() =
        runTest(dispatcher) {
            val saved = mutableListOf<Pair<String, Int>>()
            val vm =
                ReaderViewModel("gutenberg", "t1", "c1", 0, FakeReader(comic, AppResult.Ok(Unit))) { source, _, _, page ->
                    saved +=
                        source to page
                }
            advanceUntilIdle()
            vm.onIntent(ReaderIntent.PageVisible(1))
            advanceTimeBy(200)
            vm.onIntent(ReaderIntent.PageVisible(2))
            advanceUntilIdle()
            assertEquals(listOf("gutenberg" to 2), saved)
        }

    @Test
    fun bookOpensAndReadsBySection() =
        runTest(dispatcher) {
            val reader = FakeReader(book, AppResult.Ok(Unit))
            val vm = ReaderViewModel("vovoscan", "t9", "b1", startPage = 1, reader) { _, _, _, _ -> }
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
    fun neverOpenedAndOffline() =
        runTest(dispatcher) {
            val vm = ReaderViewModel("vovoscan", "t1", "c1", 0, FakeReader(null, AppResult.Err(AppError.Network))) { _, _, _, _ -> }
            advanceUntilIdle()
            assertTrue(vm.state.value.unavailable)
            assertEquals(Freshness.Offline, vm.state.value.freshness)
        }
}
