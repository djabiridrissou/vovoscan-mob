package com.vovoscan.feature.reader.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.vovoscan.feature.reader.data.db.ReaderDatabase
import com.vovoscan.feature.reader.domain.Book
import com.vovoscan.feature.reader.domain.BookSection
import com.vovoscan.feature.reader.domain.ComicFormat
import com.vovoscan.feature.reader.domain.ReaderChapter
import com.vovoscan.feature.reader.domain.ReaderPage
import com.vovoscan.feature.reader.domain.ReadingMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

internal fun chapterKey(
    sourceKey: String,
    chapterId: String,
) = "$sourceKey/$chapterId"

/** Local source: units already opened, their pages, and book sections. */
interface ReaderLocalDataSource {
    fun observe(
        sourceKey: String,
        chapterId: String,
    ): Flow<ReaderChapter?>

    suspend fun chapter(
        sourceKey: String,
        chapterId: String,
    ): ReaderChapter?

    suspend fun save(
        sourceKey: String,
        chapter: ReaderChapterDto,
    )

    fun observeBook(
        sourceKey: String,
        chapterId: String,
    ): Flow<Book?>

    suspend fun saveBook(
        sourceKey: String,
        chapterId: String,
        sections: List<ParsedSection>,
    )

    suspend fun sectionHtml(
        sourceKey: String,
        chapterId: String,
        index: Int,
    ): String?

    suspend fun clear()

    /** Erases one source's chapters, pages and book sections (the source was removed). */
    suspend fun clearSource(sourceKey: String)
}

class SqlReaderLocalDataSource(
    private val database: ReaderDatabase,
    private val io: CoroutineDispatcher,
) : ReaderLocalDataSource {
    private val queries get() = database.readerQueries

    override fun observe(
        sourceKey: String,
        chapterId: String,
    ): Flow<ReaderChapter?> {
        val key = chapterKey(sourceKey, chapterId)
        // Either table changing reads both at once: two separate queries combined could pair a chapter saved for the
        // first time with its pages before the save (none), and the reader then lost its resume page.
        return combine(queries.chapter(key).asFlow(), queries.pages(key).asFlow()) { _, _ -> }
            .map { withContext(io) { queries.transactionWithResult { read(key) } } }
            .distinctUntilChanged()
    }

    private fun read(key: String): ReaderChapter? {
        val chapter = queries.chapter(key).executeAsOneOrNull() ?: return null
        val pages = queries.pages(key).executeAsList()
        return ReaderChapter(
            sourceKey = chapter.source_key,
            id = chapter.id,
            titleId = chapter.title_id,
            titleName = chapter.title_name,
            number = chapter.number,
            name = chapter.name,
            mode = ReadingMode.from(chapter.kind, chapter.format),
            previousChapterId = chapter.previous_id,
            nextChapterId = chapter.next_id,
            pages = pages.map { page -> ReaderPage(page.page_index.toInt(), page.url, page.width.toInt(), page.height.toInt()) },
            documentUrl = chapter.document_url,
            format = ComicFormat.from(chapter.format),
        )
    }

    override suspend fun chapter(
        sourceKey: String,
        chapterId: String,
    ): ReaderChapter? =
        withContext(io) {
            queries.chapter(chapterKey(sourceKey, chapterId)).executeAsOneOrNull()?.let {
                ReaderChapter(
                    it.source_key,
                    it.id,
                    it.title_id,
                    it.title_name,
                    it.number,
                    it.name,
                    ReadingMode.from(it.kind, it.format),
                    it.previous_id,
                    it.next_id,
                    emptyList(),
                    it.document_url,
                    ComicFormat.from(it.format),
                )
            }
        }

    override suspend fun save(
        sourceKey: String,
        chapter: ReaderChapterDto,
    ) = withContext(io) {
        val key = chapterKey(sourceKey, chapter.id)
        queries.transaction {
            queries.putChapter(
                key,
                sourceKey,
                chapter.id,
                chapter.titleId,
                chapter.titleName,
                chapter.number,
                chapter.name,
                chapter.kind,
                chapter.format,
                chapter.previousChapterId,
                chapter.nextChapterId,
                chapter.document?.url,
            )
            queries.deletePages(key)
            chapter.pages.forEach { queries.putPage(key, it.index.toLong(), it.url, it.width.toLong(), it.height.toLong()) }
        }
    }

    override fun observeBook(
        sourceKey: String,
        chapterId: String,
    ): Flow<Book?> =
        queries
            .sections(chapterKey(sourceKey, chapterId))
            .asFlow()
            .mapToList(io)
            .map { rows ->
                rows.takeIf { it.isNotEmpty() }?.let { Book(it.map { row -> BookSection(row.section_index.toInt(), row.title) }) }
            }

    override suspend fun saveBook(
        sourceKey: String,
        chapterId: String,
        sections: List<ParsedSection>,
    ) = withContext(io) {
        val key = chapterKey(sourceKey, chapterId)
        queries.transaction {
            queries.deleteSections(key)
            sections.forEachIndexed { index, section -> queries.putSection(key, index.toLong(), section.title, section.html) }
        }
    }

    override suspend fun sectionHtml(
        sourceKey: String,
        chapterId: String,
        index: Int,
    ): String? = withContext(io) { queries.sectionHtml(chapterKey(sourceKey, chapterId), index.toLong()).executeAsOneOrNull() }

    override suspend fun clear() =
        withContext(io) {
            queries.transaction {
                queries.clearPages()
                queries.clearSections()
                queries.clearChapters()
                // The modes chosen per series go with the account; the device-wide preferences stay.
                queries.clearSeriesModes()
            }
        }

    override suspend fun clearSource(sourceKey: String) =
        withContext(io) {
            queries.transaction {
                queries.clearPagesOf(sourceKey)
                queries.clearSectionsOf(sourceKey)
                queries.clearChaptersOf(sourceKey)
                queries.clearSeriesModesOf(sourceKey)
            }
        }
}
