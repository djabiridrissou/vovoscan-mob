package com.vovoscan.feature.reader.data

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.Clock
import com.vovoscan.core.common.NetworkMonitor
import com.vovoscan.core.common.NetworkState
import com.vovoscan.core.common.userMessage
import com.vovoscan.feature.reader.domain.ChapterDownload
import com.vovoscan.feature.reader.domain.DownloadActivity
import com.vovoscan.feature.reader.domain.DownloadBlock
import com.vovoscan.feature.reader.domain.DownloadScheduler
import com.vovoscan.feature.reader.domain.DownloadState
import com.vovoscan.feature.reader.domain.DownloadableChapter
import com.vovoscan.feature.reader.domain.DownloadableTitle
import com.vovoscan.feature.reader.domain.DownloadsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The download queue, run in the app's scope: one chapter at a time, page by page, each page written to disk then
 * recorded (a chapter interrupted halfway resumes at its first missing page). A book is one step: its file fetched,
 * read into sections and saved, as opening it does. Every change to a chapter's rows and files happens under one
 * lock, so a chapter removed while it downloads leaves nothing behind.
 *
 * Network: with no connection, or on mobile data with "Wi-Fi only", the chapter waits and starts again by itself.
 * A failure while online (source down, expired link) marks it failed; "Try again" queues it again.
 *
 * Background: whenever there is work, [scheduler] asks the platform to keep it going (Android WorkManager); the
 * platform's background work only calls [drain] and, before the app is suspended, [pause].
 */
class DownloadsRepositoryImpl(
    private val remote: ReaderRemoteDataSource,
    private val readerLocal: ReaderLocalDataSource,
    private val local: DownloadsLocalDataSource,
    private val files: PageFiles,
    private val network: NetworkMonitor,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val scheduler: DownloadScheduler = DownloadScheduler.None,
    private val epub: EpubParser = EpubParser(),
    private val clock: Clock,
) : DownloadsRepository {
    private class Running(
        val key: String,
        val sourceKey: String,
        val job: Job,
    )

    private val lock = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val block = MutableStateFlow<DownloadBlock?>(null)
    private var worker: Job? = null
    private var running: Running? = null

    override fun observeAll(): Flow<List<ChapterDownload>> = local.observeAll()

    override fun observeTitle(
        sourceKey: String,
        titleId: String,
    ): Flow<List<ChapterDownload>> = local.observeTitle(sourceKey, titleId)

    override fun observeBlock(): Flow<DownloadBlock?> = block.asStateFlow()

    override fun observeActivity(): Flow<DownloadActivity> =
        combine(local.observeAll(), block) { all, block ->
            val left = all.filter { it.state.unfinished }
            DownloadActivity(
                chapters = left.count { !it.isBook },
                books = left.count { it.isBook },
                current =
                    left.firstOrNull { it.state == DownloadState.Downloading } ?: left.firstOrNull { it.state == DownloadState.Waiting },
                block = if (left.isEmpty()) null else block,
            )
        }.distinctUntilChanged()

    override fun observeWifiOnly(): Flow<Boolean> = local.observeWifiOnly()

    override suspend fun setWifiOnly(enabled: Boolean) {
        local.setWifiOnly(enabled)
        // What was scheduled waits for the other network: schedule it again.
        if (local.unfinished().isNotEmpty()) scheduler.schedule(enabled, replace = true)
    }

    override suspend fun enqueue(
        title: DownloadableTitle,
        chapters: List<DownloadableChapter>,
    ) {
        if (chapters.isEmpty()) return
        val now = clock.nowMillis()
        local.add(
            chapters.mapIndexed { i, chapter ->
                NewDownload(
                    title.sourceKey,
                    title.titleId,
                    chapter.id,
                    title.titleName,
                    chapter.label,
                    chapter.number,
                    now + i,
                    title.isBook,
                )
            },
        )
        kick()
    }

    override suspend fun remove(
        sourceKey: String,
        chapterId: String,
    ) {
        val key = chapterKey(sourceKey, chapterId)
        val job =
            lock.withLock {
                local.delete(key)
                withContext(io) { files.deleteChapter(sourceKey, chapterId) }
                running?.takeIf { it.key == key }?.job
            }
        job?.cancel()
    }

    override suspend fun removeTitle(
        sourceKey: String,
        titleId: String,
    ) = local.keysOf(sourceKey, titleId).forEach { (source, chapter) -> remove(source, chapter) }

    override suspend fun retry(
        sourceKey: String,
        chapterId: String,
    ) {
        val key = chapterKey(sourceKey, chapterId)
        if (local.download(key)?.state != DownloadState.Failed) return
        local.setState(key, DownloadState.Queued, null)
        kick()
    }

    override suspend fun cancelAll() {
        local.unfinished().forEach { (source, chapter) -> remove(source, chapter) }
        scheduler.cancel()
    }

    override fun resume() {
        scope.launch {
            start()
            if (local.unfinished().isNotEmpty()) scheduler.schedule(local.wifiOnly())
        }
    }

    override suspend fun pause() {
        val (loop, job) =
            lock.withLock {
                val loop = worker
                worker = null
                loop to running?.job
            }
        loop?.cancelAndJoin()
        job?.cancel()
        // A book being read into sections stops at its next section; don't hold the system's time longer than that.
        withTimeoutOrNull(PAUSE_WAIT_MS) { job?.join() }
        lock.withLock {
            running = null
            // The chapter that was downloading (or waiting) is queued again; its pages on disk stay.
            local.requeueInterrupted()
        }
        block.value = null
    }

    override suspend fun drain(): DownloadBlock? {
        start()
        wake.trySend(Unit)
        // A block the queue hasn't cleared yet (the network just came back, Wi-Fi only just turned off) doesn't count.
        val settled = observeActivity().first { !it.busy || (it.block != null && blockNow() != null) }
        return if (settled.busy) blockNow() else null
    }

    /** What the network allows right now: null when the queue may download. */
    private suspend fun blockNow(): DownloadBlock? {
        val state = network.state.value
        return when {
            !state.online -> DownloadBlock.NoConnection
            !state.unmetered && local.wifiOnly() -> DownloadBlock.NotOnWifi
            else -> null
        }
    }

    override suspend fun clearLocal() {
        val job =
            lock.withLock {
                local.clear()
                withContext(io) { files.deleteAll() }
                running?.job
            }
        job?.cancel()
        scheduler.cancel()
    }

    override suspend fun clearSource(sourceKey: String) {
        val job =
            lock.withLock {
                local.clearSource(sourceKey)
                withContext(io) { files.deleteSource(sourceKey) }
                running?.takeIf { it.sourceKey == sourceKey }?.job
            }
        job?.cancel()
    }

    /** New work: run it now, and have the platform keep it going in the background. */
    private suspend fun kick() {
        start()
        wake.trySend(Unit)
        scheduler.schedule(local.wifiOnly())
    }

    private suspend fun start() =
        lock.withLock {
            if (worker == null) {
                local.requeueInterrupted()
                worker = scope.launch { work() }
            }
        }

    private suspend fun work() {
        while (true) {
            val next = local.nextQueued()
            if (next == null) {
                block.value = null
                wake.receive()
                continue
            }
            val job =
                lock.withLock {
                    // Removed between the two reads: nothing to do.
                    if (local.download(next.key)?.state != DownloadState.Queued) {
                        null
                    } else {
                        scope
                            .launch(
                                start = CoroutineStart.LAZY,
                            ) { download(next) }
                            .also { running = Running(next.key, next.sourceKey, it) }
                    }
                } ?: continue
            job.join()
            lock.withLock { if (running?.job == job) running = null }
        }
    }

    /**
     * A failure that may pass (no answer, a dropped connection, an unexpected answer) is tried again twice, after 5 s
     * then 30 s, before the chapter is marked failed: in the background nobody is there to tap "Try again" (the network
     * is often still settling when the system starts the app after a reboot). A refusal fails at once.
     */
    private suspend fun download(item: QueuedDownload) {
        var attempt = 0
        while (true) {
            val error = attempt(item) ?: return
            val transient = error == AppError.Network || error is AppError.Unknown || error is AppError.SourceUnavailable
            if (!transient || attempt >= RETRY_DELAYS_MS.size || !network.state.value.online) return failed(item.key, error)
            delay(RETRY_DELAYS_MS[attempt++])
        }
    }

    /** One try: null when it is done (or was removed meanwhile), else why it failed. */
    private suspend fun attempt(item: QueuedDownload): AppError? {
        val key = item.key
        try {
            if (!awaitNetwork(key)) return null
            val chapter =
                when (val loaded = remote.chapter(item.sourceKey, item.titleId, item.chapterId)) {
                    is AppResult.Err -> return loaded.error
                    is AppResult.Ok -> loaded.value
                }
            // The reader opens it offline from this copy.
            readerLocal.save(item.sourceKey, chapter)
            if (item.isBook) return downloadBook(item, chapter)
            if (chapter.pages.isEmpty()) return AppError.Rejected("NO_PAGES", "This chapter has no pages to download.")
            local.setPageCount(key, chapter.pages.size)
            val done = local.pages(key)
            for (page in chapter.pages.sortedBy { it.index }) {
                if (done[page.index]?.let(files::exists) == true) continue
                if (!awaitNetwork(key)) return null
                val bytes =
                    when (val fetched = remote.downloadPage(item.sourceKey, page.url)) {
                        is AppResult.Err -> return fetched.error
                        is AppResult.Ok -> fetched.value
                    }
                val kept =
                    lock.withLock {
                        if (local.download(key) == null) return@withLock false
                        val file = files.pagePath(item.sourceKey, item.chapterId, page.index, bytes)
                        withContext(io) { files.write(file, bytes) }
                        local.putPage(key, page.index, file, bytes.size.toLong())
                        true
                    }
                if (!kept) return null
            }
            local.setState(key, DownloadState.Done, null)
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return AppError.Unknown(e.message ?: "download")
        }
    }

    /** A book: its file fetched and read into sections, saved as opening it does (then it shows as a saved book). */
    private suspend fun downloadBook(
        item: QueuedDownload,
        chapter: ReaderChapterDto,
    ): AppError? {
        val key = item.key
        // Opened meanwhile: it is already on the device.
        if (readerLocal.observeBook(item.sourceKey, item.chapterId).first() != null) {
            lock.withLock { if (local.download(key) != null) local.finishBook(key, null) }
            return null
        }
        val url = chapter.document?.url ?: return NoBookFile
        val sections =
            when (val fetched = fetchBook(remote, epub, io, item.sourceKey, url)) {
                is AppResult.Err -> return fetched.error
                is AppResult.Ok -> fetched.value
            }
        lock.withLock { if (local.download(key) != null) local.finishBook(key, sections) }
        return null
    }

    /**
     * Waits until the network allows downloading (online; unmetered when "Wi-Fi only" is on). False when the chapter
     * was removed meanwhile.
     */
    private suspend fun awaitNetwork(key: String): Boolean {
        fun allowed(
            state: NetworkState,
            wifiOnly: Boolean,
        ) = state.online && (state.unmetered || !wifiOnly)
        val now = network.state.value
        if (!allowed(now, local.wifiOnly())) {
            block.value = if (now.online) DownloadBlock.NotOnWifi else DownloadBlock.NoConnection
            local.setState(key, DownloadState.Waiting, null)
            combine(network.state, local.observeWifiOnly()) { state, wifiOnly ->
                if (!allowed(state, wifiOnly)) block.value = if (state.online) DownloadBlock.NotOnWifi else DownloadBlock.NoConnection
                allowed(state, wifiOnly)
            }.first { it }
        }
        block.value = null
        if (local.download(key) == null) return false
        local.setState(key, DownloadState.Downloading, null)
        return true
    }

    /** Offline: back in the queue, it starts again once the device is online. Otherwise: failed, with the reason. */
    private suspend fun failed(
        key: String,
        error: AppError,
    ) {
        if (error == AppError.Network && !network.state.value.online) {
            local.setState(key, DownloadState.Queued, null)
            block.value = DownloadBlock.NoConnection
            network.state.first { it.online }
            return
        }
        // A lost connection or an unexpected answer (an expired link): just "stopped"; a refusal says why.
        val message = if (error == AppError.Network || error is AppError.Unknown) FAILED else error.userMessage()
        local.setState(key, DownloadState.Failed, message)
    }

    private companion object {
        const val FAILED = "The download stopped. Try again."
        const val PAUSE_WAIT_MS = 2_000L
        val RETRY_DELAYS_MS = listOf(5_000L, 30_000L)

        val DownloadState.unfinished: Boolean
            get() = this == DownloadState.Queued || this == DownloadState.Waiting || this == DownloadState.Downloading
    }
}
