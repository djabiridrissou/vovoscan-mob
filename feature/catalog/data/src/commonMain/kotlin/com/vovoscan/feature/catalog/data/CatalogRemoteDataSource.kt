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

/** Une page de catalogue, quelle que soit la source. */
data class TitlesPage(
    val page: Int,
    val items: List<TitleSummaryDto>,
    val hasNext: Boolean,
)

/** Source distante d'un catalogue : l'API vovoscan pour la source interne, la source elle-même pour les autres. */
interface CatalogRemoteDataSource {
    suspend fun titles(
        sourceKey: String,
        page: Int,
    ): AppResult<TitlesPage>

    suspend fun search(
        sourceKey: String,
        query: String,
    ): AppResult<TitlesPage>

    suspend fun title(
        sourceKey: String,
        id: String,
    ): AppResult<TitleDetailDto>
}

/** `api/v1/catalog/…` : la source interne (nos titres publiés). */
class ApiCatalogRemoteDataSource(
    private val http: HttpClient,
) : CatalogRemoteDataSource {
    override suspend fun titles(
        sourceKey: String,
        page: Int,
    ): AppResult<TitlesPage> =
        http
            .apiCall<ApiPage<TitleSummaryDto>> {
                get("api/v1/catalog/sources/$sourceKey/titles") {
                    parameter("page", page)
                    parameter("perPage", PAGE_SIZE)
                }
            }.map { TitlesPage(it.page, it.data, it.hasNext) }

    override suspend fun search(
        sourceKey: String,
        query: String,
    ): AppResult<TitlesPage> =
        http
            .apiCall<ApiPage<TitleSummaryDto>> {
                get("api/v1/catalog/sources/$sourceKey/titles") {
                    parameter("search", query)
                    parameter("perPage", PAGE_SIZE)
                }
            }.map { TitlesPage(it.page, it.data, it.hasNext) }

    override suspend fun title(
        sourceKey: String,
        id: String,
    ): AppResult<TitleDetailDto> = http.apiCall<ApiData<TitleDetailDto>> { get("api/v1/catalog/titles/$id") }.map { it.data }

    private companion object {
        const val PAGE_SIZE = 24
    }
}

/** Sources lues sur l'appareil : le manifeste interprété ici, les requêtes faites d'ici, jamais par vovoscan. */
class OnDeviceCatalogRemoteDataSource(
    private val manifests: ManifestProvider,
    private val http: RulesHttp,
) : CatalogRemoteDataSource {
    private suspend fun source(key: String): RulesSource? = manifests.manifest(key)?.let { RulesSource(it, http) }

    override suspend fun titles(
        sourceKey: String,
        page: Int,
    ): AppResult<TitlesPage> = source(sourceKey)?.catalog(page)?.map { TitlesPage(page, it.items.map(::dto), it.hasNext) } ?: unknown()

    override suspend fun search(
        sourceKey: String,
        query: String,
    ): AppResult<TitlesPage> = source(sourceKey)?.catalog(1, query)?.map { TitlesPage(1, it.items.map(::dto), it.hasNext) } ?: unknown()

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
                // Du plus récent au plus ancien, comme l'API.
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

/** Aiguillage : une source qui a un manifeste se lit sur l'appareil ; sinon c'est la source interne, par l'API. */
class RoutingCatalogRemoteDataSource(
    private val manifests: ManifestProvider,
    private val api: CatalogRemoteDataSource,
    private val onDevice: CatalogRemoteDataSource,
) : CatalogRemoteDataSource {
    private suspend fun pick(key: String) = if (manifests.manifest(key) != null) onDevice else api

    override suspend fun titles(
        sourceKey: String,
        page: Int,
    ) = pick(sourceKey).titles(sourceKey, page)

    override suspend fun search(
        sourceKey: String,
        query: String,
    ) = pick(sourceKey).search(sourceKey, query)

    override suspend fun title(
        sourceKey: String,
        id: String,
    ) = pick(sourceKey).title(sourceKey, id)
}
