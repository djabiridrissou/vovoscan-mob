package com.vovoscan.feature.reader.data

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.map
import com.vovoscan.feature.reader.domain.Book
import com.vovoscan.feature.reader.domain.ReaderChapter
import com.vovoscan.feature.reader.domain.ReaderRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Downloaded pages of a chapter on this device: page index → `file://` URL. */
fun interface LocalPages {
    fun observe(
        sourceKey: String,
        chapterId: String,
    ): Flow<Map<Int, String>>

    companion object {
        val None = LocalPages { _, _ -> flowOf(emptyMap()) }
    }
}

/** The download queue's pages, as files that are really on disk (a missing file falls back to the network). */
class DownloadedPages(
    private val downloads: DownloadsLocalDataSource,
    private val files: PageFiles,
) : LocalPages {
    override fun observe(
        sourceKey: String,
        chapterId: String,
    ): Flow<Map<Int, String>> =
        downloads
            .observePages(chapterKey(sourceKey, chapterId))
            .map { found -> found.filterValues(files::exists).mapValues { (_, file) -> files.url(file) } }
}

/**
 * Stale-while-revalidate for units; a book is downloaded once, opened here, then kept as sections. A downloaded
 * comic chapter's pages are files on the device, shown before the network's.
 */
class ReaderRepositoryImpl(
    private val remote: ReaderRemoteDataSource,
    private val local: ReaderLocalDataSource,
    private val epub: EpubParser,
    private val io: CoroutineDispatcher,
    private val localPages: LocalPages = LocalPages.None,
) : ReaderRepository {
    override fun observe(
        sourceKey: String,
        chapterId: String,
    ): Flow<ReaderChapter?> =
        combine(local.observe(sourceKey, chapterId), localPages.observe(sourceKey, chapterId)) { chapter, files ->
            if (chapter == null || files.isEmpty()) {
                chapter
            } else {
                chapter.copy(pages = chapter.pages.map { page -> files[page.index]?.let { page.copy(url = it) } ?: page })
            }
        }

    override suspend fun refresh(
        sourceKey: String,
        titleId: String,
        chapterId: String,
    ): AppResult<Unit> =
        remote.chapter(sourceKey, titleId, chapterId).also { if (it is AppResult.Ok) local.save(sourceKey, it.value) }.map { }

    override fun observeBook(
        sourceKey: String,
        chapterId: String,
    ): Flow<Book?> = local.observeBook(sourceKey, chapterId)

    override suspend fun loadBook(
        sourceKey: String,
        chapterId: String,
    ): AppResult<Unit> {
        if (local.observeBook(sourceKey, chapterId).first() != null) return AppResult.Ok(Unit)
        val url =
            local.chapter(sourceKey, chapterId)?.documentUrl
                ?: return AppResult.Err(AppError.Rejected("NO_BOOK_FILE", "This book has no file yet."))
        val bytes =
            when (val downloaded = remote.download(sourceKey, url)) {
                is AppResult.Err -> return downloaded
                is AppResult.Ok -> downloaded.value
            }
        val sections =
            try {
                withContext(io) { epub.parse(bytes) }
            } catch (e: InvalidEpub) {
                return AppResult.Err(AppError.Rejected("INVALID_BOOK", e.message ?: "This book file can't be opened."))
            }
        local.saveBook(sourceKey, chapterId, sections)
        return AppResult.Ok(Unit)
    }

    override suspend fun sectionHtml(
        sourceKey: String,
        chapterId: String,
        index: Int,
    ): String? = local.sectionHtml(sourceKey, chapterId, index)

    override suspend fun clearLocal() = local.clear()

    override suspend fun clearSource(sourceKey: String) = local.clearSource(sourceKey)
}
