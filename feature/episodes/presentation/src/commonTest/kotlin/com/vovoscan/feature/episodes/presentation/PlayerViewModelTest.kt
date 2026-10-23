package com.vovoscan.feature.episodes.presentation

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.feature.episodes.domain.player.AudioKind
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val w = 390.0
    private val h = 693.0

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun repo(vararg results: AppResult<com.vovoscan.feature.episodes.domain.Watch>) =
        FakeEpisodes().apply { watchResults = ArrayDeque(results.toList()) }

    @Test
    fun loadsTheEpisodeAndShowsWhereItIs() =
        runTest(dispatcher) {
            val vm = PlayerViewModel("c1", null, repo(AppResult.Ok(watch())), { 0L })
            assertTrue(vm.state.value.loading)
            assertNull(vm.frame(0.0, w, h))
            advanceUntilIdle()
            val state = vm.state.value
            assertTrue(state.ready)
            assertFalse(state.loading)
            assertEquals(3000.0, state.durationMs)
            assertEquals("0:00 / 0:03", state.timeLabel)
            assertEquals("Page 1 / 2", state.pageLabel)
            assertEquals("Sound on", state.soundLabel)
            assertEquals(listOf("0.75×", "1×", "1.25×", "1.5×"), state.speeds.map { it.label })
            assertEquals(listOf("https://api.test/p0", "https://api.test/p1"), state.pageUrls)
            assertEquals("https://api.test/p0", assertNotNull(vm.frame(0.0, w, h)).shot.pageUrl)
        }

    @Test
    fun theNativeClockDrivesPlaybackAtTheChosenSpeed() =
        runTest(dispatcher) {
            val vm = PlayerViewModel("c1", null, repo(AppResult.Ok(watch())), { 0L })
            advanceUntilIdle()
            vm.onIntent(PlayerIntent.Play)
            vm.frame(10_000.0, w, h)
            vm.frame(10_100.0, w, h)
            assertEquals(100.0, assertNotNull(vm.frame(10_100.0, w, h)).timeMs)
            vm.onIntent(PlayerIntent.SetSpeed(1.5))
            assertEquals(250.0, assertNotNull(vm.frame(10_200.0, w, h)).timeMs)
            // A stalled frame never jumps more than a quarter of a second.
            assertEquals(625.0, assertNotNull(vm.frame(20_000.0, w, h)).timeMs)
            vm.onIntent(PlayerIntent.Pause)
            assertEquals(625.0, assertNotNull(vm.frame(30_000.0, w, h)).timeMs)
            vm.onIntent(PlayerIntent.SetSpeed(3.0))
            assertEquals(1.5, vm.state.value.speed)
        }

    @Test
    fun theEndSuggestsTheNextChapter() =
        runTest(dispatcher) {
            val vm = PlayerViewModel("c1", "c2", repo(AppResult.Ok(watch())), { 0L })
            advanceUntilIdle()
            vm.onIntent(PlayerIntent.Play)
            var t = 0.0
            while (!vm.state.value.ended && t < 10_000) {
                vm.frame(t, w, h)
                t += 16.0
            }
            val state = vm.state.value
            assertTrue(state.ended)
            assertFalse(state.playing)
            // The clock shows the whole length at the end ("0:03 / 0:03").
            assertEquals(state.durationMs, state.positionMs)
            assertEquals(2, state.beat)
            assertEquals("Next chapter →", state.nextLabel)
            assertEquals("End of chapter", state.endTitle)
            vm.onIntent(PlayerIntent.Replay)
            assertTrue(vm.state.value.playing)
            assertEquals(0, vm.state.value.beat)
            assertNull(vm.state.value.nextLabel)
        }

    @Test
    fun fromTheReaderItStartsOnThePageBeingRead() =
        runTest(dispatcher) {
            val vm = PlayerViewModel("c1", null, repo(AppResult.Ok(watch())), { 0L }, startPage = 1)
            advanceUntilIdle()
            assertEquals(2, vm.state.value.beat)
            assertEquals("Page 2 / 2", vm.state.value.pageLabel)
        }

    @Test
    fun beatsAndPagesNavigate() =
        runTest(dispatcher) {
            val vm = PlayerViewModel("c1", null, repo(AppResult.Ok(watch())), { 0L })
            advanceUntilIdle()
            vm.onIntent(PlayerIntent.NextPage)
            assertEquals(2, vm.state.value.beat)
            assertEquals("Page 2 / 2", vm.state.value.pageLabel)
            assertEquals("https://api.test/p1", assertNotNull(vm.frame(0.0, w, h)).shot.pageUrl)
            vm.onIntent(PlayerIntent.PreviousPage)
            assertEquals(0, vm.state.value.beat)
            vm.onIntent(PlayerIntent.NextBeat)
            assertEquals(1, vm.state.value.beat)
            assertEquals(1000.0, vm.state.value.positionMs)
            vm.onIntent(PlayerIntent.PreviousBeat)
            assertEquals(0, vm.state.value.beat)
            vm.onIntent(PlayerIntent.SeekTo(2_500.0))
            assertEquals(2, vm.state.value.beat)
            vm.onIntent(PlayerIntent.SeekToBeat(1))
            assertEquals(1, vm.state.value.beat)
        }

    @Test
    fun theFrameSaysWhatToPlayAndMuteSilencesIt() =
        runTest(dispatcher) {
            val vm = PlayerViewModel("c1", null, repo(AppResult.Ok(watch())), { 0L })
            advanceUntilIdle()
            vm.onIntent(PlayerIntent.SeekTo(1_200.0))
            val audio = assertNotNull(vm.frame(0.0, w, h)).audio
            assertEquals(setOf(AudioKind.Voice, AudioKind.Bed), audio.map { it.kind }.toSet())
            assertEquals("https://api.test/line", audio.single { it.kind == AudioKind.Voice }.url)
            assertEquals(200.0, audio.single { it.kind == AudioKind.Voice }.offsetMs)
            assertTrue(audio.all { it.gain > 0 })
            vm.onIntent(PlayerIntent.ToggleMute)
            assertEquals("Sound off", vm.state.value.soundLabel)
            assertTrue(assertNotNull(vm.frame(0.0, w, h)).audio.all { it.gain == 0.0 })
        }

    @Test
    fun freshUrlsBeforeTheyExpireOrWhenMediaFails() =
        runTest(dispatcher) {
            val now = 1_000_000L
            val repo =
                repo(
                    AppResult.Ok(watch(expiresAtMillis = now + 10 * 60_000L)),
                    AppResult.Ok(watch(expiresAtMillis = now + 60 * 60_000L)),
                    AppResult.Ok(watch(expiresAtMillis = now + 60 * 60_000L)),
                )
            val vm = PlayerViewModel("c1", null, repo, { now })
            runCurrent()
            vm.onIntent(PlayerIntent.SeekTo(1_500.0))
            assertEquals(listOf(false), repo.watchCalls)
            // Five minutes before the URLs expire, the watch is fetched again; playback stays where it was.
            advanceTimeBy(5 * 60_000L + 1)
            assertEquals(listOf(false, true), repo.watchCalls)
            assertEquals(1_500.0, vm.state.value.positionMs)
            vm.onIntent(PlayerIntent.MediaFailed("https://api.test/line"))
            vm.onIntent(PlayerIntent.MediaFailed("https://api.test/bed"))
            runCurrent()
            assertEquals(listOf(false, true, true), repo.watchCalls)
        }

    @Test
    fun nothingToPlay() =
        runTest(dispatcher) {
            val vm = PlayerViewModel("c1", null, repo(AppResult.Err(AppError.Rejected("EPISODE_NOT_READY", "x"))), { 0L })
            advanceUntilIdle()
            assertFalse(vm.state.value.loading)
            assertFalse(vm.state.value.ready)
            assertEquals("This episode isn't ready yet.", vm.state.value.error)
            val outdated = PlayerViewModel("c1", null, repo(AppResult.Err(AppError.Rejected("EPISODE_OUTDATED", "x"))), { 0L })
            advanceUntilIdle()
            assertEquals("The chapter changed since this episode was prepared. Prepare it again.", outdated.state.value.error)
            val offline = PlayerViewModel("c1", null, repo(AppResult.Err(AppError.Network), AppResult.Ok(watch())), { 0L })
            advanceUntilIdle()
            assertEquals("You're offline. An episode plays offline once it has been opened online.", offline.state.value.error)
            offline.onIntent(PlayerIntent.Retry)
            advanceUntilIdle()
            assertTrue(offline.state.value.ready)
            assertNull(offline.state.value.error)
        }

    private fun language(
        status: com.vovoscan.feature.episodes.domain.EpisodeStatus,
        code: String = "fr",
        progress: com.vovoscan.feature.episodes.domain.StageProgress? = null,
    ) = episode(status, progress = progress).copy(language = code)

    @Test
    fun aLanguageAlreadyMadeSwitchesAtOnceAtTheSamePageAndKeepsPlaying() =
        runTest(dispatcher) {
            val repo = repo(AppResult.Ok(watch()), AppResult.Ok(watch(renderKey = "rk-fr")))
            repo.inLanguage["fr"] =
                kotlinx.coroutines.flow.MutableStateFlow(language(com.vovoscan.feature.episodes.domain.EpisodeStatus.Ready))
            val vm = PlayerViewModel("c1", null, repo, { 0L })
            advanceUntilIdle()
            assertEquals("Original", vm.state.value.languageLabel)
            vm.onIntent(PlayerIntent.SeekToBeat(2))
            vm.onIntent(PlayerIntent.Play)
            vm.onIntent(PlayerIntent.ChooseLanguage("fr"))
            advanceUntilIdle()
            val state = vm.state.value
            assertNull(state.languageDock)
            assertEquals("fr", state.language)
            assertEquals("Français", state.languageLabel)
            assertTrue(state.playing)
            assertEquals("rk-fr", vm.plan?.watch?.renderKey)
            // Page 2 (chapter page 1) was playing: the new language starts there.
            assertEquals(2000.0, state.positionMs)
            assertEquals(listOf("", "fr", "fr"), repo.languages)
        }

    @Test
    fun aNewLanguageIsQuotedThenMadeWhileTheEpisodePlaysThenSwitches() =
        runTest(dispatcher) {
            val repo = repo(AppResult.Ok(watch()), AppResult.Ok(watch(renderKey = "rk-yo")))
            val quotes =
                com.vovoscan.feature.episodes.domain.RunQuotes { _, _ ->
                    com.vovoscan.feature.episodes.domain.RunCost(
                        credits = 30,
                        coveredByOwnKeys = false,
                        alreadyAvailable = false,
                        maxCredits = 40,
                        metered = setOf("anthropic"),
                    )
                }
            val queued = language(com.vovoscan.feature.episodes.domain.EpisodeStatus.Queued, "yo")
            repo.runResult = {
                AppResult.Ok(
                    com.vovoscan.feature.episodes.domain.PrepareResult
                        .Queued(queued),
                )
            }
            val vm = PlayerViewModel("c1", null, repo, { 0L }, quotes = quotes)
            advanceUntilIdle()
            vm.onIntent(PlayerIntent.Play)
            vm.onIntent(PlayerIntent.ChooseLanguage("yo"))
            advanceUntilIdle()
            val offer = assertNotNull(vm.state.value.languageDock)
            assertEquals("Watch it in Yorùbá?", offer.title)
            assertEquals("Costs 30 credits from your wallet.", offer.line)
            assertEquals("Make it", offer.startLabel)
            assertEquals("Add your own key", offer.addKeyLabel)
            assertTrue(repo.runs.isEmpty())
            vm.onIntent(PlayerIntent.StartLanguage)
            runCurrent()
            assertEquals(listOf(false), repo.runs)
            repo.inLanguage.getValue("yo").value =
                language(
                    com.vovoscan.feature.episodes.domain.EpisodeStatus.Reading,
                    "yo",
                    com.vovoscan.feature.episodes.domain
                        .StageProgress("OCR", "pages", 6, 12),
                )
            runCurrent()
            val making = assertNotNull(vm.state.value.languageDock)
            assertEquals("Making it in Yorùbá", making.title)
            // OCR is the second of five stages, half done: 30 %.
            assertEquals(0.3f, assertNotNull(making.progress), 0.001f)
            assertTrue(vm.state.value.playing)
            assertEquals("", vm.state.value.language)
            repo.followEnd.complete(AppResult.Ok(language(com.vovoscan.feature.episodes.domain.EpisodeStatus.Ready, "yo")))
            advanceUntilIdle()
            assertNull(vm.state.value.languageDock)
            assertEquals("yo", vm.state.value.language)
            assertEquals("rk-yo", vm.plan?.watch?.renderKey)
        }

    @Test
    fun nothingToPayWithSaysWhyAndLeadsToTheKeys() =
        runTest(dispatcher) {
            val quotes =
                com.vovoscan.feature.episodes.domain.RunQuotes { _, _ ->
                    com.vovoscan.feature.episodes.domain.RunCost(
                        credits = 30,
                        coveredByOwnKeys = false,
                        alreadyAvailable = false,
                        maxCredits = 40,
                        fundable = false,
                    )
                }
            val repo = repo(AppResult.Ok(watch()))
            val vm = PlayerViewModel("c1", null, repo, { 0L }, quotes = quotes)
            advanceUntilIdle()
            vm.onIntent(PlayerIntent.ChooseLanguage("yo"))
            advanceUntilIdle()
            val dock = assertNotNull(vm.state.value.languageDock)
            assertNull(dock.startLabel)
            assertEquals("Add your own key", dock.addKeyLabel)
            assertEquals("Nothing can pay for it yet. Add your own key (no credits needed), or ask the owner for credits.", dock.line)
            vm.onIntent(PlayerIntent.StartLanguage)
            advanceUntilIdle()
            assertTrue(repo.runs.isEmpty())
            vm.onIntent(PlayerIntent.CloseLanguageDock)
            assertNull(vm.state.value.languageDock)
            assertEquals("", vm.state.value.language)
        }

    @Test
    fun aFailedRunSaysSoInTheDockWithTryAgain() =
        runTest(dispatcher) {
            val repo = repo(AppResult.Ok(watch()))
            repo.runResult =
                {
                    AppResult.Ok(
                        com.vovoscan.feature.episodes.domain.PrepareResult.Queued(
                            language(com.vovoscan.feature.episodes.domain.EpisodeStatus.Queued, "yo"),
                        ),
                    )
                }
            val vm = PlayerViewModel("c1", null, repo, { 0L })
            advanceUntilIdle()
            vm.onIntent(PlayerIntent.ChooseLanguage("yo"))
            advanceUntilIdle()
            vm.onIntent(PlayerIntent.StartLanguage)
            runCurrent()
            repo.followEnd.complete(
                AppResult.Ok(episode(com.vovoscan.feature.episodes.domain.EpisodeStatus.Failed, error = "The voices couldn't be made.")),
            )
            advanceUntilIdle()
            val dock = assertNotNull(vm.state.value.languageDock)
            assertTrue(dock.failed)
            assertEquals("The voices couldn't be made.", dock.line)
            assertEquals("Try again", dock.retryLabel)
            assertEquals("", vm.state.value.language)
        }
}
