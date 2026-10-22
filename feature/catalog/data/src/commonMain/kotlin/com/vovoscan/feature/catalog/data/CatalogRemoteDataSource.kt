package com.vovoscan.feature.catalog.data

import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.map
import com.vovoscan.core.network.ApiData
import com.vovoscan.core.network.ApiPage
import com.vovoscan.core.network.apiCall
import com.vovoscan.core.sources.ManifestProvider
import com.vovoscan.core.sources.RulesHttp
import com.vovoscan.core.sources.RulesSource
import com.vovoscan.core.sources.RulesTitleSummary
import com.vovoscan.feature.catalog.domain.SourceCategory
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.Serializable

@Serializable
data class TitleSummaryDto(
    val id: String,
    val name: String,
    val coverUrl: String? = null,
    val kind: String = "comic",
    val format: String,
    val status: String,
)

@Serializable
data class ChapterDto(
    val id: String,
    val number: Double,
    val name: String? = null,
    val pageCount: Int = 0,
    val publishedAt: String? = null,
)

@Serializable
data class TitleDetailDto(
    val id: String,
    val name: String,
    val coverUrl: String? = null,
    val kind: String = "comic",
    val format: String,
    val status: String,
    val description: String = "",
    val authors: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val chapters: List<ChapterDto> = emptyList(),
)

@Serializable
data class CategoryDto(
    val key: String,
    val name: String,
    val position: Int = 0,
)

/** A catalog page, whatever the source. */
data class TitlesPage(
    val page: Int,
    val items: List<TitleSummaryDto>,
    val hasNext: Boolean,
)

/** Remote source of a catalog: the vovoscan API for the internal source, the source itself for the others. */
interface CatalogRemoteDataSource {
    /** `categoryId`: one of the source's categories, null for everything. */
    suspend fun titles(
        sourceKey: String,
        page: Int,
        categoryId: String? = null,
    ): AppResult<TitlesPage>

    suspend fun search(
        sourceKey: String,
        query: String,
        categoryId: String? = null,
    ): AppResult<TitlesPage>

    suspend fun categories(sourceKey: String): AppResult<List<SourceCategory>>

    suspend fun title(
        sourceKey: String,
        id: String,
    ): AppResult<TitleDetailDto>
}

/** `api/v1/catalog/…`: the internal source (our published titles). */
class ApiCatalogRemoteDataSource(
    private val http: HttpClient,
) : CatalogRemoteDataSource {
    override suspend fun titles(
        sourceKey: String,
        page: Int,
        categoryId: String?,
    ): AppResult<TitlesPage> =
        http
            .apiCall<ApiPage<TitleSummaryDto>> {
                get("api/v1/catalog/sources/$sourceKey/titles") {
                    parameter("page", page)
                    parameter("perPage", PAGE_SIZE)
                    categoryId?.let { parameter("category", it) }
                }
            }.map { TitlesPage(it.page, it.data, it.hasNext) }

    override suspend fun search(
        sourceKey: String,
        query: String,
        categoryId: String?,
    ): AppResult<TitlesPage> =
        http
            .apiCall<ApiPage<TitleSummaryDto>> {
                get("api/v1/catalog/sources/$sourceKey/titles") {
                    parameter("search", query)
                    parameter("perPage", PAGE_SIZE)
                    categoryId?.let { parameter("category", it) }
                }
            }.map { TitlesPage(it.page, it.data, it.hasNext) }

    /** The internal catalog's categories (their `key` is the id sent back as `category`). */
    override suspend fun categories(sourceKey: String): AppResult<List<SourceCategory>> =
        http
            .apiCall<ApiData<List<CategoryDto>>> { get("api/v1/catalog/sources/$sourceKey/categories") }
            .map { list -> list.data.sortedBy { it.position }.map { SourceCategory(it.key, it.name) } }

    override suspend fun title(
        sourceKey: String,
        id: String,
    ): AppResult<TitleDetailDto> = http.apiCall<ApiData<TitleDetailDto>> { get("api/v1/catalog/titles/$id") }.map { it.data }

    private companion object {
        const val PAGE_SIZE = 24
    }
}

/** Sources read on the device: the manifest is interpreted here and requests are made from here, never through vovoscan. */
class OnDeviceCatalogRemoteDataSource(
    private val manifests: ManifestProvider,
    private val http: RulesHttp,
) : CatalogRemoteDataSource {
    private suspend fun source(key: String): RulesSource? = manifests.manifest(key)?.let { RulesSource(it, http) }

    override suspend fun titles(
        sourceKey: String,
        page: Int,
        categoryId: String?,
    ): AppResult<TitlesPage> =
        source(sourceKey)?.catalog(page, category = categoryId.orEmpty())?.map { TitlesPage(page, it.items.map(::dto), it.hasNext) }
            ?: unknown()

    override suspend fun search(
        sourceKey: String,
        query: String,
        categoryId: String?,
    ): AppResult<TitlesPage> =
        source(sourceKey)?.catalog(1, query, categoryId.orEmpty())?.map { TitlesPage(1, it.items.map(::dto), it.hasNext) } ?: unknown()

    override suspend fun categories(sourceKey: String): AppResult<List<SourceCategory>> =
        source(sourceKey)?.categories()?.map { list -> list.map { SourceCategory(it.id, it.name) } } ?: unknown()

    override suspend fun title(
        sourceKey: String,
        id: String,
    ): AppResult<TitleDetailDto> =
        source(sourceKey)?.title(id)?.map { title ->
            TitleDetailDto(
                id = title.summary.id,
                name = title.summary.name,
                coverUrl = title.summary.coverUrl,
                kind = title.summary.kind,
                format = title.summary.format,
                status = title.summary.status,
                description = title.description,
                authors = title.authors,
                genres = title.genres,
                // Newest to oldest, like the API.
                chapters = title.units.reversed().map { ChapterDto(it.id, it.number, it.name) },
            )
        } ?: unknown()

    private fun dto(item: RulesTitleSummary) = TitleSummaryDto(item.id, item.name, item.coverUrl, item.kind, item.format, item.status)

    private fun <T> unknown(): AppResult<T> =
        AppResult.Err(
            com.vovoscan.core.common.AppError
                .Rejected("SOURCE_NOT_FOUND", "This source isn't available on this device yet."),
        )
}

/** Routing: a source with a manifest is read on the device; otherwise it is the internal source, through the API. */
class RoutingCatalogRemoteDataSource(
    private val manifests: ManifestProvider,
    private val api: CatalogRemoteDataSource,
    private val onDevice: CatalogRemoteDataSource,
) : CatalogRemoteDataSource {
    private suspend fun pick(key: String) = if (manifests.manifest(key) != null) onDevice else api

    override suspend fun titles(
        sourceKey: String,
        page: Int,
        categoryId: String?,
    ) = pick(sourceKey).titles(sourceKey, page, categoryId)

    override suspend fun search(
        sourceKey: String,
        query: String,
        categoryId: String?,
    ) = pick(sourceKey).search(sourceKey, query, categoryId)

    override suspend fun categories(sourceKey: String) = pick(sourceKey).categories(sourceKey)

    override suspend fun title(
        sourceKey: String,
        id: String,
    ) = pick(sourceKey).title(sourceKey, id)
}
