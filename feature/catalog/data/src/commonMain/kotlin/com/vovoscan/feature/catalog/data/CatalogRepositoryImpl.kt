package com.vovoscan.feature.catalog.data

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.map
import com.vovoscan.feature.catalog.domain.CatalogRepository
import com.vovoscan.feature.catalog.domain.SearchResult
import com.vovoscan.feature.catalog.domain.TitleDetail
import com.vovoscan.feature.catalog.domain.TitleSummary
import kotlinx.coroutines.flow.Flow

/** Stale-while-revalidate: the local source shows at once, the remote source replaces it. */
class CatalogRepositoryImpl(
    private val remote: CatalogRemoteDataSource,
    private val local: CatalogLocalDataSource,
) : CatalogRepository {
    override fun observeTitles(sourceKey: String): Flow<List<TitleSummary>> = local.observeCatalog(sourceKey)

    override suspend fun refreshTitles(sourceKey: String): AppResult<Boolean> = fetchPage(sourceKey, 1)

    override suspend fun loadMoreTitles(sourceKey: String): AppResult<Boolean> {
        val next = local.nextPage(sourceKey) ?: return AppResult.Ok(false)
        return fetchPage(sourceKey, next)
    }

    override suspend fun search(
        sourceKey: String,
        query: String,
    ): SearchResult =
        when (val result = remote.search(sourceKey, query.trim())) {
            is AppResult.Ok -> {
                local.saveSummaries(sourceKey, result.value.items)
                SearchResult(result.value.items.map { it.toDomain(sourceKey) }, offline = false)
            }
            is AppResult.Err ->
                if (result.error == AppError.Network) {
                    SearchResult(local.searchLocal(sourceKey, query.trim()), offline = true)
                } else {
                    SearchResult(emptyList(), offline = false)
                }
        }

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
    ): AppResult<Boolean> =
        remote
            .titles(sourceKey, page)
            .also {
                if (it is AppResult.Ok) local.saveCatalogPage(sourceKey, it.value)
            }.map { it.hasNext }
}
