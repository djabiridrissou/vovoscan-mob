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

/** Stale-while-revalidate: the local source shows at once, the remote source replaces it. */
class CatalogRepositoryImpl(
    private val remote: CatalogRemoteDataSource,
    private val local: CatalogLocalDataSource,
) : CatalogRepository {
    override fun observeListing(
        sourceKey: String,
        categoryId: String?,
    ): Flow<List<ListedTitle>> = local.observeListing(sourceKey, categoryId)

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
                val items = withLearnedFormats(sourceKey, result.value.items)
                local.saveSummaries(sourceKey, items)
                SearchResult(items.map { it.toDomain(sourceKey) }, offline = false)
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

    override suspend fun clearSource(sourceKey: String) = local.clearSource(sourceKey)

    private suspend fun fetchPage(
        sourceKey: String,
        page: Int,
        categoryId: String?,
    ): AppResult<Boolean> =
        remote
            .titles(sourceKey, page, categoryId)
            .map { it.copy(items = withLearnedFormats(sourceKey, it.items)) }
            .also {
                if (it is AppResult.Ok) local.saveCatalogPage(sourceKey, it.value, categoryId)
            }.map { it.hasNext }

    /** A source whose catalog names no format: the formats the server learned from imports replace the default (manga). */
    private suspend fun withLearnedFormats(
        sourceKey: String,
        items: List<TitleSummaryDto>,
    ): List<TitleSummaryDto> {
        val learned = remote.learnedFormats(sourceKey, items.map { it.id })
        return if (learned.isEmpty()) items else items.map { item -> learned[item.id]?.let { item.copy(format = it) } ?: item }
    }
}
