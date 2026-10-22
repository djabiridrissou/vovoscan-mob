package com.vovoscan.feature.reader.data

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.Clock
import com.vovoscan.core.common.NetworkMonitor
import com.vovoscan.core.common.NetworkState
import com.vovoscan.core.common.userMessage
import com.vovoscan.feature.reader.domain.ChapterDownload
import com.vovoscan.feature.reader.domain.DownloadBlock
import com.vovoscan.feature.reader.domain.DownloadState
import com.vovoscan.feature.reader.domain.DownloadableChapter
import com.vovoscan.feature.reader.domain.DownloadableTitle
import com.vovoscan.feature.reader.domain.DownloadsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The download queue, run in the app's scope: one chapter at a time, page by page, each page written to disk then
 * recorded (a chapter interrupted halfway resumes at its first missing page). Every change to a chapter's rows and
 * files happens under one lock, so a chapter removed while it downloads leaves nothing behind.
 *
 * Network: with no connection, or on mobile data with "Wi-Fi only", the chapter waits and starts again by itself.
 * A failure while online (source down, expired link) marks it failed; "Try again" queues it again.
 */
class DownloadsRepositoryImpl(
    private val remote: ReaderRemoteDataSource,
    private val readerLocal: ReaderLocalDataSource,
    private val local: DownloadsLocalDataSource,
    private val files: PageFiles,
    private val network: NetworkMonitor,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
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

    override fun observeWifiOnly(): Flow<Boolean> = local.observeWifiOnly()

    override suspend fun setWifiOnly(enabled: Boolean) = local.setWifiOnly(enabled)

    override suspend fun enqueue(
        title: DownloadableTitle,
        chapters: List<DownloadableChapter>,
    ) {
        if (title.isBook || chapters.isEmpty()) return
        val now = clock.nowMillis()
        local.add(
            chapters.mapIndexed { i, chapter ->
                NewDownload(title.sourceKey, title.titleId, chapter.id, title.titleName, chapter.label, chapter.number, now + i)
            },
        )
        start()
        wake.trySend(Unit)
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
        start()
        wake.trySend(Unit)
    }

    override fun resume() {
        scope.launch { start() }
    }

    override suspend fun clearLocal() {
        val job =
            lock.withLock {
                local.clear()
                withContext(io) { files.deleteAll() }
                running?.job
            }
        job?.cancel()
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

    private suspend fun download(item: QueuedDownload) {
        val key = item.key
        try {
            if (!awaitNetwork(key)) return
            val chapter =
                when (val loaded = remote.chapter(item.sourceKey, item.titleId, item.chapterId)) {
                    is AppResult.Err -> return failed(key, loaded.error)
                    is AppResult.Ok -> loaded.value
                }
            // The reader opens it offline from this copy.
            readerLocal.save(item.sourceKey, chapter)
            if (chapter.pages.isEmpty()) return failed(key, AppError.Rejected("NO_PAGES", "This chapter has no pages to download."))
            local.setPageCount(key, chapter.pages.size)
            val done = local.pages(key)
            for (page in chapter.pages.sortedBy { it.index }) {
                if (done[page.index]?.let(files::exists) == true) continue
                if (!awaitNetwork(key)) return
                val bytes =
                    when (val fetched = remote.download(item.sourceKey, page.url)) {
                        is AppResult.Err -> return failed(key, fetched.error)
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
                if (!kept) return
            }
            local.setState(key, DownloadState.Done, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            local.setState(key, DownloadState.Failed, FAILED)
        }
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
    }
}
