package com.vovoscan.feature.reader.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.vovoscan.feature.reader.data.db.ReaderDatabase
import com.vovoscan.feature.reader.domain.ChapterDownload
import com.vovoscan.feature.reader.domain.DownloadState
import com.vovoscan.feature.reader.domain.numberLabel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** A row of the download queue. */
data class QueuedDownload(
    val key: String,
    val sourceKey: String,
    val titleId: String,
    val chapterId: String,
    val state: DownloadState,
    val isBook: Boolean = false,
)

/** One chapter to add to the queue. */
data class NewDownload(
    val sourceKey: String,
    val titleId: String,
    val chapterId: String,
    val titleName: String,
    val label: String,
    val number: Double,
    val requestedAt: Long,
    val isBook: Boolean = false,
)

/** The download queue, downloaded pages and saved books, in the reader's database. */
interface DownloadsLocalDataSource {
    fun observeAll(): Flow<List<ChapterDownload>>

    fun observeTitle(
        sourceKey: String,
        titleId: String,
    ): Flow<List<ChapterDownload>>

    /** Downloaded pages of a chapter: page index → path relative to the downloads folder. */
    fun observePages(key: String): Flow<Map<Int, String>>

    suspend fun pages(key: String): Map<Int, String>

    suspend fun download(key: String): QueuedDownload?

    suspend fun nextQueued(): QueuedDownload?

    /** Adds what isn't already in the queue. */
    suspend fun add(downloads: List<NewDownload>)

    suspend fun setState(
        key: String,
        state: DownloadState,
        error: String?,
    )

    suspend fun setPageCount(
        key: String,
        count: Int,
    )

    suspend fun putPage(
        key: String,
        index: Int,
        file: String,
        bytes: Long,
    )

    /** What was interrupted (the app stopped) goes back to the queue. */
    suspend fun requeueInterrupted()

    /** Queued, waiting or downloading: `(sourceKey, chapterId)`. */
    suspend fun unfinished(): List<Pair<String, String>>

    /**
     * A downloaded book: its sections saved and its queue row gone, in one go (it then shows as a saved book).
     * `sections` null: already saved (opened meanwhile), only the row goes.
     */
    suspend fun finishBook(
        key: String,
        sections: List<ParsedSection>?,
    )

    /** The queue row, its pages and, for a book, its saved sections. */
    suspend fun delete(key: String)

    /** Chapter keys of a title: queue rows and saved books. */
    suspend fun keysOf(
        sourceKey: String,
        titleId: String,
    ): List<Pair<String, String>>

    fun observeWifiOnly(): Flow<Boolean>

    suspend fun wifiOnly(): Boolean

    suspend fun setWifiOnly(enabled: Boolean)

    suspend fun clear()

    suspend fun clearSource(sourceKey: String)
}

class SqlDownloadsLocalDataSource(
    private val database: ReaderDatabase,
    private val io: CoroutineDispatcher,
) : DownloadsLocalDataSource {
    private val queries get() = database.readerQueries

    override fun observeAll(): Flow<List<ChapterDownload>> =
        combine(
            queries.downloads(::comic).asFlow().mapToList(io),
            queries.savedBooks(::book).asFlow().mapToList(io),
        ) { comics, books -> comics + books }

    override fun observeTitle(
        sourceKey: String,
        titleId: String,
    ): Flow<List<ChapterDownload>> =
        combine(
            queries.titleDownloads(sourceKey, titleId, ::comic).asFlow().mapToList(io),
            queries.titleSavedBooks(sourceKey, titleId, ::book).asFlow().mapToList(io),
        ) { comics, books -> comics + books }

    override fun observePages(key: String): Flow<Map<Int, String>> =
        queries
            .downloadPages(key)
            .asFlow()
            .mapToList(io)
            .map { rows -> rows.associate { it.page_index.toInt() to it.file_path } }
            .distinctUntilChanged()

    override suspend fun pages(key: String): Map<Int, String> =
        withContext(io) { queries.downloadPages(key).executeAsList().associate { it.page_index.toInt() to it.file_path } }

    override suspend fun download(key: String): QueuedDownload? = withContext(io) { queries.download(key).executeAsOneOrNull()?.queued() }

    override suspend fun nextQueued(): QueuedDownload? = withContext(io) { queries.nextQueued().executeAsOneOrNull()?.queued() }

    override suspend fun add(downloads: List<NewDownload>) =
        withContext(io) {
            queries.transaction {
                downloads.forEach {
                    queries.putDownload(
                        chapterKey(it.sourceKey, it.chapterId),
                        it.sourceKey,
                        it.titleId,
                        it.chapterId,
                        it.titleName,
                        it.label,
                        it.number,
                        it.requestedAt,
                        if (it.isBook) BOOK else COMIC,
                    )
                }
            }
        }

    override suspend fun setState(
        key: String,
        state: DownloadState,
        error: String?,
    ) {
        withContext(io) { queries.setDownloadState(state.name.lowercase(), error, key) }
    }

    override suspend fun setPageCount(
        key: String,
        count: Int,
    ) {
        withContext(io) { queries.setDownloadPageCount(count.toLong(), key) }
    }

    override suspend fun putPage(
        key: String,
        index: Int,
        file: String,
        bytes: Long,
    ) {
        withContext(io) { queries.putDownloadPage(key, index.toLong(), file, bytes) }
    }

    override suspend fun requeueInterrupted() {
        withContext(io) { queries.requeueInterrupted() }
    }

    override suspend fun unfinished(): List<Pair<String, String>> =
        withContext(io) { queries.unfinishedDownloads().executeAsList().map { it.source_key to it.chapter_id } }

    override suspend fun finishBook(
        key: String,
        sections: List<ParsedSection>?,
    ) = withContext(io) {
        queries.transaction {
            if (sections != null) database.putBook(key, sections)
            queries.deleteDownload(key)
        }
    }

    override suspend fun delete(key: String) =
        withContext(io) {
            queries.transaction {
                queries.deleteDownloadPages(key)
                queries.deleteDownload(key)
                database.deleteBook(key)
            }
        }

    override suspend fun keysOf(
        sourceKey: String,
        titleId: String,
    ): List<Pair<String, String>> =
        withContext(io) {
            (
                queries.titleDownloads(sourceKey, titleId).executeAsList().map { it.source_key to it.chapter_id } +
                    queries.titleSavedBooks(sourceKey, titleId).executeAsList().map { it.source_key to it.id }
            ).distinct()
        }

    override fun observeWifiOnly(): Flow<Boolean> =
        queries
            .setting(WIFI_ONLY)
            .asFlow()
            .mapToOneOrNull(io)
            .map { it != "false" }
            .distinctUntilChanged()

    override suspend fun wifiOnly(): Boolean = withContext(io) { queries.setting(WIFI_ONLY).executeAsOneOrNull() != "false" }

    override suspend fun setWifiOnly(enabled: Boolean) {
        withContext(io) { queries.putSetting(WIFI_ONLY, enabled.toString()) }
    }

    override suspend fun clear() =
        withContext(io) {
            queries.transaction {
                queries.clearDownloadPages()
                queries.clearDownloads()
            }
        }

    override suspend fun clearSource(sourceKey: String) =
        withContext(io) {
            queries.transaction {
                queries.clearDownloadPagesOf(sourceKey)
                queries.clearDownloadsOf(sourceKey)
            }
        }

    private fun com.vovoscan.feature.reader.data.db.Download.queued() =
        QueuedDownload(chapter_key, source_key, title_id, chapter_id, stateOf(state), kind == BOOK)

    @Suppress("LongParameterList", "UNUSED_PARAMETER")
    private fun comic(
        key: String,
        sourceKey: String,
        titleId: String,
        chapterId: String,
        titleName: String,
        label: String,
        number: Double,
        state: String,
        pageCount: Long,
        error: String?,
        kind: String,
        pagesDone: Long,
        bytes: Double,
    ) = ChapterDownload(
        sourceKey = sourceKey,
        titleId = titleId,
        titleName = titleName,
        chapterId = chapterId,
        label = label,
        number = number,
        state = stateOf(state),
        pagesDone = pagesDone.toInt(),
        pageCount = pageCount.toInt(),
        bytes = bytes.toLong(),
        error = error,
        isBook = kind == BOOK,
    )

    @Suppress("LongParameterList", "UNUSED_PARAMETER")
    private fun book(
        key: String,
        sourceKey: String,
        titleId: String,
        chapterId: String,
        titleName: String,
        name: String?,
        number: Double,
        bytes: Double,
    ) = ChapterDownload(
        sourceKey = sourceKey,
        titleId = titleId,
        titleName = titleName,
        chapterId = chapterId,
        label = name ?: "Part ${numberLabel(number)}",
        number = number,
        state = DownloadState.Done,
        pagesDone = 0,
        pageCount = 0,
        bytes = bytes.toLong(),
        error = null,
        isBook = true,
    )

    private companion object {
        const val WIFI_ONLY = "download_wifi_only"
        const val COMIC = "comic"
        const val BOOK = "book"

        fun stateOf(value: String): DownloadState =
            DownloadState.entries.firstOrNull { it.name.lowercase() == value } ?: DownloadState.Queued
    }
}
