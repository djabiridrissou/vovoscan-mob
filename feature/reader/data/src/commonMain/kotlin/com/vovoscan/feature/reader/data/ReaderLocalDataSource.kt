package com.vovoscan.feature.reader.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.vovoscan.feature.reader.data.db.ReaderDatabase
import com.vovoscan.feature.reader.domain.ReaderChapter
import com.vovoscan.feature.reader.domain.ReaderPage
import com.vovoscan.feature.reader.domain.ReadingMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

/** Source locale : chapitres déjà ouverts et leurs pages. */
interface ReaderLocalDataSource {
    fun observe(chapterId: String): Flow<ReaderChapter?>

    suspend fun save(chapter: ReaderChapterDto)

    suspend fun clear()
}

class SqlReaderLocalDataSource(
    private val database: ReaderDatabase,
    private val io: CoroutineDispatcher,
) : ReaderLocalDataSource {
    private val queries get() = database.readerQueries

    override fun observe(chapterId: String): Flow<ReaderChapter?> =
        combine(
            queries.chapter(chapterId).asFlow().mapToOneOrNull(io),
            queries.pages(chapterId).asFlow().mapToList(io),
        ) { chapter, pages ->
            chapter?.let {
                ReaderChapter(
                    id = it.id,
                    titleId = it.title_id,
                    titleName = it.title_name,
                    number = it.number,
                    name = it.name,
                    mode = ReadingMode.fromFormat(it.format),
                    previousChapterId = it.previous_id,
                    nextChapterId = it.next_id,
                    pages = pages.map { page -> ReaderPage(page.page_index.toInt(), page.url, page.width.toInt(), page.height.toInt()) },
                )
            }
        }

    override suspend fun save(chapter: ReaderChapterDto) =
        withContext(io) {
            queries.transaction {
                queries.putChapter(
                    chapter.id,
                    chapter.titleId,
                    chapter.titleName,
                    chapter.number,
                    chapter.name,
                    chapter.format,
                    chapter.previousChapterId,
                    chapter.nextChapterId,
                )
                queries.deletePages(chapter.id)
                chapter.pages.forEach { queries.putPage(chapter.id, it.index.toLong(), it.url, it.width.toLong(), it.height.toLong()) }
            }
        }

    override suspend fun clear() =
        withContext(io) {
            queries.transaction {
                queries.clearPages()
                queries.clearChapters()
            }
        }
}
