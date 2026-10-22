package com.vovoscan.feature.episodes.presentation

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.feature.episodes.domain.EpisodeAccess
import com.vovoscan.feature.episodes.domain.EpisodeStatus
import com.vovoscan.feature.episodes.domain.KeyStatus
import com.vovoscan.feature.episodes.domain.PipelineStage
import com.vovoscan.feature.episodes.domain.PrepareResult
import com.vovoscan.feature.episodes.domain.RunCost
import com.vovoscan.feature.episodes.domain.RunQuotes
import com.vovoscan.feature.episodes.domain.StageProgress
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

@OptIn(ExperimentalCoroutinesApi::class)
class EpisodeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val bothKeys = FakeKeyStatus(KeyStatus(true, "abcd", "9876"))

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun comingSoonOffersNothingUntilTheAccountGetsTheFeature() =
        runTest(dispatcher) {
            val open = MutableStateFlow(false)
            val repo = FakeEpisodes(episode(EpisodeStatus.Ready))
            val vm = EpisodeViewModel("c1", repo, bothKeys, EpisodeAccess { open })
            advanceUntilIdle()
            var state = vm.state.value
            assertTrue(state.comingSoon)
            assertEquals("Episodes are coming soon.", state.headline)
            assertFalse(state.canWatch)
            assertFalse(state.canPrepare)
            assertFalse(state.canReread)
            assertFalse(state.exportShown)
            assertTrue(state.keyLines.isEmpty())
            vm.onIntent(EpisodeIntent.Prepare)
            advanceUntilIdle()
            assertTrue(repo.runs.isEmpty())
            open.value = true
            vm.onIntent(EpisodeIntent.Start)
            advanceUntilIdle()
            state = vm.state.value
            assertFalse(state.comingSoon)
            assertTrue(state.canWatch)
        }

    @Test
    fun theServerTurningTheFeatureOffShowsComingSoon() =
        runTest(dispatcher) {
            val repo = FakeEpisodes()
            repo.refreshResult = AppResult.Err(AppError.Rejected("FEATURE_DISABLED", "Coming soon."))
            val vm = EpisodeViewModel("c1", repo, bothKeys)
            advanceUntilIdle()
            assertTrue(vm.state.value.comingSoon)
        }

    @Test
    fun theQuoteShowsNextToPrepare() =
        runTest(dispatcher) {
            val vm = EpisodeViewModel("c1", FakeEpisodes(), bothKeys, EpisodeAccess.Open, RunQuotes { RunCost(12, false, false) })
            advanceUntilIdle()
            assertEquals("Costs 12 credits from your wallet.", vm.state.value.costLine)
            val own = EpisodeViewModel("c2", FakeEpisodes(), bothKeys, EpisodeAccess.Open, RunQuotes { RunCost(12, true, false) })
            advanceUntilIdle()
            assertEquals("Your own keys pay: no credit spent.", own.state.value.costLine)
        }

    @Test
    fun anEmptyWalletSaysHowToGoOn() =
        runTest(dispatcher) {
            val repo = FakeEpisodes()
            repo.runResult = { AppResult.Err(AppError.Rejected("INSUFFICIENT_CREDITS", "Not enough credits.")) }
            val vm = EpisodeViewModel("c1", repo, bothKeys)
            advanceUntilIdle()
            vm.onIntent(EpisodeIntent.Prepare)
            advanceUntilIdle()
            assertEquals(EpisodeText.NO_CREDITS, vm.state.value.error)
        }

    @Test
    fun noEpisodeYetOffersToPrepareWithTheUsersKeys() =
        runTest(dispatcher) {
            val keys = FakeKeyStatus(KeyStatus(true, "abcd", null))
            val vm = EpisodeViewModel("c1", FakeEpisodes(), keys)
            advanceUntilIdle()
            val state = vm.state.value
            assertEquals(EpisodePhase.None, state.phase)
            assertEquals("No episode yet.", state.headline)
            assertTrue(state.canPrepare)
            assertEquals("Prepare episode", state.prepareLabel)
            assertTrue(state.showsKeyNotice)
            assertEquals(
                listOf("Your Anthropic key (•••• abcd) reads the bubbles.", "No ElevenLabs key: the episode stays silent."),
                state.keyLines,
            )
            assertEquals("Add the missing key in Settings.", state.keyWarning)
            assertEquals(1, keys.refreshes)
            keys.status.value = KeyStatus(true, "abcd", "9876")
            advanceUntilIdle()
            assertNull(vm.state.value.keyWarning)
        }

    @Test
    fun preparingStartsARunAndFollowsItLive() =
        runTest(dispatcher) {
            val repo = FakeEpisodes()
            repo.runResult =
                { AppResult.Ok(PrepareResult.Queued(episode(EpisodeStatus.Queued, StageProgress("ANALYZE", "queued", null, null)))) }
            val vm = EpisodeViewModel("c1", repo, bothKeys)
            advanceUntilIdle()
            vm.onIntent(EpisodeIntent.Prepare)
            advanceUntilIdle()
            var state = vm.state.value
            assertEquals(EpisodePhase.Running, state.phase)
            assertEquals("Preparing the episode ✦ ANALYZE", state.headline)
            assertEquals("Queued", state.progressLabel)
            assertEquals(listOf("ANALYZE", "OCR", "VOICE", "TIMELINE"), state.stages.map { it.label })
            assertEquals(StageState.Active, state.stages[0].state)
            assertEquals("Started. Your keys pay for this run; everyone can watch the result.", state.notice)
            assertTrue(state.live)
            assertEquals(1, repo.follows)
            // Live progress arrives through the local copy.
            repo.stored.value = episode(EpisodeStatus.Reading, StageProgress("OCR", "pages", 3, 12))
            advanceUntilIdle()
            state = vm.state.value
            assertEquals("3 / 12 pages", state.progressLabel)
            assertEquals(0.25, state.progressFraction)
            assertEquals(listOf(StageState.Done, StageState.Active, StageState.Waiting, StageState.Waiting), state.stages.map { it.state })
            assertEquals(1, repo.follows)
            repo.stored.value = episode(EpisodeStatus.Ready)
            repo.followEnd.complete(AppResult.Ok(repo.stored.value))
            advanceUntilIdle()
            assertEquals(EpisodePhase.Ready, vm.state.value.phase)
            assertTrue(vm.state.value.canWatch)
            assertFalse(vm.state.value.live)
        }

    @Test
    fun aRunAlreadyInProgressIsFollowedNotRestarted() =
        runTest(dispatcher) {
            val repo = FakeEpisodes()
            repo.runResult = { AppResult.Ok(PrepareResult.Busy(episode(EpisodeStatus.Voicing, StageProgress("VOICE", "lines", 4, 30)))) }
            val vm = EpisodeViewModel("c1", repo, bothKeys)
            advanceUntilIdle()
            vm.onIntent(EpisodeIntent.Prepare)
            advanceUntilIdle()
            assertEquals("Already in progress. Following it.", vm.state.value.notice)
            assertEquals("4 / 30 lines", vm.state.value.progressLabel)
            assertEquals(
                PipelineStage.Voice,
                vm.state.value.stages
                    .single { it.state == StageState.Active }
                    .stage,
            )
            assertEquals(1, repo.follows)
        }

    @Test
    fun nobodyPaysTwice() =
        runTest(dispatcher) {
            val repo = FakeEpisodes()
            repo.runResult = { AppResult.Ok(PrepareResult.AlreadyReady(episode(EpisodeStatus.Ready))) }
            val vm = EpisodeViewModel("c1", repo, bothKeys)
            advanceUntilIdle()
            vm.onIntent(EpisodeIntent.Prepare)
            advanceUntilIdle()
            assertEquals("Ready. It was already made, so nothing was charged.", vm.state.value.notice)
            assertEquals(EpisodePhase.Ready, vm.state.value.phase)
            assertEquals(0, repo.follows)
        }

    @Test
    fun aReadyEpisodeShowsWhatItLacksAndWhoCanImproveIt() =
        runTest(dispatcher) {
            val repo = FakeEpisodes(episode(EpisodeStatus.Ready, degraded = true, silent = true, canReread = true))
            val vm = EpisodeViewModel("c1", repo, bothKeys)
            advanceUntilIdle()
            var state = vm.state.value
            assertEquals(listOf("Degraded", "Silent"), state.badges)
            assertEquals(
                listOf(
                    "Some bubbles weren't read or voiced.",
                    "No voices: it was prepared without an ElevenLabs key.",
                    "Your keys can improve it.",
                ),
                state.notes,
            )
            assertTrue(state.canReread)
            assertFalse(state.canPrepare)
            vm.onIntent(EpisodeIntent.Reread)
            advanceUntilIdle()
            assertEquals(listOf(true), repo.runs)
            // Outdated: prepare again (the server rebuilds it), no rerun of the old render.
            repo.stored.value = episode(EpisodeStatus.Ready, outdated = true, canReread = true)
            repo.followEnd.complete(AppResult.Ok(null))
            advanceUntilIdle()
            state = vm.state.value
            assertEquals(listOf("Outdated"), state.badges)
            assertTrue(state.canPrepare)
            assertEquals("Prepare again", state.prepareLabel)
            assertFalse(state.canReread)
        }

    @Test
    fun aFailedRunCanBeTriedAgain() =
        runTest(dispatcher) {
            val repo = FakeEpisodes(episode(EpisodeStatus.Failed, error = "Processing failed after several attempts.", render = false))
            val vm = EpisodeViewModel("c1", repo, bothKeys)
            advanceUntilIdle()
            val state = vm.state.value
            assertEquals(EpisodePhase.Failed, state.phase)
            assertEquals("Processing failed after several attempts.", state.detail)
            assertEquals("Try again", state.prepareLabel)
            assertTrue(state.canPrepare)
            assertFalse(state.canWatch)
        }

    @Test
    fun aBookHidesTheFeature() =
        runTest(dispatcher) {
            val repo =
                FakeEpisodes().apply {
                    refreshResult =
                        AppResult.Err(AppError.Rejected("EPISODE_NOT_SUPPORTED", "Only comics become episodes."))
                }
            val vm = EpisodeViewModel("c1", repo, bothKeys)
            advanceUntilIdle()
            assertFalse(vm.state.value.visible)
            assertEquals(EpisodePhase.Unsupported, vm.state.value.phase)
        }

    @Test
    fun followingStopsWithTheScreen() =
        runTest(dispatcher) {
            val repo = FakeEpisodes(episode(EpisodeStatus.Timing))
            val vm = EpisodeViewModel("c1", repo, bothKeys)
            advanceUntilIdle()
            assertEquals(1, repo.follows)
            vm.onIntent(EpisodeIntent.Stop)
            advanceUntilIdle()
            assertEquals(1, repo.cancelledFollows)
            assertFalse(vm.state.value.live)
            vm.onIntent(EpisodeIntent.Start)
            advanceUntilIdle()
            assertEquals(2, repo.follows)
            assertTrue(vm.state.value.live)
        }

    @Test
    fun offlineOnAFirstVisitAndOfflineWrites() =
        runTest(dispatcher) {
            val repo = FakeEpisodes().apply { refreshResult = AppResult.Err(AppError.Network) }
            val vm = EpisodeViewModel("c1", repo, bothKeys)
            advanceUntilIdle()
            assertEquals(EpisodePhase.Unavailable, vm.state.value.phase)
            assertEquals("You're offline. The episode shows once you're back online.", vm.state.value.headline)
            assertFalse(vm.state.value.canPrepare)
            // Back online with no episode: Prepare, but the request fails offline.
            repo.refreshResult = null
            repo.runResult = { AppResult.Err(AppError.Network) }
            vm.onIntent(EpisodeIntent.Refresh)
            advanceUntilIdle()
            vm.onIntent(EpisodeIntent.Prepare)
            advanceUntilIdle()
            assertEquals("You're offline. Connect to the internet to prepare this episode.", vm.state.value.error)
            assertEquals(EpisodePhase.None, vm.state.value.phase)
        }

    @Test
    fun keysTurnedOffOnTheServer() =
        runTest(dispatcher) {
            val vm = EpisodeViewModel("c1", FakeEpisodes(), FakeKeyStatus(KeyStatus(false, null, null)))
            advanceUntilIdle()
            assertEquals(listOf("Keys are turned off on this server: the episode will be unread and silent."), vm.state.value.keyLines)
            assertNull(vm.state.value.keyWarning)
        }
}
