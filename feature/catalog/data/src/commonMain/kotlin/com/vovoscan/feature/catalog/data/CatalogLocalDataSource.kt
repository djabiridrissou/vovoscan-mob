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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Local source: the feature's SQLDelight database, read by the screens. One listing per source and category
 * (`categoryId` null: everything), the sources' categories, the catalog's filter.
 */
interface CatalogLocalDataSource {
    /** [episodes]: the listing of the titles with an episode (its own, as the server gave it). */
    fun observeListing(
        sourceKey: String,
        categoryId: String?,
        episodes: Boolean = false,
    ): Flow<List<ListedTitle>>

    /** Stores a page of a listing; page 1 starts it over from scratch. */
    suspend fun saveCatalogPage(
        sourceKey: String,
        page: TitlesPage,
        categoryId: String? = null,
        episodes: Boolean = false,
    )

    /** Next page to request, null if everything is already here. */
    suspend fun nextPage(
        sourceKey: String,
        categoryId: String? = null,
        episodes: Boolean = false,
    ): Int?

    /** Titles saved for the source (in that category's listing when one is given; with an episode for [episodes]). */
    suspend fun searchLocal(
        sourceKey: String,
        query: String,
        categoryId: String? = null,
        episodes: Boolean = false,
    ): List<TitleSummary>

    /** The episode count saved for a title (the server's word last heard; 0 when none or unknown). */
    suspend fun savedEpisodeCount(
        sourceKey: String,
        id: String,
    ): Int

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

    /** The episode languages saved with a chapter (any title of [sourceKey] holding it). */
    fun observeEpisodeLanguages(
        sourceKey: String,
        chapterId: String,
    ): Flow<Set<String>>

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

    /** The title and, unless [keepChapters], its chapters (replacing the saved ones). */
    suspend fun saveDetail(
        sourceKey: String,
        detail: TitleDetailDto,
        keepChapters: Boolean = false,
    )

    /** One page of a long series' chapters, added to or replacing the saved ones (with the server's episode languages). */
    suspend fun saveChapters(
        sourceKey: String,
        titleId: String,
        chapters: List<ChapterDto>,
    )

    /** Every page read: the saved chapters not in [kept] are gone from the source. */
    suspend fun pruneChapters(
        sourceKey: String,
        titleId: String,
        kept: Set<String>,
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
        episodes: Boolean,
    ): Flow<List<ListedTitle>> =
        queries
            .listingTitles(sourceKey, listing(categoryId, episodes)) { key, id, name, cover, kind, format, status, rating, count, page ->
                ListedTitle(summary(key, id, name, cover, kind, format, status, rating, count), page.toInt())
            }.asFlow()
            .mapToList(io)

    override suspend fun saveCatalogPage(
        sourceKey: String,
        page: TitlesPage,
        categoryId: String?,
        episodes: Boolean,
    ) = withContext(io) {
        val listing = listing(categoryId, episodes)
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
        episodes: Boolean,
    ): Int? =
        withContext(io) {
            queries
                .cursor(sourceKey, listing(categoryId, episodes))
                .executeAsOneOrNull()
                ?.next_page
                ?.toInt()
        }

    override suspend fun searchLocal(
        sourceKey: String,
        query: String,
        categoryId: String?,
        episodes: Boolean,
    ): List<TitleSummary> =
        withContext(io) {
            val found =
                if (categoryId == null) {
                    queries.searchTitles(sourceKey, query, ::summary).executeAsList()
                } else {
                    queries.searchListing(sourceKey, categoryId, query, ::summary).executeAsList()
                }
            if (episodes) found.filter { it.episodeCount > 0 } else found
        }

    override suspend fun savedEpisodeCount(
        sourceKey: String,
        id: String,
    ): Int =
        withContext(io) {
            queries
                .titleById(sourceKey, id)
                .executeAsOneOrNull()
                ?.episode_count
                ?.toInt() ?: 0
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
                episodesOnly = values[EPISODES] == "1",
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
                if (filter.episodesOnly) queries.putSetting(EPISODES, "1") else queries.deleteSetting(EPISODES)
            }
        }

    override fun observeEpisodeLanguages(
        sourceKey: String,
        chapterId: String,
    ): Flow<Set<String>> =
        queries
            .chaptersById(sourceKey, chapterId)
            .asFlow()
            .mapToList(io)
            .map { rows -> rows.flatMap { decodeLanguages(it.episode_languages) }.toSet() }
            .distinctUntilChanged()

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
                            title.episode_count,
                        ),
                    synopsis = title.description.orEmpty(),
                    authors = title.authors.splitList(),
                    genres = title.genres.splitList(),
                    chapters =
                        chapters.map {
                            Chapter(
                                it.id,
                                it.number,
                                it.name,
                                it.page_count.toInt(),
                                it.published_at,
                                decodeLanguages(it.episode_languages),
                            )
                        },
                )
            }
        }

    override suspend fun saveChapters(
        sourceKey: String,
        titleId: String,
        chapters: List<ChapterDto>,
    ) = withContext(io) {
        queries.transaction {
            chapters.forEach {
                val languages = encodeLanguages(it.episodeLanguages)
                queries.insertChapter(sourceKey, titleId, it.id, it.number, it.name, it.pageCount.toLong(), it.publishedAt, languages)
            }
        }
    }

    override suspend fun pruneChapters(
        sourceKey: String,
        titleId: String,
        kept: Set<String>,
    ) = withContext(io) {
        queries.transaction {
            queries.chaptersOf(sourceKey, titleId).executeAsList().filter { it.id !in kept }.forEach {
                queries.deleteChapter(sourceKey, titleId, it.id)
            }
        }
    }

    override suspend fun saveDetail(
        sourceKey: String,
        detail: TitleDetailDto,
        keepChapters: Boolean,
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
                    episodeCount = detail.episodeCount,
                    episodesKnown = detail.episodesKnown,
                ),
            )
            queries.updateDetail(detail.description, detail.authors.joinList(), detail.genres.joinList(), sourceKey, detail.id)
            if (keepChapters) return@transaction
            // Read on the device: the languages the server gave before stay with their chapters.
            val saved =
                if (detail.episodesKnown) {
                    emptyMap()
                } else {
                    queries.chaptersOf(sourceKey, detail.id).executeAsList().associate { it.id to it.episode_languages }
                }
            queries.deleteChaptersOf(sourceKey, detail.id)
            detail.chapters.forEach {
                val languages = if (detail.episodesKnown) encodeLanguages(it.episodeLanguages) else saved[it.id]
                queries.insertChapter(sourceKey, detail.id, it.id, it.number, it.name, it.pageCount.toLong(), it.publishedAt, languages)
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
                queries.clearStats()
                queries.clearRatings()
                queries.clearSeries()
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
                queries.clearStatsOf(sourceKey)
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
        // Listed by the device itself: the count the server gave stays.
        if (it.episodesKnown) queries.setEpisodeCount(it.episodeCount.toLong(), sourceKey, it.id)
        // Our catalog's listings carry everyone's numbers (favourites and ratings contract); other sources are asked apart.
        if (sourceKey == INTERNAL_SOURCE) {
            queries.putStats(sourceKey, it.id, it.ratingAvg, it.ratingCount?.toLong(), it.favoriteCount?.toLong())
        }
    }

    private companion object {
        const val SOURCES = "sources"
        const val CATEGORY = "category"
        const val EPISODES = "episodes"
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

/**
 * The listing of everything is '' (a category id is never empty); the titles with an episode have their own listings,
 * under a prefix no source's category id starts with (a control character).
 */
private fun listing(
    categoryId: String?,
    episodes: Boolean = false,
) = if (episodes) EPISODES_LISTING + categoryId.orEmpty() else categoryId.orEmpty()

private const val EPISODES_LISTING = "\u0002episodes:"

private val languagesJson = Json { ignoreUnknownKeys = true }

/** Stored as a JSON array: `""` (the original) is a language like the others. */
private fun encodeLanguages(languages: List<String>): String? =
    if (languages.isEmpty()) null else languagesJson.encodeToString(ListSerializer(String.serializer()), languages)

private fun decodeLanguages(value: String?): List<String> =
    value?.let { runCatching { languagesJson.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() }.orEmpty()

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
    episodeCount: Long,
) = TitleSummary(
    sourceKey,
    id,
    name,
    coverUrl,
    ContentKind.fromApi(kind),
    ReadingFormat.fromApi(format),
    status,
    ContentRating.fromApi(contentRating),
    episodeCount.toInt(),
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
        episodeCount,
    )
