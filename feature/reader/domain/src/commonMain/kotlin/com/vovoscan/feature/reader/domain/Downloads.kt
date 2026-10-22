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
 * A chapter on the device, or on its way: a comic chapter downloaded page by page, or a book already opened (its
 * sections are kept in the database: `isBook`, always `Done`).
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
 * the app starts again. Pages come from the API's signed links (internal source) or from the source itself through
 * the rules engine's client (allowed hosts only, never the vovoscan session).
 */
interface DownloadsRepository {
    fun observeAll(): Flow<List<ChapterDownload>>

    fun observeTitle(
        sourceKey: String,
        titleId: String,
    ): Flow<List<ChapterDownload>>

    /** Null while the queue may run (or has nothing to do). */
    fun observeBlock(): Flow<DownloadBlock?>

    /** Download only on Wi-Fi (or another unmetered network). On by default. */
    fun observeWifiOnly(): Flow<Boolean>

    suspend fun setWifiOnly(enabled: Boolean)

    /** Queues these chapters (already queued or downloaded ones are left as they are). */
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

    /** Starts the queue (app launch): what was interrupted starts again. */
    fun resume()

    /** Sign-out: everything goes, files included. */
    suspend fun clearLocal()

    /** A source left the user's list: its downloads go. */
    suspend fun clearSource(sourceKey: String)
}
