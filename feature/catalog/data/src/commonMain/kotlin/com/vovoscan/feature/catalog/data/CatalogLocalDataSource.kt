package com.vovoscan.feature.catalog.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.vovoscan.core.network.ApiPage
import com.vovoscan.feature.catalog.data.db.CatalogDatabase
import com.vovoscan.feature.catalog.data.db.Title
import com.vovoscan.feature.catalog.domain.Chapter
import com.vovoscan.feature.catalog.domain.ReadingFormat
import com.vovoscan.feature.catalog.domain.TitleDetail
import com.vovoscan.feature.catalog.domain.TitleSummary
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Source locale : la base SQLDelight de la feature, lue par les écrans. */
interface CatalogLocalDataSource {
    fun observeCatalog(): Flow<List<TitleSummary>>

    /** Range une page du catalogue ; la page 1 repart de zéro. */
    suspend fun saveCatalogPage(page: ApiPage<TitleSummaryDto>)

    /** Prochaine page à demander, null si tout est déjà là. */
    suspend fun nextPage(): Int?

    suspend fun searchLocal(query: String): List<TitleSummary>

    /** Garde aussi les résultats de recherche : la fiche s'ouvre ensuite hors ligne. */
    suspend fun saveSummaries(items: List<TitleSummaryDto>)

    fun observeDetail(id: String): Flow<TitleDetail?>

    suspend fun saveDetail(detail: TitleDetailDto)

    suspend fun clear()
}

class SqlCatalogLocalDataSource(
    private val database: CatalogDatabase,
    private val io: CoroutineDispatcher,
) : CatalogLocalDataSource {
    private val queries get() = database.catalogQueries

    override fun observeCatalog(): Flow<List<TitleSummary>> =
        queries
            .catalogTitles { id, name, coverUrl, format, status, _, _, _, _ -> summary(id, name, coverUrl, format, status) }
            .asFlow()
            .mapToList(io)

    override suspend fun saveCatalogPage(page: ApiPage<TitleSummaryDto>) =
        withContext(io) {
            queries.transaction {
                val first = page.page <= 1
                if (first) queries.clearRanks()
                var rank = if (first) 0L else (queries.maxRank().executeAsOne().max_rank ?: -1L) + 1
                page.data.forEach {
                    upsertSummary(it)
                    queries.setRank(rank++, it.id)
                }
                queries.putCursor(if (page.hasNext) (page.page + 1).toLong() else null)
            }
        }

    override suspend fun nextPage(): Int? =
        withContext(io) {
            queries
                .cursor()
                .executeAsOneOrNull()
                ?.next_page
                ?.toInt()
        }

    override suspend fun searchLocal(query: String): List<TitleSummary> =
        withContext(io) { queries.searchTitles(query).executeAsList().map { it.toSummary() } }

    override suspend fun saveSummaries(items: List<TitleSummaryDto>) =
        withContext(io) { queries.transaction { items.forEach { upsertSummary(it) } } }

    override fun observeDetail(id: String): Flow<TitleDetail?> =
        combine(
            queries.titleById(id).asFlow().mapToList(io),
            queries.chaptersOf(id).asFlow().mapToList(io),
        ) { titles, chapters ->
            titles.firstOrNull()?.let { title ->
                TitleDetail(
                    summary = title.toSummary(),
                    description = title.description.orEmpty(),
                    authors = title.authors.splitList(),
                    genres = title.genres.splitList(),
                    chapters = chapters.map { Chapter(it.id, it.number, it.name, it.page_count.toInt(), it.published_at) },
                )
            }
        }

    override suspend fun saveDetail(detail: TitleDetailDto) =
        withContext(io) {
            queries.transaction {
                upsertSummary(TitleSummaryDto(detail.id, detail.name, detail.coverUrl, detail.format, detail.status))
                queries.updateDetail(detail.description, detail.authors.joinList(), detail.genres.joinList(), detail.id)
                queries.deleteChaptersOf(detail.id)
                detail.chapters.forEach {
                    queries.insertChapter(it.id, detail.id, it.number, it.name, it.pageCount.toLong(), it.publishedAt)
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

    private fun upsertSummary(it: TitleSummaryDto) {
        queries.insertSummary(it.id, it.name, it.coverUrl, it.format, it.status)
        queries.updateSummary(it.name, it.coverUrl, it.format, it.status, it.id)
    }
}

private const val SEPARATOR = "\u001F"

private fun List<String>.joinList() = joinToString(SEPARATOR)

private fun String?.splitList() = this?.split(SEPARATOR)?.filter { it.isNotBlank() }.orEmpty()

private fun summary(
    id: String,
    name: String,
    coverUrl: String?,
    format: String,
    status: String,
) = TitleSummary(id, name, coverUrl, ReadingFormat.fromApi(format), status)

private fun Title.toSummary() = summary(id, name, cover_url, format, status)

internal fun TitleSummaryDto.toDomain() = TitleSummary(id, name, coverUrl, ReadingFormat.fromApi(format), status)
