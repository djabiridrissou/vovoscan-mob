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
import com.vovoscan.core.sources.declaresFormat
import com.vovoscan.feature.catalog.domain.ReadingFormat
import com.vovoscan.feature.catalog.domain.SourceCategory
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.Serializable

@Serializable
data class TitleSummaryDto(
    val id: String,
    val name: String,
    val coverUrl: String? = null,
    val kind: String = "comic",
    val format: String,
    val status: String,
    val contentRating: String? = null,
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
    val contentRating: String? = null,
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
    /** `manga`, `webtoon` or `text` for our default categories (the format they stand for); null for a curated one. */
    val format: String? = null,
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

    /**
     * Title ref → format (`manga`, `webtoon`) the server learned from imported pages, for a source read on the device
     * whose catalog names no format; empty otherwise, offline, or for refs it has not seen.
     */
    suspend fun learnedFormats(
        sourceKey: String,
        refs: List<String>,
    ): Map<String, String> = emptyMap()
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
            .map { list ->
                list.data.sortedBy { it.position }.map { SourceCategory(it.key, it.name, it.format?.let(ReadingFormat::fromApi)) }
            }

    override suspend fun title(
        sourceKey: String,
        id: String,
    ): AppResult<TitleDetailDto> = http.apiCall<ApiData<TitleDetailDto>> { get("api/v1/catalog/titles/$id") }.map { it.data }

    override suspend fun learnedFormats(
        sourceKey: String,
        refs: List<String>,
    ): Map<String, String> =
        refs.distinct().chunked(MAX_FORMAT_REFS).fold(emptyMap()) { found, chunk ->
            val result =
                http.apiCall<ApiData<Map<String, String>>> {
                    get("api/v1/source-reader/${sourceKey.encodeURLPathPart()}/formats") { chunk.forEach { parameter("ref", it) } }
                }
            found + ((result as? AppResult.Ok)?.value?.data ?: return found)
        }

    private companion object {
        const val PAGE_SIZE = 24

        /** Refs per formats request (the server's limit). */
        const val MAX_FORMAT_REFS = 100
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
                contentRating = title.summary.contentRating,
                description = title.description,
                authors = title.authors,
                genres = title.genres,
                // Newest to oldest, like the API.
                chapters = title.units.reversed().map { ChapterDto(it.id, it.number, it.name) },
            )
        } ?: unknown()

    private fun dto(item: RulesTitleSummary) =
        TitleSummaryDto(item.id, item.name, item.coverUrl, item.kind, item.format, item.status, item.contentRating)

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

    /** Asked of the API for a source read on the device whose catalog names no format; nothing for the others. */
    override suspend fun learnedFormats(
        sourceKey: String,
        refs: List<String>,
    ): Map<String, String> {
        val manifest = manifests.manifest(sourceKey) ?: return emptyMap()
        return if (manifest.declaresFormat || refs.isEmpty()) emptyMap() else api.learnedFormats(sourceKey, refs)
    }
}
