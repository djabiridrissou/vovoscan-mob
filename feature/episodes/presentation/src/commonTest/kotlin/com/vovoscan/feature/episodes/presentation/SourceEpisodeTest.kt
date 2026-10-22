package com.vovoscan.feature.episodes.presentation

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.ChapterImports
import com.vovoscan.core.common.ImportStatus
import com.vovoscan.core.common.ImportedChapter
import com.vovoscan.core.common.SourceUnit
import com.vovoscan.feature.episodes.domain.EpisodeAccess
import com.vovoscan.feature.episodes.domain.EpisodeStatus
import com.vovoscan.feature.episodes.domain.KeyStatus
import com.vovoscan.feature.episodes.domain.PrepareResult
import com.vovoscan.feature.episodes.domain.RunCost
import com.vovoscan.feature.episodes.domain.RunQuotes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
import kotlin.test.assertTrue

/** A chapter of a source read on the device: its pages go to the server first, then the card is the copy's. */
@OptIn(ExperimentalCoroutinesApi::class)
class SourceEpisodeTest {
    private val dispatcher = StandardTestDispatcher()
    private val keys = FakeKeyStatus(KeyStatus(true, "abcd", "9876"))

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class Imports(
        var found: ImportedChapter? = null,
        val states: ArrayDeque<ImportedChapter> = ArrayDeque(),
        val imported: AppResult<ImportedChapter> = AppResult.Ok(copy(ImportStatus.Fetching)),
    ) : ChapterImports {
        val asked = mutableListOf<SourceUnit>()

        override suspend fun find(unit: SourceUnit): ImportedChapter? = found

        override suspend fun import(unit: SourceUnit): AppResult<ImportedChapter> {
            asked += unit
            return imported
        }

        override suspend fun status(chapterId: String): AppResult<ImportedChapter> = AppResult.Ok(states.removeFirst())
    }

    private fun vm(
        repo: FakeEpisodes,
        imports: ChapterImports,
        quotes: RunQuotes = RunQuotes { id, _ -> if (id == "server-1") RunCost(12, false, false) else null },
    ) = EpisodeViewModel("u3", repo, keys, EpisodeAccess.Open, quotes, sourceKey = "inkwell", titleId = "t9", imports = imports)

    @Test
    fun nothingIsCopiedUntilPrepareThenTheCostIsShownBeforeAnythingIsPaid() =
        runTest(dispatcher) {
            val repo = FakeEpisodes()
            repo.runResult = { AppResult.Ok(PrepareResult.Queued(episode(EpisodeStatus.Queued))) }
            val imports = Imports(states = ArrayDeque(listOf(copy(ImportStatus.Fetching, 5), copy(ImportStatus.Ready, 12))))
            val vm = vm(repo, imports)
            advanceUntilIdle()
            var state = vm.state.value
            assertEquals(EpisodePhase.None, state.phase)
            assertEquals("Its pages come from the source first. You’ll see the cost before anything is paid.", state.detail)
            assertTrue(state.canPrepare)
            assertEquals("", state.chapterId)
            assertTrue(imports.asked.isEmpty())
            assertTrue(repo.chapterIds.isEmpty())

            vm.onIntent(EpisodeIntent.Prepare)
            runCurrent()
            advanceTimeBy(ChapterImports.POLL_MS + 1)
            state = vm.state.value
            assertEquals(EpisodePhase.Running, state.phase)
            assertEquals("Getting the pages…", state.headline)
            assertEquals("5 pages in", state.progressLabel)
            assertEquals(listOf(SourceUnit("inkwell", "t9", "u3")), imports.asked)

            advanceUntilIdle()
            state = vm.state.value
            // The copy is the card's now: its own (empty) episode and its cost; nothing was run.
            assertEquals("server-1", state.chapterId)
            assertEquals(EpisodePhase.None, state.phase)
            assertEquals("Costs 12 credits from your wallet.", state.costLine)
            assertEquals("The pages are in. Check the cost, then prepare.", state.notice)
            assertTrue(repo.runs.isEmpty())
            assertTrue(repo.chapterIds.all { it == "server-1" })

            vm.onIntent(EpisodeIntent.Prepare)
            advanceUntilIdle()
            assertEquals(listOf(false), repo.runs)
            assertEquals("server-1", repo.chapterIds.last())
        }

    @Test
    fun aCopyMadeBeforeIsUsedAtOnce() =
        runTest(dispatcher) {
            val repo = FakeEpisodes(episode(EpisodeStatus.Ready))
            val imports = Imports(found = copy(ImportStatus.Ready, 12))
            val vm = vm(repo, imports)
            advanceUntilIdle()
            val state = vm.state.value
            assertEquals("server-1", state.chapterId)
            assertTrue(state.canWatch)
            assertTrue(imports.asked.isEmpty())
        }

    @Test
    fun aFailedCopySaysWhyAndPrepareTriesAgain() =
        runTest(dispatcher) {
            val imports = Imports(states = ArrayDeque(listOf(copy(ImportStatus.Failed, error = "NO_PAGES"))))
            val vm = vm(FakeEpisodes(), imports)
            advanceUntilIdle()
            vm.onIntent(EpisodeIntent.Prepare)
            advanceUntilIdle()
            val state = vm.state.value
            assertEquals("The source has no pages for this chapter.", state.error)
            assertEquals(EpisodePhase.None, state.phase)
            assertTrue(state.canPrepare)
            assertEquals("", state.chapterId)
        }

    @Test
    fun aSourceTheServerDoesNotKnowYetIsExplained() =
        runTest(dispatcher) {
            val imports = Imports(imported = AppResult.Err(AppError.Rejected("SOURCE_NOT_FOUND", "Source not found.")))
            val vm = vm(FakeEpisodes(), imports)
            advanceUntilIdle()
            vm.onIntent(EpisodeIntent.Prepare)
            advanceUntilIdle()
            assertEquals("This source isn’t on your account yet. Open Sources while online, then try again.", vm.state.value.error)
        }

    @Test
    fun ourOwnChaptersAreNeverCopied() =
        runTest(dispatcher) {
            val imports = Imports()
            val repo = FakeEpisodes()
            repo.runResult = { AppResult.Ok(PrepareResult.Queued(episode(EpisodeStatus.Queued))) }
            val vm = EpisodeViewModel("c1", repo, keys, imports = imports)
            advanceUntilIdle()
            assertEquals("c1", vm.state.value.chapterId)
            vm.onIntent(EpisodeIntent.Prepare)
            advanceUntilIdle()
            assertTrue(imports.asked.isEmpty())
        }

    private companion object {
        fun copy(
            status: ImportStatus,
            pages: Int = 0,
            error: String? = null,
        ) = ImportedChapter("title-1", "server-1", status, pages, error)
    }
}
