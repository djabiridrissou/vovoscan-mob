package com.vovoscan.feature.reader.presentation

import com.vovoscan.feature.reader.domain.ChapterDownload
import com.vovoscan.feature.reader.domain.DownloadBlock
import com.vovoscan.feature.reader.domain.DownloadState
import com.vovoscan.feature.reader.domain.DownloadableChapter
import com.vovoscan.feature.reader.domain.DownloadableTitle
import com.vovoscan.feature.reader.domain.DownloadsRepository
import com.vovoscan.feature.reader.domain.TitleChapters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
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
class DownloadsViewModelsTest {
    private val dispatcher = StandardTestDispatcher()

    private class FakeDownloads : DownloadsRepository {
        val all = MutableStateFlow<List<ChapterDownload>>(emptyList())
        val block = MutableStateFlow<DownloadBlock?>(null)
        val wifiOnly = MutableStateFlow(true)
        val calls = mutableListOf<String>()

        override fun observeAll(): Flow<List<ChapterDownload>> = all

        override fun observeTitle(
            sourceKey: String,
            titleId: String,
        ): Flow<List<ChapterDownload>> = all.map { list -> list.filter { it.sourceKey == sourceKey && it.titleId == titleId } }

        override fun observeBlock(): Flow<DownloadBlock?> = block

        override fun observeWifiOnly(): Flow<Boolean> = wifiOnly

        override suspend fun setWifiOnly(enabled: Boolean) {
            wifiOnly.value = enabled
        }

        override suspend fun enqueue(
            title: DownloadableTitle,
            chapters: List<DownloadableChapter>,
        ) {
            calls += "enqueue " + chapters.joinToString(",") { it.id }
        }

        override suspend fun remove(
            sourceKey: String,
            chapterId: String,
        ) {
            calls += "remove $chapterId"
        }

        override suspend fun removeTitle(
            sourceKey: String,
            titleId: String,
        ) {
            calls += "removeTitle $titleId"
        }

        override suspend fun retry(
            sourceKey: String,
            chapterId: String,
        ) {
            calls += "retry $chapterId"
        }

        override fun resume() = Unit

        override suspend fun clearLocal() = Unit

        override suspend fun clearSource(sourceKey: String) = Unit
    }

    private val chapters = (1..8).map { DownloadableChapter("c$it", it.toDouble(), "Chapter $it") }
    private val griot = DownloadableTitle("vovoscan", "t1", "Le Griot de Guerre", false, chapters, resumeChapterId = "c3")

    private fun download(
        chapterId: String,
        state: DownloadState,
        done: Int = 0,
        count: Int = 0,
        bytes: Long = 0,
        titleId: String = "t1",
        titleName: String = "Le Griot de Guerre",
        isBook: Boolean = false,
        sourceKey: String = "vovoscan",
    ) = ChapterDownload(
        sourceKey,
        titleId,
        titleName,
        chapterId,
        "Chapter ${chapterId.drop(1)}",
        chapterId.drop(1).toDoubleOrNull() ?: 1.0,
        state,
        done,
        count,
        bytes,
        null,
        isBook,
    )

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun titlePageRowsSayWhereEachChapterIs() =
        runTest(dispatcher) {
            val repo = FakeDownloads()
            repo.all.value =
                listOf(
                    download("c3", DownloadState.Done, 10, 10, 4_200_000),
                    download("c4", DownloadState.Downloading, 3, 10),
                    download("c6", DownloadState.Failed),
                )
            val vm = TitleDownloadsViewModel("vovoscan", "t1", repo, TitleChapters { _, _ -> MutableStateFlow(griot) })
            advanceUntilIdle()
            val state = vm.state.value
            assertTrue(state.offersDownloads)
            assertEquals(DownloadStatus.Done to DownloadAction.Delete, state.row("c3").status to state.row("c3").action)
            assertEquals("Downloaded ✦ 4.2 MB", state.row("c3").text)
            assertEquals("Downloading ✦ 3 / 10", state.row("c4").text)
            assertEquals(0.3f, state.row("c4").progress)
            assertEquals(DownloadAction.Cancel, state.row("c4").action)
            assertEquals(DownloadAction.Retry to true, state.row("c6").action to state.row("c6").removable)
            assertEquals(DownloadStatus.NotDownloaded to DownloadAction.Download, state.row("c1").status to state.row("c1").action)
            assertEquals("1 of 8 downloaded ✦ 1 in the queue ✦ 4.2 MB", state.summary)
            // From the chapter being read (c3): c5, c7, c8 are left (c3, c4, c6 are taken).
            assertEquals("Download next 3", state.downloadNextLabel)
            assertEquals("Download all (5)", state.downloadAllLabel)
            assertTrue(state.canDeleteAll)
            assertNull(state.notice)
        }

    @Test
    fun titlePageActionsGoToTheQueue() =
        runTest(dispatcher) {
            val repo = FakeDownloads()
            repo.all.value =
                listOf(download("c3", DownloadState.Done), download("c4", DownloadState.Queued), download("c6", DownloadState.Failed))
            val vm = TitleDownloadsViewModel("vovoscan", "t1", repo, TitleChapters { _, _ -> MutableStateFlow(griot) })
            advanceUntilIdle()
            vm.onIntent(TitleDownloadsIntent.DownloadNext)
            vm.onIntent(TitleDownloadsIntent.DownloadAll)
            vm.onIntent(TitleDownloadsIntent.Act("c1"))
            vm.onIntent(TitleDownloadsIntent.Act("c3"))
            vm.onIntent(TitleDownloadsIntent.Act("c4"))
            vm.onIntent(TitleDownloadsIntent.Act("c6"))
            vm.onIntent(TitleDownloadsIntent.Remove("c6"))
            vm.onIntent(TitleDownloadsIntent.DeleteAll)
            advanceUntilIdle()
            assertEquals(
                listOf(
                    "enqueue c5,c7,c8",
                    "enqueue c1,c2,c5,c7,c8",
                    "enqueue c1",
                    "remove c3",
                    "remove c4",
                    "retry c6",
                    "remove c6",
                    "removeTitle t1",
                ),
                repo.calls,
            )
        }

    @Test
    fun withoutProgressNextStartsAtTheFirstChapter() =
        runTest(dispatcher) {
            val vm =
                TitleDownloadsViewModel(
                    "vovoscan",
                    "t1",
                    FakeDownloads(),
                    TitleChapters {
                        _,
                        _,
                        ->
                        MutableStateFlow(griot.copy(resumeChapterId = null))
                    },
                )
            advanceUntilIdle()
            assertEquals("Download next 5", vm.state.value.downloadNextLabel)
            assertEquals("Download all (8)", vm.state.value.downloadAllLabel)
            assertNull(vm.state.value.summary)
            assertFalse(vm.state.value.canDeleteAll)
        }

    @Test
    fun aWaitingQueueSaysWhy() =
        runTest(dispatcher) {
            val repo = FakeDownloads()
            repo.all.value = listOf(download("c4", DownloadState.Waiting))
            repo.block.value = DownloadBlock.NotOnWifi
            val vm = TitleDownloadsViewModel("vovoscan", "t1", repo, TitleChapters { _, _ -> MutableStateFlow(griot) })
            advanceUntilIdle()
            assertEquals(
                "Waiting for Wi-Fi",
                vm.state.value
                    .row("c4")
                    .text,
            )
            assertEquals("Downloads wait for Wi-Fi. Turn off “Wi-Fi only” in Downloads to use mobile data.", vm.state.value.notice)
            repo.block.value = DownloadBlock.NoConnection
            advanceUntilIdle()
            assertEquals(
                "Waiting for a connection",
                vm.state.value
                    .row("c4")
                    .text,
            )
        }

    @Test
    fun aBookIsShownAsSavedOnceOpened() =
        runTest(dispatcher) {
            val repo = FakeDownloads()
            val book =
                DownloadableTitle(
                    "gutenberg",
                    "1342",
                    "Pride and Prejudice",
                    true,
                    listOf(DownloadableChapter("1342", 1.0, "Full text")),
                    null,
                )
            val vm = TitleDownloadsViewModel("gutenberg", "1342", repo, TitleChapters { _, _ -> MutableStateFlow(book) })
            advanceUntilIdle()
            assertFalse(vm.state.value.offersDownloads)
            assertEquals(
                DownloadAction.Unavailable,
                vm.state.value
                    .row("1342")
                    .action,
            )
            assertNull(vm.state.value.downloadNextLabel)

            repo.all.value =
                listOf(download("1342", DownloadState.Done, bytes = 820_000, titleId = "1342", isBook = true, sourceKey = "gutenberg"))
            advanceUntilIdle()
            assertEquals(
                "Saved on this device ✦ 820 KB",
                vm.state.value
                    .row("1342")
                    .text,
            )
            assertEquals("Saved on this device ✦ 820 KB", vm.state.value.summary)
            assertEquals(
                DownloadAction.Delete,
                vm.state.value
                    .row("1342")
                    .action,
            )
        }

    @Test
    fun downloadsScreenGroupsByTitle() =
        runTest(dispatcher) {
            val repo = FakeDownloads()
            repo.all.value =
                listOf(
                    download("c2", DownloadState.Done, bytes = 3_000_000),
                    download("c1", DownloadState.Done, bytes = 2_000_000),
                    download("c9", DownloadState.Queued, titleId = "t2", titleName = "Reaper of the Drifting Moon"),
                    download(
                        "1342",
                        DownloadState.Done,
                        bytes = 1_500_000,
                        titleId = "1342",
                        titleName = "Pride and Prejudice",
                        isBook = true,
                    ),
                )
            val vm = DownloadsViewModel(repo)
            advanceUntilIdle()
            val state = vm.state.value
            assertEquals(
                listOf("Le Griot de Guerre", "Pride and Prejudice", "Reaper of the Drifting Moon"),
                state.groups.map { it.titleName },
            )
            assertEquals("2 chapters ✦ 5.0 MB", state.groups[0].meta)
            assertEquals(listOf("c1", "c2"), state.groups[0].rows.map { it.chapterId })
            assertEquals("Book ✦ 1.5 MB", state.groups[1].meta)
            assertEquals("6.5 MB on this device", state.total)
            assertEquals("Downloads ✦ 6.5 MB", state.entryLabel)
            assertTrue(state.wifiOnly)

            vm.onIntent(DownloadsIntent.SetWifiOnly(false))
            vm.onIntent(DownloadsIntent.Act("vovoscan", "c9"))
            vm.onIntent(DownloadsIntent.DeleteTitle("vovoscan", "t1"))
            advanceUntilIdle()
            assertFalse(vm.state.value.wifiOnly)
            assertEquals(listOf("remove c9", "removeTitle t1"), repo.calls)
        }

    @Test
    fun nothingDownloadedYet() =
        runTest(dispatcher) {
            val vm = DownloadsViewModel(FakeDownloads())
            advanceUntilIdle()
            assertTrue(vm.state.value.empty)
            assertEquals("Downloads", vm.state.value.entryLabel)
        }

    @Test
    fun sizesReadLikeTheSystemsShowThem() {
        assertEquals("1 KB", DownloadText.size(10))
        assertEquals("820 KB", DownloadText.size(820_000))
        assertEquals("4.2 MB", DownloadText.size(4_230_000))
        assertEquals("42 MB", DownloadText.size(42_400_000))
        assertEquals("1.3 GB", DownloadText.size(1_300_000_000))
    }
}
