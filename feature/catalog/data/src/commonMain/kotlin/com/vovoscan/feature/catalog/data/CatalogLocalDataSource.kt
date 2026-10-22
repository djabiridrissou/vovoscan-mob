package com.vovoscan.feature.catalog.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.vovoscan.core.common.ContentRating
import com.vovoscan.core.common.StripGenres
import com.vovoscan.feature.catalog.data.db.CatalogDatabase
import com.vovoscan.feature.catalog.domain.CatalogFilter
import com.vovoscan.feature.catalog.domain.Chapter
import com.vovoscan.feature.catalog.domain.ContentKind
import com.vovoscan.feature.catalog.domain.INTERNAL_SOURCE
import com.vovoscan.feature.catalog.domain.ListedTitle
import com.vovoscan.feature.catalog.domain.ReadingFormat
import com.vovoscan.feature.catalog.domain.SourceCategory
import com.vovoscan.feature.catalog.domain.TitleDetail
import com.vovoscan.feature.catalog.domain.TitleSummary
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Local source: the feature's SQLDelight database, read by the screens. One listing per source and category
 * (`categoryId` null: everything), the sources' categories, the catalog's filter.
 */
interface CatalogLocalDataSource {
    fun observeListing(
        sourceKey: String,
        categoryId: String?,
    ): Flow<List<ListedTitle>>

    /** Stores a page of a listing; page 1 starts it over from scratch. */
    suspend fun saveCatalogPage(
        sourceKey: String,
        page: TitlesPage,
        categoryId: String? = null,
    )

    /** Next page to request, null if everything is already here. */
    suspend fun nextPage(
        sourceKey: String,
        categoryId: String? = null,
    ): Int?

    /** Titles saved for the source (in that category's listing when one is given). */
    suspend fun searchLocal(
        sourceKey: String,
        query: String,
        categoryId: String? = null,
    ): List<TitleSummary>

    /** Also keeps search results: the title page then opens offline. */
    suspend fun saveSummaries(
        sourceKey: String,
        items: List<TitleSummaryDto>,
    )

    /**
     * Formats the server learned (id → format), kept over the manga default only, so every listing and every launch
     * shows them; a source's later listings that name no format keep them too.
     */
    suspend fun saveLearnedFormats(
        sourceKey: String,
        formats: Map<String, String>,
    )

    /** The format saved for each of these titles (what the title page read), by id; titles not saved are left out. */
    suspend fun savedFormats(
        sourceKey: String,
        ids: List<String>,
    ): Map<String, String>

    /** The cover saved for each of these titles, by id; titles without one are left out. */
    suspend fun savedCovers(
        sourceKey: String,
        ids: List<String>,
    ): Map<String, String>

    fun observeCategories(sourceKey: String): Flow<List<SourceCategory>>

    suspend fun saveCategories(
        sourceKey: String,
        categories: List<SourceCategory>,
    )

    fun observeFilter(): Flow<CatalogFilter>

    suspend fun saveFilter(filter: CatalogFilter)

    fun observeNewestFirst(): Flow<Boolean>

    suspend fun saveNewestFirst(newestFirst: Boolean)

    fun observeDetail(
        sourceKey: String,
        id: String,
    ): Flow<TitleDetail?>

    suspend fun saveDetail(
        sourceKey: String,
        detail: TitleDetailDto,
    )

    suspend fun clear()

    /** Erases one source's listings, titles, chapters and categories (the source was removed). */
    suspend fun clearSource(sourceKey: String)
}

class SqlCatalogLocalDataSource(
    private val database: CatalogDatabase,
    private val io: CoroutineDispatcher,
) : CatalogLocalDataSource {
    private val queries get() = database.catalogQueries

    override fun observeListing(
        sourceKey: String,
        categoryId: String?,
    ): Flow<List<ListedTitle>> =
        queries
            .listingTitles(sourceKey, listing(categoryId)) { key, id, name, cover, kind, format, status, rating, page ->
                ListedTitle(summary(key, id, name, cover, kind, format, status, rating), page.toInt())
            }.asFlow()
            .mapToList(io)

    override suspend fun saveCatalogPage(
        sourceKey: String,
        page: TitlesPage,
        categoryId: String?,
    ) = withContext(io) {
        val listing = listing(categoryId)
        queries.transaction {
            val first = page.page <= 1
            if (first) queries.clearListing(sourceKey, listing)
            var rank = if (first) 0L else (queries.maxRank(sourceKey, listing).executeAsOne().max_rank ?: -1L) + 1
            page.items.forEach {
                upsertSummary(sourceKey, it)
                // A title already listed on an earlier page keeps its place (sites shift while paging).
                queries.putEntry(sourceKey, listing, it.id, page.page.toLong(), rank++)
            }
            queries.putCursor(sourceKey, listing, if (page.hasNext) (page.page + 1).toLong() else null)
        }
    }

    override suspend fun nextPage(
        sourceKey: String,
        categoryId: String?,
    ): Int? =
        withContext(io) {
            queries
                .cursor(sourceKey, listing(categoryId))
                .executeAsOneOrNull()
                ?.next_page
                ?.toInt()
        }

    override suspend fun searchLocal(
        sourceKey: String,
        query: String,
        categoryId: String?,
    ): List<TitleSummary> =
        withContext(io) {
            if (categoryId == null) {
                queries.searchTitles(sourceKey, query, ::summary).executeAsList()
            } else {
                queries.searchListing(sourceKey, categoryId, query, ::summary).executeAsList()
            }
        }

    override suspend fun saveSummaries(
        sourceKey: String,
        items: List<TitleSummaryDto>,
    ) = withContext(io) { queries.transaction { items.forEach { upsertSummary(sourceKey, it) } } }

    override suspend fun saveLearnedFormats(
        sourceKey: String,
        formats: Map<String, String>,
    ) = withContext(io) { queries.transaction { formats.forEach { (id, format) -> queries.learnFormat(format, sourceKey, id) } } }

    override suspend fun savedCovers(
        sourceKey: String,
        ids: List<String>,
    ): Map<String, String> =
        withContext(io) {
            ids
                .mapNotNull { id ->
                    queries
                        .titleById(sourceKey, id)
                        .executeAsOneOrNull()
                        ?.cover_url
                        ?.let { id to it }
                }.toMap()
        }

    override suspend fun savedFormats(
        sourceKey: String,
        ids: List<String>,
    ): Map<String, String> =
        withContext(io) { ids.mapNotNull { id -> queries.titleById(sourceKey, id).executeAsOneOrNull()?.let { id to it.format } }.toMap() }

    override fun observeCategories(sourceKey: String): Flow<List<SourceCategory>> =
        queries.categoriesOf(sourceKey) { id, name -> SourceCategory(id, name) }.asFlow().mapToList(io)

    override suspend fun saveCategories(
        sourceKey: String,
        categories: List<SourceCategory>,
    ) = withContext(io) {
        queries.transaction {
            queries.clearCategoriesOf(sourceKey)
            categories.forEachIndexed { position, it -> queries.insertCategory(sourceKey, it.id, it.name, position.toLong()) }
        }
    }

    override fun observeFilter(): Flow<CatalogFilter> =
        queries.allSettings().asFlow().mapToList(io).map { rows ->
            val values = rows.associate { it.key to it.setting_value }
            CatalogFilter(
                sourceKeys = values[SOURCES].splitList(),
                categoryName = values[CATEGORY]?.takeIf { it.isNotBlank() },
            )
        }

    override suspend fun saveFilter(filter: CatalogFilter) =
        withContext(io) {
            queries.transaction {
                if (filter.sourceKeys.isEmpty()) {
                    queries.deleteSetting(
                        SOURCES,
                    )
                } else {
                    queries.putSetting(SOURCES, filter.sourceKeys.joinList())
                }
                filter.categoryName?.takeIf { it.isNotBlank() }?.let { queries.putSetting(CATEGORY, it) } ?: queries.deleteSetting(CATEGORY)
            }
        }

    override fun observeNewestFirst(): Flow<Boolean> =
        queries
            .setting(NEWEST_FIRST)
            .asFlow()
            .mapToOneOrNull(io)
            .map { it == "1" }

    override suspend fun saveNewestFirst(newestFirst: Boolean) {
        withContext(io) { if (newestFirst) queries.putSetting(NEWEST_FIRST, "1") else queries.deleteSetting(NEWEST_FIRST) }
    }

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
                    summary =
                        summary(
                            title.source_key,
                            title.id,
                            title.name,
                            title.cover_url,
                            title.kind,
                            title.format,
                            title.status,
                            title.content_rating,
                        ),
                    synopsis = title.description.orEmpty(),
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
            upsertSummary(
                sourceKey,
                TitleSummaryDto(
                    detail.id,
                    detail.name,
                    detail.coverUrl,
                    detail.kind,
                    stripAware(sourceKey, detail),
                    detail.status,
                    detail.contentRating,
                    formatKnown = detail.formatKnown || stripAware(sourceKey, detail) != detail.format,
                ),
            )
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
                queries.clearEntries()
                queries.clearCursors()
                queries.clearCategories()
                queries.clearSettings()
            }
        }

    override suspend fun clearSource(sourceKey: String) =
        withContext(io) {
            queries.transaction {
                queries.clearChaptersOfSource(sourceKey)
                queries.clearTitlesOf(sourceKey)
                queries.clearEntriesOf(sourceKey)
                queries.clearCursorsOf(sourceKey)
                queries.clearCategoriesOf(sourceKey)
            }
        }

    private fun upsertSummary(
        sourceKey: String,
        it: TitleSummaryDto,
    ) {
        queries.insertSummary(sourceKey, it.id, it.name, it.coverUrl, it.kind, it.format, it.status, it.contentRating)
        // A source that names no format sends the default: the format read from the title page (its genres) stays. A
        // saved webtoon (its genres, or the server's word) is never turned back into a manga by a listing.
        val row = queries.titleById(sourceKey, it.id).executeAsOneOrNull()
        val saved = row?.format
        val format =
            when {
                !it.formatKnown -> saved ?: it.format
                sourceKey != INTERNAL_SOURCE && it.format == "manga" && saved == "webtoon" -> saved
                else -> it.format
            }
        // A search answer that sends no cover keeps the one the listing saved.
        val cover = it.coverUrl ?: row?.cover_url
        queries.updateSummary(it.name, cover, it.kind, format, it.status, it.contentRating, sourceKey, it.id)
    }

    private companion object {
        const val SOURCES = "sources"
        const val CATEGORY = "category"
        const val NEWEST_FIRST = "chapters_newest_first"
    }
}

/**
 * A title of a source read on the device saved as the default manga whose genres name a vertical strip is saved as a
 * webtoon, so its card, its search result and its page agree.
 */
private fun stripAware(
    sourceKey: String,
    detail: TitleDetailDto,
): String =
    if (sourceKey != INTERNAL_SOURCE && detail.kind != "book" && detail.format == "manga" && StripGenres.named(detail.genres)) {
        "webtoon"
    } else {
        detail.format
    }

/** The listing of everything is '' (a category id is never empty). */
private fun listing(categoryId: String?) = categoryId.orEmpty()

private const val SEPARATOR = "\u001F"

private fun List<String>.joinList() = joinToString(SEPARATOR)

private fun String?.splitList() = this?.split(SEPARATOR)?.filter { it.isNotBlank() }.orEmpty()

internal fun summary(
    sourceKey: String,
    id: String,
    name: String,
    coverUrl: String?,
    kind: String,
    format: String,
    status: String,
    contentRating: String?,
) = TitleSummary(
    sourceKey,
    id,
    name,
    coverUrl,
    ContentKind.fromApi(kind),
    ReadingFormat.fromApi(format),
    status,
    ContentRating.fromApi(contentRating),
)

internal fun TitleSummaryDto.toDomain(sourceKey: String) =
    TitleSummary(
        sourceKey,
        id,
        name,
        coverUrl,
        ContentKind.fromApi(kind),
        ReadingFormat.fromApi(format),
        status,
        ContentRating.fromApi(contentRating),
    )
