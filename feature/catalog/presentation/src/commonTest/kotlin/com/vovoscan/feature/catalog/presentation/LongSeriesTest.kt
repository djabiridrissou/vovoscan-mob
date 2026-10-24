package com.vovoscan.feature.catalog.presentation

import com.vovoscan.feature.catalog.domain.Chapter
import com.vovoscan.feature.catalog.domain.ChapterLoad
import com.vovoscan.feature.catalog.domain.ContentKind
import com.vovoscan.feature.catalog.domain.ReadingFormat
import com.vovoscan.feature.catalog.domain.ReadingResume
import com.vovoscan.feature.catalog.domain.ResumePoint
import com.vovoscan.feature.catalog.domain.TitleDetail
import com.vovoscan.feature.catalog.domain.TitleSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
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

/** A series of hundreds of chapters: the load line while its pages come, and the jumps. */
@OptIn(ExperimentalCoroutinesApi::class)
class LongSeriesTest {
    private val dispatcher = StandardTestDispatcher()
    private val summary = TitleSummary("vovoscan", "t1", "Ink Road", null, ContentKind.Comic, ReadingFormat.Webtoon, "ongoing")

    // Newest first, as saved; 60 and 60.5 then a gap from 62 to 79.
    private val numbers = (1..60).map { it.toDouble() } + 60.5 + (80..100).map { it.toDouble() }
    private val detail =
        TitleDetail(summary, "", emptyList(), emptyList(), numbers.reversed().map { Chapter("c$it", it, null, 10, null) })

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun theLoadLineFollowsThePagesOfChapters() =
        runTest(dispatcher) {
            val repository = FakeCatalog().apply { this.detail.value = this@LongSeriesTest.detail }
            val viewModel = TitleDetailViewModel("vovoscan", "t1", repository)
            advanceUntilIdle()
            assertNull(viewModel.state.value.chapterLoading)
            repository.chapterLoads.value = mapOf("vovoscan/t1" to ChapterLoad(600, 3012))
            advanceUntilIdle()
            assertEquals("Loading chapters ✦ 600 of 3,012", viewModel.state.value.chapterLoading)
            repository.chapterLoads.value = emptyMap()
            advanceUntilIdle()
            assertNull(viewModel.state.value.chapterLoading)
        }

    @Test
    fun aJumpGoesToTheChapterOrTheNearestOne() =
        runTest(dispatcher) {
            val repository = FakeCatalog().apply { this.detail.value = this@LongSeriesTest.detail }
            val at = ResumePoint("vovoscan", "t1", "c42.0", 3, 1)
            val viewModel = TitleDetailViewModel("vovoscan", "t1", repository, ReadingResume { flowOf(listOf(at)) })
            advanceUntilIdle()
            val state = viewModel.state.value
            assertTrue(state.showJump)
            assertEquals("Chapter being read", state.jumpCurrent)

            viewModel.onIntent(TitleDetailIntent.JumpTo(" 60,5 "))
            assertEquals(
                "c60.5",
                viewModel.state.value.scroll
                    ?.chapterId,
            )
            val seq =
                viewModel.state.value.scroll!!
                    .seq
            viewModel.onIntent(TitleDetailIntent.ScrollHandled(seq))
            assertNull(viewModel.state.value.scroll)

            // No chapter 70: the nearest listed one.
            viewModel.onIntent(TitleDetailIntent.JumpTo("75"))
            assertEquals(
                "c80.0",
                viewModel.state.value.scroll
                    ?.chapterId,
            )

            viewModel.onIntent(TitleDetailIntent.JumpToCurrent)
            assertEquals(
                "c42.0",
                viewModel.state.value.scroll
                    ?.chapterId,
            )

            viewModel.onIntent(TitleDetailIntent.JumpTo("abc"))
            assertEquals("Type a chapter number.", viewModel.state.value.jumpError)
            viewModel.onIntent(TitleDetailIntent.JumpTo("1"))
            assertNull(viewModel.state.value.jumpError)
            assertEquals(
                "c1.0",
                viewModel.state.value.scroll
                    ?.chapterId,
            )
        }

    @Test
    fun aShortListHasNoJump() =
        runTest(dispatcher) {
            val short = detail.copy(chapters = detail.chapters.take(10))
            val repository = FakeCatalog().apply { this.detail.value = short }
            val viewModel = TitleDetailViewModel("vovoscan", "t1", repository)
            advanceUntilIdle()
            assertFalse(viewModel.state.value.showJump)
            assertNull(viewModel.state.value.jumpCurrent)
        }
}
