package com.vovoscan.feature.catalog.presentation

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.feature.catalog.domain.CatalogFilter
import com.vovoscan.feature.catalog.domain.CatalogRepository
import com.vovoscan.feature.catalog.domain.ContentKind
import com.vovoscan.feature.catalog.domain.ListedTitle
import com.vovoscan.feature.catalog.domain.ReadingFormat
import com.vovoscan.feature.catalog.domain.SearchResult
import com.vovoscan.feature.catalog.domain.SourceCategory
import com.vovoscan.feature.catalog.domain.TitleDetail
import com.vovoscan.feature.catalog.domain.TitleSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

fun title(
    source: String,
    id: String,
    format: ReadingFormat = ReadingFormat.Manga,
) = TitleSummary(
    source,
    id,
    "$source $id",
    null,
    if (format ==
        ReadingFormat.Text
    ) {
        ContentKind.Book
    } else {
        ContentKind.Comic
    },
    format,
    "ongoing",
)

/**
 * Sources in memory: each has pages of titles per listing (category id or null), categories, and can fail with
 * `failures[key]`. The saved copy (`saved`) is what a refresh has written, like the real repository.
 */
class FakeCatalog : CatalogRepository {
    /** Remote pages per (source, category id): page n is pages[n - 1]. */
    val remote = mutableMapOf<Pair<String, String?>, List<List<TitleSummary>>>()
    val categories = mutableMapOf<String, List<SourceCategory>>()
    val failures = mutableMapOf<String, AppError>()
    val saved = MutableStateFlow<Map<Pair<String, String?>, List<ListedTitle>>>(emptyMap())
    private val savedCategories = MutableStateFlow<Map<String, List<SourceCategory>>>(emptyMap())
    val filter = MutableStateFlow(CatalogFilter())
    val newestFirst = MutableStateFlow(false)
    val refreshed = mutableListOf<Pair<String, String?>>()
    val searched = mutableListOf<Triple<String, String, String?>>()
    var detail = MutableStateFlow<TitleDetail?>(null)
    var titleResult: AppResult<Unit> = AppResult.Ok(Unit)
    private val loadedPages = mutableMapOf<Pair<String, String?>, Int>()

    /** What the server learned of titles read on the device ("<source>/<id>" → format), and what was asked of it. */
    val serverFormats = mutableMapOf<String, ReadingFormat>()
    val formatsAsked = mutableListOf<Pair<String, List<String>>>()
    private val learned = MutableStateFlow<Map<String, ReadingFormat>>(emptyMap())

    override fun observeListing(
        sourceKey: String,
        categoryId: String?,
    ): Flow<List<ListedTitle>> =
        saved.combine(learned) { all, known ->
            all[sourceKey to categoryId].orEmpty().map { t ->
                known["$sourceKey/${t.title.id}"]?.let { t.copy(title = t.title.copy(format = it)) } ?: t
            }
        }

    override suspend fun learnFormats(
        sourceKey: String,
        ids: List<String>,
    ): Map<String, ReadingFormat> {
        val fresh = ids.filter { "$sourceKey/$it" !in learned.value && formatsAsked.none { (s, asked) -> s == sourceKey && it in asked } }
        if (fresh.isNotEmpty()) {
            formatsAsked += sourceKey to fresh
            learned.value += fresh.mapNotNull { id -> serverFormats["$sourceKey/$id"]?.let { "$sourceKey/$id" to it } }
        }
        return ids.mapNotNull { id -> learned.value["$sourceKey/$id"]?.let { id to it } }.toMap()
    }

    override suspend fun refreshListing(
        sourceKey: String,
        categoryId: String?,
    ): AppResult<Boolean> {
        refreshed += sourceKey to categoryId
        failures[sourceKey]?.let { return AppResult.Err(it) }
        val pages = remote[sourceKey to categoryId].orEmpty()
        loadedPages[sourceKey to categoryId] = 1
        saved.value = saved.value + ((sourceKey to categoryId) to pages.firstOrNull().orEmpty().map { ListedTitle(it, 1) })
        return AppResult.Ok(pages.size > 1)
    }

    override suspend fun loadMoreListing(
        sourceKey: String,
        categoryId: String?,
    ): AppResult<Boolean> {
        failures[sourceKey]?.let { return AppResult.Err(it) }
        val key = sourceKey to categoryId
        val pages = remote[key].orEmpty()
        val next = (loadedPages[key] ?: 0) + 1
        if (next > pages.size) return AppResult.Ok(false)
        loadedPages[key] = next
        saved.value = saved.value + (key to saved.value[key].orEmpty() + pages[next - 1].map { ListedTitle(it, next) })
        return AppResult.Ok(pages.size > next)
    }

    override suspend fun hasMore(
        sourceKey: String,
        categoryId: String?,
    ) = (loadedPages[sourceKey to categoryId] ?: 0) < remote[sourceKey to categoryId].orEmpty().size

    override suspend fun search(
        sourceKey: String,
        query: String,
        categoryId: String?,
    ): SearchResult {
        searched += Triple(sourceKey, query, categoryId)
        val pool = remote[sourceKey to categoryId].orEmpty().flatten().filter { it.name.contains(query, ignoreCase = true) }
        return when (val failure = failures[sourceKey]) {
            null -> SearchResult(pool, offline = false)
            AppError.Network -> SearchResult(savedMatching(sourceKey, categoryId, query), offline = true)
            is AppError.SourceUnavailable ->
                SearchResult(
                    savedMatching(sourceKey, categoryId, query),
                    offline = true,
                    unavailableSource = failure.sourceName,
                )
            else -> SearchResult(emptyList(), offline = false)
        }
    }

    private fun savedMatching(
        sourceKey: String,
        categoryId: String?,
        query: String,
    ) = saved.value[sourceKey to categoryId]
        .orEmpty()
        .map { it.title }
        .filter { it.name.contains(query, ignoreCase = true) }

    override fun observeCategories(sourceKey: String): Flow<List<SourceCategory>> = savedCategories.map { it[sourceKey].orEmpty() }

    override suspend fun refreshCategories(sourceKey: String): AppResult<Unit> {
        failures[sourceKey]?.let { return AppResult.Err(it) }
        savedCategories.value = savedCategories.value + (sourceKey to categories[sourceKey].orEmpty())
        return AppResult.Ok(Unit)
    }

    override fun observeFilter(): Flow<CatalogFilter> = filter

    override suspend fun saveFilter(filter: CatalogFilter) {
        this.filter.value = filter
    }

    override fun observeNewestFirst(): Flow<Boolean> = newestFirst

    override suspend fun saveNewestFirst(newestFirst: Boolean) {
        this.newestFirst.value = newestFirst
    }

    override fun observeTitle(
        sourceKey: String,
        id: String,
    ): Flow<TitleDetail?> = detail

    override suspend fun refreshTitle(
        sourceKey: String,
        id: String,
    ) = titleResult

    override suspend fun clearLocal() = Unit

    override suspend fun clearSource(sourceKey: String) = Unit
}
