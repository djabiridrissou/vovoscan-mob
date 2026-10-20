package com.vovoscan.feature.catalog.data

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.map
import com.vovoscan.feature.catalog.domain.CatalogRepository
import com.vovoscan.feature.catalog.domain.SearchResult
import com.vovoscan.feature.catalog.domain.TitleDetail
import com.vovoscan.feature.catalog.domain.TitleSummary
import kotlinx.coroutines.flow.Flow

/** Stale-while-revalidate : la source locale s'affiche tout de suite, la source distante la remplace. */
class CatalogRepositoryImpl(
    private val remote: CatalogRemoteDataSource,
    private val local: CatalogLocalDataSource,
) : CatalogRepository {
    override fun observeTitles(): Flow<List<TitleSummary>> = local.observeCatalog()

    override suspend fun refreshTitles(): AppResult<Boolean> = fetchPage(1)

    override suspend fun loadMoreTitles(): AppResult<Boolean> {
        val next = local.nextPage() ?: return AppResult.Ok(false)
        return fetchPage(next)
    }

    override suspend fun search(query: String): SearchResult =
        when (val result = remote.search(query.trim())) {
            is AppResult.Ok -> {
                local.saveSummaries(result.value.data)
                SearchResult(result.value.data.map { it.toDomain() }, offline = false)
            }
            is AppResult.Err ->
                if (result.error == AppError.Network) {
                    SearchResult(local.searchLocal(query.trim()), offline = true)
                } else {
                    SearchResult(emptyList(), offline = false)
                }
        }

    override fun observeTitle(id: String): Flow<TitleDetail?> = local.observeDetail(id)

    override suspend fun refreshTitle(id: String): AppResult<Unit> =
        remote.title(id).also { if (it is AppResult.Ok) local.saveDetail(it.value) }.map { }

    override suspend fun clearLocal() = local.clear()

    private suspend fun fetchPage(page: Int): AppResult<Boolean> =
        remote.titles(page).also { if (it is AppResult.Ok) local.saveCatalogPage(it.value) }.map { it.hasNext }
}
