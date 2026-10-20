package com.vovoscan.feature.library.data

import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.map
import com.vovoscan.core.network.ApiData
import com.vovoscan.core.network.apiCall
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.Serializable

@Serializable
data class LibraryEntryDto(
    val sourceKey: String,
    val titleRef: String,
    val titleName: String,
    val coverUrl: String? = null,
    val lastChapterRef: String? = null,
    val lastPage: Int = 0,
    val updatedAt: String,
    val deleted: Boolean = false,
)

@Serializable
internal data class SaveEntryBody(
    val updatedAt: String,
    val lastChapterRef: String?,
    val lastPage: Int,
    val titleName: String?,
    val coverUrl: String?,
)

/** Source distante : `api/v1/library`. Écritures idempotentes, l'API rend l'état qui a gagné. */
interface LibraryRemoteDataSource {
    /** Avec [updatedSince] : changements depuis cette date, pierres tombales comprises. */
    suspend fun entries(updatedSince: String?): AppResult<List<LibraryEntryDto>>

    suspend fun save(
        sourceKey: String,
        titleRef: String,
        updatedAt: String,
        lastChapterRef: String?,
        lastPage: Int,
        titleName: String,
        coverUrl: String?,
    ): AppResult<LibraryEntryDto>

    suspend fun remove(
        sourceKey: String,
        titleRef: String,
        updatedAt: String,
    ): AppResult<LibraryEntryDto>
}

class KtorLibraryRemoteDataSource(
    private val http: HttpClient,
) : LibraryRemoteDataSource {
    private fun path(
        sourceKey: String,
        titleRef: String,
    ) = "api/v1/library/${sourceKey.encodeURLPathPart()}/${titleRef.encodeURLPathPart()}"

    override suspend fun entries(updatedSince: String?): AppResult<List<LibraryEntryDto>> =
        http
            .apiCall<ApiData<List<LibraryEntryDto>>> {
                get("api/v1/library") { updatedSince?.let { parameter("updatedSince", it) } }
            }.map { it.data }

    override suspend fun save(
        sourceKey: String,
        titleRef: String,
        updatedAt: String,
        lastChapterRef: String?,
        lastPage: Int,
        titleName: String,
        coverUrl: String?,
    ): AppResult<LibraryEntryDto> =
        http
            .apiCall<ApiData<LibraryEntryDto>> {
                put(path(sourceKey, titleRef)) {
                    contentType(ContentType.Application.Json)
                    setBody(SaveEntryBody(updatedAt, lastChapterRef, lastPage, titleName, coverUrl))
                }
            }.map { it.data }

    override suspend fun remove(
        sourceKey: String,
        titleRef: String,
        updatedAt: String,
    ): AppResult<LibraryEntryDto> =
        http
            .apiCall<ApiData<LibraryEntryDto>> {
                delete(path(sourceKey, titleRef)) { parameter("updatedAt", updatedAt) }
            }.map { it.data }
}
