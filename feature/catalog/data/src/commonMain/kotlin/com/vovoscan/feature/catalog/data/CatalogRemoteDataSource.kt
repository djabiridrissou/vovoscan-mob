package com.vovoscan.feature.catalog.data

import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.map
import com.vovoscan.core.network.ApiData
import com.vovoscan.core.network.ApiPage
import com.vovoscan.core.network.apiCall
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.Serializable

@Serializable
data class TitleSummaryDto(
    val id: String,
    val name: String,
    val coverUrl: String? = null,
    val format: String,
    val status: String,
)

@Serializable
data class ChapterDto(
    val id: String,
    val number: Double,
    val name: String? = null,
    val pageCount: Int,
    val publishedAt: String? = null,
)

@Serializable
data class TitleDetailDto(
    val id: String,
    val name: String,
    val coverUrl: String? = null,
    val format: String,
    val status: String,
    val description: String = "",
    val authors: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val chapters: List<ChapterDto> = emptyList(),
)

/**
 * Source distante : `api/v1/catalog/…`. Le catalogue se parcourt par source (« vovoscan » : nos titres) ;
 * le serveur ne rend que les titres publiés.
 */
interface CatalogRemoteDataSource {
    suspend fun titles(page: Int): AppResult<ApiPage<TitleSummaryDto>>

    suspend fun search(query: String): AppResult<ApiPage<TitleSummaryDto>>

    suspend fun title(id: String): AppResult<TitleDetailDto>
}

class KtorCatalogRemoteDataSource(
    private val http: HttpClient,
    private val sourceKey: String = DEFAULT_SOURCE,
) : CatalogRemoteDataSource {
    override suspend fun titles(page: Int): AppResult<ApiPage<TitleSummaryDto>> =
        http.apiCall {
            get("api/v1/catalog/sources/$sourceKey/titles") {
                parameter("page", page)
                parameter("perPage", PAGE_SIZE)
            }
        }

    override suspend fun search(query: String): AppResult<ApiPage<TitleSummaryDto>> =
        http.apiCall {
            get("api/v1/catalog/sources/$sourceKey/titles") {
                parameter("search", query)
                parameter("perPage", PAGE_SIZE)
            }
        }

    override suspend fun title(id: String): AppResult<TitleDetailDto> =
        http.apiCall<ApiData<TitleDetailDto>> { get("api/v1/catalog/titles/$id") }.map { it.data }

    companion object {
        /** Notre propre source, la seule pour l'instant. */
        const val DEFAULT_SOURCE = "vovoscan"
        private const val PAGE_SIZE = 24
    }
}
