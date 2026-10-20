package com.vovoscan.feature.catalog.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.vovoscan.feature.catalog.data.db.CatalogDatabase
import com.vovoscan.feature.catalog.domain.Chapter
import com.vovoscan.feature.catalog.domain.ContentKind
import com.vovoscan.feature.catalog.domain.ReadingFormat
import com.vovoscan.feature.catalog.domain.TitleDetail
import com.vovoscan.feature.catalog.domain.TitleSummary
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

/** Local source: the feature's SQLDelight database, read by the screens; one catalog per source. */
interface CatalogLocalDataSource {
    fun observeCatalog(sourceKey: String): Flow<List<TitleSummary>>

    /** Stores a page of a source's catalog; page 1 starts over from scratch. */
    suspend fun saveCatalogPage(
        sourceKey: String,
        page: TitlesPage,
    )

    /** Next page to request, null if everything is already here. */
    suspend fun nextPage(sourceKey: String): Int?

    suspend fun searchLocal(
        sourceKey: String,
        query: String,
    ): List<TitleSummary>

    /** Also keeps search results: the title page then opens offline. */
    suspend fun saveSummaries(
        sourceKey: String,
        items: List<TitleSummaryDto>,
    )

    fun observeDetail(
        sourceKey: String,
        id: String,
    ): Flow<TitleDetail?>

    suspend fun saveDetail(
        sourceKey: String,
        detail: TitleDetailDto,
    )

    suspend fun clear()
}

class SqlCatalogLocalDataSource(
    private val database: CatalogDatabase,
    private val io: CoroutineDispatcher,
) : CatalogLocalDataSource {
    private val queries get() = database.catalogQueries

    override fun observeCatalog(sourceKey: String): Flow<List<TitleSummary>> =
        queries.catalogTitles(sourceKey, ::summary).asFlow().mapToList(io)

    override suspend fun saveCatalogPage(
        sourceKey: String,
        page: TitlesPage,
    ) = withContext(io) {
        queries.transaction {
            val first = page.page <= 1
            if (first) queries.clearRanks(sourceKey)
            var rank = if (first) 0L else (queries.maxRank(sourceKey).executeAsOne().max_rank ?: -1L) + 1
            page.items.forEach {
                upsertSummary(sourceKey, it)
                queries.setRank(rank++, sourceKey, it.id)
            }
            queries.putCursor(sourceKey, if (page.hasNext) (page.page + 1).toLong() else null)
        }
    }

    override suspend fun nextPage(sourceKey: String): Int? =
        withContext(io) {
            queries
                .cursor(sourceKey)
                .executeAsOneOrNull()
                ?.next_page
                ?.toInt()
        }

    override suspend fun searchLocal(
        sourceKey: String,
        query: String,
    ): List<TitleSummary> = withContext(io) { queries.searchTitles(sourceKey, query, ::summary).executeAsList() }

    override suspend fun saveSummaries(
        sourceKey: String,
        items: List<TitleSummaryDto>,
    ) = withContext(io) { queries.transaction { items.forEach { upsertSummary(sourceKey, it) } } }

    override fun observeDetail(
        sourceKey: String,
        id: String,
    ): Flow<TitleDetail?> =
        combine(
            queries.titleById(sourceKey, id).asFlow().mapToList(io),
            queries.chaptersOf(sourceKey, id).asFlow().mapToList(io),
        ) { titles, chapters ->
            titles.firstOrNull()?.let { title ->
                TitleDetail(
                    summary = summary(title.source_key, title.id, title.name, title.cover_url, title.kind, title.format, title.status),
                    description = title.description.orEmpty(),
                    authors = title.authors.splitList(),
                    genres = title.genres.splitList(),
                    chapters = chapters.map { Chapter(it.id, it.number, it.name, it.page_count.toInt(), it.published_at) },
                )
            }
        }

    override suspend fun saveDetail(
        sourceKey: String,
        detail: TitleDetailDto,
    ) = withContext(io) {
        queries.transaction {
            upsertSummary(sourceKey, TitleSummaryDto(detail.id, detail.name, detail.coverUrl, detail.kind, detail.format, detail.status))
            queries.updateDetail(detail.description, detail.authors.joinList(), detail.genres.joinList(), sourceKey, detail.id)
            queries.deleteChaptersOf(sourceKey, detail.id)
            detail.chapters.forEach {
                queries.insertChapter(sourceKey, detail.id, it.id, it.number, it.name, it.pageCount.toLong(), it.publishedAt)
            }
        }
    }

    override suspend fun clear() =
        withContext(io) {
            queries.transaction {
                queries.clearChapters()
                queries.clearTitles()
                queries.clearCursor()
            }
        }

    private fun upsertSummary(
        sourceKey: String,
        it: TitleSummaryDto,
    ) {
        queries.insertSummary(sourceKey, it.id, it.name, it.coverUrl, it.kind, it.format, it.status)
        queries.updateSummary(it.name, it.coverUrl, it.kind, it.format, it.status, sourceKey, it.id)
    }
}

private const val SEPARATOR = "\u001F"

private fun List<String>.joinList() = joinToString(SEPARATOR)

private fun String?.splitList() = this?.split(SEPARATOR)?.filter { it.isNotBlank() }.orEmpty()

private fun summary(
    sourceKey: String,
    id: String,
    name: String,
    coverUrl: String?,
    kind: String,
    format: String,
    status: String,
) = TitleSummary(sourceKey, id, name, coverUrl, ContentKind.fromApi(kind), ReadingFormat.fromApi(format), status)

internal fun TitleSummaryDto.toDomain(sourceKey: String) =
    TitleSummary(sourceKey, id, name, coverUrl, ContentKind.fromApi(kind), ReadingFormat.fromApi(format), status)
