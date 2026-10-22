package com.vovoscan.feature.reader.presentation

import com.vovoscan.core.common.AppResult
import com.vovoscan.feature.reader.domain.Book
import com.vovoscan.feature.reader.domain.ChapterHeadings
import com.vovoscan.feature.reader.domain.CoachKind
import com.vovoscan.feature.reader.domain.CoachSync
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderCoachTest {
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

    private class Static(
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

    private fun TestScope.reader(
        settings: FakeSettings,
        chapter: ReaderChapter = manga,
        sync: CoachSync = CoachSync.None,
    ): ReaderViewModel {
        val vm =
            ReaderViewModel(
                "vovoscan",
                "t1",
                chapter.id,
                0,
                Static(chapter),
                settings,
                ChapterHeadings {
                    _,
                    _,
                    _,
                    ->
                    null
                },
                coachSync = sync,
            ) { _, _, _, _ -> }
        advanceUntilIdle()
        return vm
    }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun theFirstMangaReadRightToLeftOpensWithItsCoachUntilATap() =
        runTest(dispatcher) {
            val settings = FakeSettings(ReaderPreferences(mangaMode = ReadingMode.PagedRightToLeft))
            val vm = reader(settings)
            val coach = assertNotNull(vm.state.value.coach)
            assertEquals(CoachKind.Manga, coach.kind)
            assertEquals("How to read", coach.kicker)
            assertEquals("Manga ✦ right to left", coach.title)
            // The real zones (right and left: the left third goes on in a manga), drawn by the coach, not flashed.
            assertNotNull(coach.zones)
            assertNull(vm.state.value.tapOverlay)
            assertEquals(
                listOf(CoachGesture.Tap, CoachGesture.Swipe, CoachGesture.Help),
                coach.hints.map { it.gesture },
            )
            assertTrue(coach.hints[1].text.startsWith("Swipe right to go on"))
            // A tap closes it and never turns the page.
            vm.onIntent(ReaderIntent.Tap(0.1f, 0.5f))
            advanceUntilIdle()
            assertNull(vm.state.value.coach)
            assertNull(vm.state.value.command)
            assertEquals(setOf(CoachKind.Manga), settings.preferences.value.coachSeen)
            // The next tap reads.
            vm.onIntent(ReaderIntent.Tap(0.1f, 0.5f))
            assertIs<ReaderCommand.GoToItem>(vm.state.value.command)
        }

    @Test
    fun onceSeenItStaysAwayUntilAskedAndEachFormatHasItsOwn() =
        runTest(dispatcher) {
            val seen = ReaderPreferences(mangaMode = ReadingMode.PagedRightToLeft, coachSeen = setOf(CoachKind.Manga))
            val vm = reader(FakeSettings(seen))
            assertNull(vm.state.value.coach)
            // The help button in the bars.
            vm.onIntent(ReaderIntent.ShowCoach)
            assertEquals(
                CoachKind.Manga,
                vm.state.value.coach
                    ?.kind,
            )
            vm.onIntent(ReaderIntent.Next)
            assertNull(vm.state.value.coach)
            assertNull(vm.state.value.command)
            // A webtoon is another format: its own coach, with the zoom line when zoom is on.
            val strip = reader(FakeSettings(seen.copy(doubleTapZoom = true)), manga.copy(format = ComicFormat.Webtoon))
            val coach = assertNotNull(strip.state.value.coach)
            assertEquals(CoachKind.Webtoon, coach.kind)
            assertEquals(CoachGesture.DoubleTap, coach.hints[2].gesture)
        }

    private class AccountGuides(
        var seen: Set<CoachKind>?,
    ) : CoachSync {
        val marked = mutableListOf<CoachKind>()

        override suspend fun seen() = seen

        override suspend fun mark(kind: CoachKind) {
            marked += kind
        }
    }

    @Test
    fun aGuideSeenOnAnotherDeviceOfTheAccountIsNotShownAgain() =
        runTest(dispatcher) {
            val settings = FakeSettings(ReaderPreferences(mangaMode = ReadingMode.PagedRightToLeft))
            val vm = reader(settings, sync = AccountGuides(setOf(CoachKind.Manga)))
            assertNull(vm.state.value.coach)
            assertEquals(setOf(CoachKind.Manga), settings.preferences.value.coachSeen)
        }

    @Test
    fun closingAGuideTellsTheAccountAndOneSeenOfflineIsToldLater() =
        runTest(dispatcher) {
            val account = AccountGuides(emptySet())
            val vm = reader(FakeSettings(ReaderPreferences(mangaMode = ReadingMode.PagedRightToLeft)), sync = account)
            vm.onIntent(ReaderIntent.Tap(0.5f, 0.5f))
            advanceUntilIdle()
            assertEquals(listOf(CoachKind.Manga), account.marked)
            // Seen here while the account didn't know (offline): told when a reader opens.
            val later = AccountGuides(emptySet())
            reader(FakeSettings(ReaderPreferences(coachSeen = setOf(CoachKind.Comic, CoachKind.Book))), sync = later)
            assertEquals(listOf(CoachKind.Comic), later.marked)
        }
}
