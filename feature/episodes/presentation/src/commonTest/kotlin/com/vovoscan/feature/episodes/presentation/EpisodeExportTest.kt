package com.vovoscan.feature.episodes.presentation

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.feature.episodes.domain.EpisodeExport
import com.vovoscan.feature.episodes.domain.EpisodeStatus
import com.vovoscan.feature.episodes.domain.ExportStatus
import com.vovoscan.feature.episodes.domain.KeyStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EpisodeExportTest {
    private val dispatcher = StandardTestDispatcher()
    private val keys = FakeKeyStatus(KeyStatus(true, "abcd", "9876"))

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun export(
        status: ExportStatus,
        progress: Int = 0,
        url: String? = null,
        error: String? = null,
    ) = EpisodeExport(status, progress, url, url?.replace("download=1&", ""), error, false)

    @Test
    fun noVideoSectionUntilTheEpisodeIsReady() =
        runTest(dispatcher) {
            val repo = FakeEpisodes(episode(EpisodeStatus.Voicing))
            val vm = EpisodeViewModel("c1", repo, keys)
            advanceUntilIdle()
            assertFalse(vm.state.value.exportShown)
            assertFalse(vm.state.value.canRequestExport)
            vm.onIntent(EpisodeIntent.RequestExport)
            advanceUntilIdle()
            assertEquals(0, repo.exportRequests)
        }

    @Test
    fun aReadyEpisodeOffersAVideoAndFollowsItsProgressLive() =
        runTest(dispatcher) {
            val repo = FakeEpisodes(episode(EpisodeStatus.Ready))
            val vm = EpisodeViewModel("c1", repo, keys)
            advanceUntilIdle()
            var state = vm.state.value
            assertTrue(state.exportShown)
            assertEquals("No video yet.", state.exportLabel)
            assertEquals("Make a video", state.exportActionLabel)
            assertTrue(state.canRequestExport)
            assertFalse(state.canSaveVideo)
            assertEquals(0, repo.follows)

            vm.onIntent(EpisodeIntent.RequestExport)
            advanceUntilIdle()
            state = vm.state.value
            assertEquals(1, repo.exportRequests)
            assertEquals("Video queued", state.exportLabel)
            assertEquals(0.0, state.exportProgress)
            assertFalse(state.canRequestExport)
            assertEquals("Video queued. It takes a few minutes; you can leave this screen.", state.notice)
            // A ready episode with its MP4 being made is followed live until the MP4 is done.
            assertEquals(1, repo.follows)
            assertTrue(state.live)

            repo.stored.value = episode(EpisodeStatus.Ready, export = export(ExportStatus.Rendering, 42))
            advanceUntilIdle()
            assertEquals("Making the video ✦ 42%", vm.state.value.exportLabel)
            assertEquals(0.42, vm.state.value.exportProgress)

            val url = "http://localhost:8000/api/v1/media/exports/ab.mp4?download=1&name=Le%20Griot.mp4&expires=1&signature=s"
            repo.stored.value = episode(EpisodeStatus.Ready, export = export(ExportStatus.Ready, 100, url))
            advanceUntilIdle()
            state = vm.state.value
            assertEquals("Video ready.", state.exportLabel)
            assertTrue(state.canSaveVideo)
            assertEquals("Save video", state.saveVideoLabel)
            assertNull(state.exportProgress)
        }

    @Test
    fun savingAVideoFetchesAFreshLinkAndHandsItToTheScreenOnce() =
        runTest(dispatcher) {
            val stale = "http://localhost:8000/api/v1/media/exports/ab.mp4?download=1&name=Old.mp4&expires=1&signature=old"
            val fresh =
                "http://localhost:8000/api/v1/media/exports/ab.mp4?download=1" +
                    "&name=Le%20Griot%20de%20Guerre%20-%20Chapter%201.mp4&expires=2&signature=new"
            val repo = FakeEpisodes(episode(EpisodeStatus.Ready, export = export(ExportStatus.Ready, 100, stale)))
            repo.exportResult = { AppResult.Ok(export(ExportStatus.Ready, 100, fresh)) }
            val vm = EpisodeViewModel("c1", repo, keys)
            advanceUntilIdle()
            vm.onIntent(EpisodeIntent.SaveVideo)
            assertEquals("Getting the video…", vm.state.value.saveVideoLabel)
            advanceUntilIdle()
            val download = assertNotNull(vm.state.value.videoDownload)
            assertEquals(fresh, download.url)
            assertEquals("Le Griot de Guerre - Chapter 1.mp4", download.fileName)
            assertEquals(1, repo.exportFetches)
            // While the screen has it, a second tap does nothing.
            vm.onIntent(EpisodeIntent.SaveVideo)
            advanceUntilIdle()
            assertEquals(1, repo.exportFetches)
            vm.onIntent(EpisodeIntent.VideoHandled)
            advanceUntilIdle()
            assertNull(vm.state.value.videoDownload)
            assertEquals("Save video", vm.state.value.saveVideoLabel)
            // A new request gets a new id: the screen starts it again.
            vm.onIntent(EpisodeIntent.SaveVideo)
            advanceUntilIdle()
            assertEquals(
                2,
                vm.state.value.videoDownload
                    ?.id,
            )
            vm.onIntent(EpisodeIntent.VideoFailed)
            advanceUntilIdle()
            assertNull(vm.state.value.videoDownload)
            assertEquals("The video couldn't be downloaded. Try again.", vm.state.value.error)
        }

    @Test
    fun aFailedVideoShowsTheServersErrorAndCanBeAskedAgain() =
        runTest(dispatcher) {
            val repo = FakeEpisodes(episode(EpisodeStatus.Ready, export = export(ExportStatus.Failed, error = "The encoder stopped.")))
            val vm = EpisodeViewModel("c1", repo, keys)
            advanceUntilIdle()
            val state = vm.state.value
            assertEquals("The video couldn't be made.", state.exportLabel)
            assertEquals("The encoder stopped.", state.exportDetail)
            assertEquals("Try again", state.exportActionLabel)
            assertTrue(state.canRequestExport)
        }

    @Test
    fun exportErrorsHaveCalmMessages() =
        runTest(dispatcher) {
            val repo = FakeEpisodes(episode(EpisodeStatus.Ready))
            val vm = EpisodeViewModel("c1", repo, keys)
            advanceUntilIdle()
            val cases =
                listOf(
                    AppError.RateLimited to "You can ask for 10 videos an hour. Try again later.",
                    AppError.Rejected("EPISODE_NOT_READY", "x") to "This episode isn't ready yet.",
                    AppError.Rejected("EPISODE_OUTDATED", "x") to "The chapter changed since this episode was prepared. Prepare it again.",
                    AppError.Network to "You're offline. Connect to the internet to get the video.",
                )
            for ((error, message) in cases) {
                repo.exportRequestResult = { AppResult.Err(error) }
                vm.onIntent(EpisodeIntent.RequestExport)
                advanceUntilIdle()
                assertEquals(message, vm.state.value.error)
                assertFalse(vm.state.value.exportWorking)
                assertTrue(vm.state.value.canRequestExport)
            }
            assertEquals(0, repo.follows)
        }

    @Test
    fun aVideoThatWentAwayAsksToMakeItAgain() =
        runTest(dispatcher) {
            val url = "http://x/a.mp4?download=1&expires=1&signature=s"
            val repo = FakeEpisodes(episode(EpisodeStatus.Ready, export = export(ExportStatus.Ready, 100, url)))
            repo.exportResult = { AppResult.Ok(export(ExportStatus.None)) }
            val vm = EpisodeViewModel("c1", repo, keys)
            advanceUntilIdle()
            vm.onIntent(EpisodeIntent.SaveVideo)
            advanceUntilIdle()
            assertNull(vm.state.value.videoDownload)
            assertEquals("This video isn't available anymore. Make it again.", vm.state.value.error)
            assertEquals("No video yet.", vm.state.value.exportLabel)
        }

    @Test
    fun videoFileNames() {
        assertEquals("vovoscan episode.mp4", EpisodeText.videoFileName("http://x/a.mp4?expires=1"))
        assertEquals("Les Heures - Chapitre 2.mp4", EpisodeText.videoFileName("http://x/a.mp4?name=Les+Heures+-+Chapitre+2.mp4"))
        assertEquals("Élan.mp4", EpisodeText.videoFileName("http://x/a.mp4?name=%C3%89lan"))
        assertEquals("a b.mp4", EpisodeText.videoFileName("http://x/a.mp4?name=a%2Fb.mp4"))
    }
}
