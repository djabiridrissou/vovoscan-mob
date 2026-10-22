package com.vovoscan.feature.catalog.data

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.map
import com.vovoscan.feature.catalog.domain.CatalogFilter
import com.vovoscan.feature.catalog.domain.CatalogRepository
import com.vovoscan.feature.catalog.domain.ListedTitle
import com.vovoscan.feature.catalog.domain.ReadingFormat
import com.vovoscan.feature.catalog.domain.SearchResult
import com.vovoscan.feature.catalog.domain.SourceCategory
import com.vovoscan.feature.catalog.domain.TitleDetail
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Stale-while-revalidate: the local source shows at once, the remote source replaces it. */
class CatalogRepositoryImpl(
    private val remote: CatalogRemoteDataSource,
    private val local: CatalogLocalDataSource,
) : CatalogRepository {
    override fun observeListing(
        sourceKey: String,
        categoryId: String?,
    ): Flow<List<ListedTitle>> =
        local.observeListing(sourceKey, categoryId).combine(learned) { titles, all ->
            val known = all[sourceKey].orEmpty()
            // Only over the default: a strip the device read from the title's genres stays one.
            if (known.isEmpty()) {
                titles
            } else {
                titles.map { t ->
                    known[t.title.id]?.takeIf { t.title.format == ReadingFormat.Manga }?.let { t.copy(title = t.title.copy(format = it)) }
                        ?: t
                }
            }
        }

    /** Formats learned from the server this run, per source (kept in memory: they only serve a filter). */
    private val learned = MutableStateFlow<Map<String, Map<String, ReadingFormat>>>(emptyMap())
    private val asked = mutableMapOf<String, MutableSet<String>>()
    private val askedLock = Mutex()

    override suspend fun learnFormats(
        sourceKey: String,
        ids: List<String>,
    ): Map<String, ReadingFormat> {
        val fresh = askedLock.withLock { asked.getOrPut(sourceKey) { mutableSetOf() }.let { seen -> ids.filter(seen::add) } }
        if (fresh.isNotEmpty()) {
            val raw = remote.learnedFormats(sourceKey, fresh)
            val found = raw.map { (id, f) -> id to ReadingFormat.fromApi(f) }
            if (found.isNotEmpty()) {
                learned.update { all -> all + (sourceKey to all[sourceKey].orEmpty() + found) }
                // Saved too: "All categories" and the next launch show what the server said.
                local.saveLearnedFormats(sourceKey, raw.filterValues { it == WEBTOON })
            }
        }
        val known = learned.value[sourceKey].orEmpty()
        return ids.mapNotNull { id -> known[id]?.let { id to it } }.toMap()
    }

    override fun askFormatsAgain(sourceKey: String) {
        val known = learned.value[sourceKey].orEmpty()
        // Under the lock when it is free; a call racing an ask simply asks again next time.
        if (askedLock.tryLock()) {
            try {
                asked[sourceKey]?.retainAll(known.keys)
            } finally {
                askedLock.unlock()
            }
        }
    }

    override suspend fun refreshListing(
        sourceKey: String,
        categoryId: String?,
    ): AppResult<Boolean> = fetchPage(sourceKey, 1, categoryId)

    override suspend fun loadMoreListing(
        sourceKey: String,
        categoryId: String?,
    ): AppResult<Boolean> {
        val next = local.nextPage(sourceKey, categoryId) ?: return AppResult.Ok(false)
        return fetchPage(sourceKey, next, categoryId)
    }

    override suspend fun hasMore(
        sourceKey: String,
        categoryId: String?,
    ): Boolean = local.nextPage(sourceKey, categoryId) != null

    override suspend fun search(
        sourceKey: String,
        query: String,
        categoryId: String?,
    ): SearchResult =
        when (val result = remote.search(sourceKey, query.trim(), categoryId)) {
            is AppResult.Ok -> {
                val items = result.value.items
                local.saveSummaries(sourceKey, items)
                // As the grid shows them: a source that names no format takes the format saved from the title page,
                // and a webtoon the server learned (kept here and saved) beats a manga in every result.
                val guessed = items.filter { !it.formatKnown || it.format == MANGA }.map { it.id }
                val saved = if (guessed.isEmpty()) emptyMap() else local.savedFormats(sourceKey, guessed)
                val known = learned.value[sourceKey].orEmpty()
                val coverless = items.filter { it.coverUrl == null }.map { it.id }
                val covers = if (coverless.isEmpty()) emptyMap() else local.savedCovers(sourceKey, coverless)
                SearchResult(
                    items.map { item ->
                        val format =
                            when {
                                known[item.id] == ReadingFormat.Webtoon && item.format == MANGA -> WEBTOON
                                !item.formatKnown -> saved[item.id]
                                item.format == MANGA && saved[item.id] == WEBTOON -> WEBTOON
                                else -> null
                            }
                        val cover = item.coverUrl ?: covers[item.id]
                        (format?.let { item.copy(format = it) } ?: item).copy(coverUrl = cover).toDomain(sourceKey)
                    },
                    offline = false,
                )
            }
            is AppResult.Err ->
                when (val error = result.error) {
                    AppError.Network -> SearchResult(local.searchLocal(sourceKey, query.trim(), categoryId), offline = true)
                    is AppError.SourceUnavailable ->
                        SearchResult(
                            local.searchLocal(sourceKey, query.trim(), categoryId),
                            offline = true,
                            unavailableSource = error.sourceName,
                        )
                    else -> SearchResult(emptyList(), offline = false)
                }
        }

    /** The format each category stands for, as last read (kept for the app's run: the table has no column for it). */
    private val categoryFormats = MutableStateFlow<Map<String, Map<String, ReadingFormat>>>(emptyMap())

    override fun observeCategories(sourceKey: String): Flow<List<SourceCategory>> =
        local.observeCategories(sourceKey).combine(categoryFormats) { list, formats ->
            val known = formats[sourceKey].orEmpty()
            list.map { category -> known[category.id]?.let { category.copy(format = it) } ?: category }
        }

    override suspend fun refreshCategories(sourceKey: String): AppResult<Unit> =
        remote
            .categories(sourceKey)
            .also {
                if (it is AppResult.Ok) {
                    val formats = it.value.mapNotNull { c -> c.format?.let { f -> c.id to f } }.toMap()
                    categoryFormats.update { all -> all + (sourceKey to formats) }
                    local.saveCategories(sourceKey, it.value)
                }
            }.map { }

    override fun observeFilter(): Flow<CatalogFilter> = local.observeFilter()

    override suspend fun saveFilter(filter: CatalogFilter) = local.saveFilter(filter)

    override fun observeNewestFirst(): Flow<Boolean> = local.observeNewestFirst()

    override suspend fun saveNewestFirst(newestFirst: Boolean) = local.saveNewestFirst(newestFirst)

    override fun observeTitle(
        sourceKey: String,
        id: String,
    ): Flow<TitleDetail?> = local.observeDetail(sourceKey, id)

    override suspend fun refreshTitle(
        sourceKey: String,
        id: String,
    ): AppResult<Unit> = remote.title(sourceKey, id).also { if (it is AppResult.Ok) local.saveDetail(sourceKey, it.value) }.map { }

    override suspend fun clearLocal() = local.clear()

    override suspend fun clearSource(sourceKey: String) {
        learned.update { it - sourceKey }
        askedLock.withLock { asked.remove(sourceKey) }
        local.clearSource(sourceKey)
    }

    private suspend fun fetchPage(
        sourceKey: String,
        page: Int,
        categoryId: String?,
    ): AppResult<Boolean> =
        remote
            .titles(sourceKey, page, categoryId)
            .also {
                if (it is AppResult.Ok) local.saveCatalogPage(sourceKey, it.value, categoryId)
            }.map { it.hasNext }

    private companion object {
        /** The one learned format kept on the device (over the manga default). */
        const val WEBTOON = "webtoon"

        /** The default a source's listing gives a comic it names no format for. */
        const val MANGA = "manga"
    }
}
