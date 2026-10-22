package com.vovoscan.feature.reader.domain

import kotlinx.coroutines.flow.Flow

/** Where a chapter chosen for offline reading is. */
enum class DownloadState {
    /** Asked for, waiting for its turn (one chapter at a time). */
    Queued,

    /** Its turn, but the network doesn't allow it now (no connection, or mobile data with "Wi-Fi only"). */
    Waiting,

    Downloading,

    /** Every page is on the device: readable without a network. */
    Done,

    Failed,
}

/**
 * A chapter on the device, or on its way: a comic chapter downloaded page by page, or a book (`isBook`): its file
 * fetched once and its sections kept in the database, whether it was opened or downloaded from the title page.
 */
data class ChapterDownload(
    val sourceKey: String,
    val titleId: String,
    val titleName: String,
    val chapterId: String,
    /** "Chapter 3 · The gate" (comics) or the part's name (books). */
    val label: String,
    val number: Double,
    val state: DownloadState,
    val pagesDone: Int,
    val pageCount: Int,
    /** Space used on the device. */
    val bytes: Long,
    val error: String?,
    val isBook: Boolean = false,
)

/** What holds the queue while it has work. */
enum class DownloadBlock {
    NoConnection,

    /** "Wi-Fi only" is on and the device is on mobile data (or another metered network). */
    NotOnWifi,
}

/** What the queue is doing, for the platform code that keeps it running in the background and reports it. */
data class DownloadActivity(
    /** Comic chapters still to download: queued, waiting or downloading. */
    val chapters: Int = 0,
    /** Books still to download. */
    val books: Int = 0,
    /** The one downloading now (else the one waiting for the network). */
    val current: ChapterDownload? = null,
    /** Why the queue waits, when it does. */
    val block: DownloadBlock? = null,
) {
    val remaining: Int get() = chapters + books

    val busy: Boolean get() = remaining > 0
}

/**
 * Keeps the queue going when the app leaves the screen. A port: Android schedules a WorkManager worker, iOS has its
 * own background tasks (driven from Swift, so it needs nothing here). Both only call [DownloadsRepository.drain]:
 * the shared queue stays the source of truth.
 */
interface DownloadScheduler {
    /**
     * There is work in the queue: make sure the platform runs it, in the background too. `wifiOnly` is the network
     * it needs; `replace` when that changed (what was scheduled waits for the wrong network).
     */
    fun schedule(
        wifiOnly: Boolean,
        replace: Boolean = false,
    )

    /** The queue was cancelled or erased: nothing to keep running. */
    fun cancel()

    companion object {
        val None =
            object : DownloadScheduler {
                override fun schedule(
                    wifiOnly: Boolean,
                    replace: Boolean,
                ) = Unit

                override fun cancel() = Unit
            }
    }
}

/** A chapter a title page offers to download. */
data class DownloadableChapter(
    val id: String,
    val number: Double,
    val label: String,
)

/** A title as the title page knows it: chapters in reading order (oldest first) and the chapter being read. */
data class DownloadableTitle(
    val sourceKey: String,
    val titleId: String,
    val titleName: String,
    val isBook: Boolean,
    val chapters: List<DownloadableChapter>,
    val resumeChapterId: String?,
)

/**
 * The title page and the reading position, from the features that own them. A feature port: the app wires it to
 * the catalog (chapters) and the library (progress), without the reader knowing about them.
 */
fun interface TitleChapters {
    fun observe(
        sourceKey: String,
        titleId: String,
    ): Flow<DownloadableTitle?>
}

/**
 * Chapters downloaded for offline reading. Pages go to the app's own storage, referenced in the reader's database;
 * the reader shows them before anything from the network. A queue on the device, one chapter at a time, resumed when
 * the app starts again (and kept going in the background by the platform's [DownloadScheduler]). Pages come from the
 * API's signed links (internal source) or from the source itself through the rules engine's client (allowed hosts
 * only, never the vovoscan session). A book joins the same queue: its file is fetched and its sections saved, as
 * opening it does.
 */
interface DownloadsRepository {
    fun observeAll(): Flow<List<ChapterDownload>>

    fun observeTitle(
        sourceKey: String,
        titleId: String,
    ): Flow<List<ChapterDownload>>

    /** Null while the queue may run (or has nothing to do). */
    fun observeBlock(): Flow<DownloadBlock?>

    /** How much is left and what downloads now (background work, its notification). */
    fun observeActivity(): Flow<DownloadActivity>

    /** Download only on Wi-Fi (or another unmetered network). On by default. */
    fun observeWifiOnly(): Flow<Boolean>

    suspend fun setWifiOnly(enabled: Boolean)

    /** Queues these chapters, or a book's parts (already queued or downloaded ones are left as they are). */
    suspend fun enqueue(
        title: DownloadableTitle,
        chapters: List<DownloadableChapter>,
    )

    /** Stops the download if it's running and erases what it saved: a comic's pages, a book's sections. */
    suspend fun remove(
        sourceKey: String,
        chapterId: String,
    )

    suspend fun removeTitle(
        sourceKey: String,
        titleId: String,
    )

    suspend fun retry(
        sourceKey: String,
        chapterId: String,
    )

    /** Stops and forgets everything not downloaded yet (the notification's Cancel); what is on the device stays. */
    suspend fun cancelAll()

    /** Starts the queue (app launch, back on screen): what was interrupted starts again at its first missing page. */
    fun resume()

    /**
     * Stops the queue cleanly before the system suspends the app (iOS background time running out, Android work
     * stopped while in the background): the chapter downloading goes back to the queue, nothing is marked failed.
     * [resume] or [drain] starts it again.
     */
    suspend fun pause()

    /**
     * Runs the queue until it has nothing it can do now (background work): null once nothing is left, else why it
     * waits (the network).
     */
    suspend fun drain(): DownloadBlock?

    /** Sign-out: everything goes, files included. */
    suspend fun clearLocal()

    /** A source left the user's list: its downloads go. */
    suspend fun clearSource(sourceKey: String)
}
