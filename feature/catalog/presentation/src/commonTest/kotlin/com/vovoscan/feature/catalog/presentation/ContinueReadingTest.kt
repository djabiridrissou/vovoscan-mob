package com.vovoscan.feature.catalog.presentation

import com.vovoscan.feature.catalog.domain.Chapter
import com.vovoscan.feature.catalog.domain.ContentKind
import com.vovoscan.feature.catalog.domain.ReadingFormat
import com.vovoscan.feature.catalog.domain.ReadingResume
import com.vovoscan.feature.catalog.domain.ResumePoint
import com.vovoscan.feature.catalog.domain.TitleDetail
import com.vovoscan.feature.catalog.domain.TitleSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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

/** Chapter 1 on top, "Continue reading" where the account left the title, "Start reading" otherwise. */
@OptIn(ExperimentalCoroutinesApi::class)
class ContinueReadingTest {
    private val dispatcher = StandardTestDispatcher()
    private val summary =
        TitleSummary(
            "inkwell",
            "dle",
            "Dark and Light Martial Emperor",
            "https://img/c.jpg",
            ContentKind.Comic,
            ReadingFormat.Webtoon,
            "ongoing",
        )

    // As sources list them: newest first.
    private val detail =
        TitleDetail(
            summary,
            "",
            emptyList(),
            emptyList(),
            listOf(
                Chapter("k3", 3.0, null, 40, null),
                Chapter("k2", 2.0, "The Well", 38, null),
                Chapter("k1", 1.0, null, 35, null),
            ),
        )
    private val points = MutableStateFlow<List<ResumePoint>>(emptyList())
    private val resume = ReadingResume { points }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun repository() = FakeCatalog().apply { this.detail.value = this@ContinueReadingTest.detail }

    @Test
    fun chapterOneIsOnTopAndTheOrderIsRemembered() =
        runTest(dispatcher) {
            val repository = repository()
            val vm = TitleDetailViewModel("inkwell", "dle", repository, resume)
            advanceUntilIdle()
            assertEquals(
                listOf("k1", "k2", "k3"),
                vm.state.value.chapters
                    .map { it.id },
            )
            assertEquals("Oldest first", vm.state.value.orderLabel)
            assertEquals("k2", vm.state.value.nextChapterId("k1"))

            vm.onIntent(TitleDetailIntent.ToggleOrder)
            advanceUntilIdle()
            assertTrue(repository.newestFirst.value)
            assertEquals(
                listOf("k3", "k2", "k1"),
                vm.state.value.chapters
                    .map { it.id },
            )
            assertEquals("Newest first", vm.state.value.orderLabel)
            // The next chapter is still the one after in reading order.
            assertEquals("k2", vm.state.value.nextChapterId("k1"))
            assertNull(vm.state.value.nextChapterId("k3"))

            // Another title page opened later keeps the choice.
            val again = TitleDetailViewModel("inkwell", "dle", repository, resume)
            advanceUntilIdle()
            assertEquals(
                listOf("k3", "k2", "k1"),
                again.state.value.chapters
                    .map { it.id },
            )
        }

    @Test
    fun aTitleNeverReadStartsAtChapterOne() =
        runTest(dispatcher) {
            val vm = TitleDetailViewModel("inkwell", "dle", repository(), resume)
            advanceUntilIdle()
            val action = vm.state.value.readAction!!
            assertEquals("k1", action.chapterId)
            assertEquals(0, action.page)
            assertFalse(action.resumes)
            assertEquals("Start reading →", action.label)
            assertEquals("Chapter 1", action.detail)
            assertTrue(
                vm.state.value.chapters
                    .none { it.current },
            )
        }

    @Test
    fun aTitleLeftMidChapterContinuesThereAndFollowsNewReading() =
        runTest(dispatcher) {
            points.value = listOf(ResumePoint("inkwell", "other", "z9", 4, 900), ResumePoint("inkwell", "dle", "k2", 11, 500))
            val vm = TitleDetailViewModel("inkwell", "dle", repository(), resume)
            advanceUntilIdle()
            val action = vm.state.value.readAction!!
            assertTrue(action.resumes)
            assertEquals("k2", action.chapterId)
            assertEquals(11, action.page)
            assertEquals("Continue reading →", action.label)
            assertEquals("Chapter 2 · The Well ✦ page 12", action.detail)
            assertEquals(
                listOf("k2"),
                vm.state.value.chapters
                    .filter { it.current }
                    .map { it.id },
            )

            // Reading goes on (here or on another device): the button follows.
            points.value = listOf(ResumePoint("inkwell", "dle", "k3", 0, 1_000))
            advanceUntilIdle()
            assertEquals(
                "k3",
                vm.state.value.readAction
                    ?.chapterId,
            )
            assertEquals(
                "Chapter 3",
                vm.state.value.readAction
                    ?.detail,
            )
        }

    @Test
    fun aChapterTheSourceNoLongerListsFallsBackToStartReading() =
        runTest(dispatcher) {
            points.value = listOf(ResumePoint("inkwell", "dle", "gone", 3, 500))
            val vm = TitleDetailViewModel("inkwell", "dle", repository(), resume)
            advanceUntilIdle()
            assertEquals(
                "k1",
                vm.state.value.readAction
                    ?.chapterId,
            )
            assertFalse(
                vm.state.value.readAction!!
                    .resumes,
            )
        }

    @Test
    fun theCatalogOffersTheLastTitleReadAndMarksStartedCards() =
        runTest(dispatcher) {
            val repository =
                repository().apply {
                    remote["inkwell" to null] = listOf(listOf(summary, summary.copy(id = "fresh", name = "Fresh")))
                }
            val vm = CatalogViewModel(repository, resume)
            vm.onIntent(CatalogIntent.SourcesChanged(listOf(CatalogSource("inkwell", "Inkwell"))))
            advanceUntilIdle()
            assertNull(vm.state.value.resume)
            assertTrue(
                vm.state.value.visible
                    .none { it.started },
            )

            points.value = listOf(ResumePoint("inkwell", "dle", "k2", 0, 500))
            advanceUntilIdle()
            val card = vm.state.value.resume!!
            assertEquals("Dark and Light Martial Emperor", card.titleName)
            assertEquals("https://img/c.jpg", card.coverUrl)
            assertEquals("k2", card.chapterId)
            assertEquals("Continue reading →", card.label)
            assertEquals("Chapter 2 · The Well", card.detail)
            assertEquals(
                mapOf("dle" to true, "fresh" to false),
                vm.state.value.visible
                    .associate { it.id to it.started },
            )
            assertEquals("Reading", vm.state.value.startedLabel)
        }
}
