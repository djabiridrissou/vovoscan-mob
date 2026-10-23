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
    val favorite: Boolean = false,
    val favoritedAt: String? = null,
    /** The heart's own sync clock. */
    val favoriteUpdatedAt: String? = null,
    /** Our catalog's titles only (null for a source read on the device). */
    val chapterCount: Int? = null,
    val newChapters: Int? = null,
)

@Serializable
internal data class SaveEntryBody(
    val updatedAt: String,
    val lastChapterRef: String?,
    val lastPage: Int,
    val titleName: String?,
    val coverUrl: String?,
)

@Serializable
internal data class FavoriteBody(
    val updatedAt: String,
    val titleName: String?,
    val coverUrl: String?,
)

/** Remote source: `api/v1/library`. Idempotent writes; the API returns the winning state. */
interface LibraryRemoteDataSource {
    /** With [updatedSince]: changes since that date, tombstones included. */
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

    /** `PUT favorites/{source}/{ref}`: the heart (follows the title when it isn't). */
    suspend fun favorite(
        sourceKey: String,
        titleRef: String,
        updatedAt: String,
        titleName: String,
        coverUrl: String?,
    ): AppResult<LibraryEntryDto>

    /** `DELETE favorites/{source}/{ref}`: the title stays followed. */
    suspend fun unfavorite(
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

    // `favorites/…`, never `library/favorites/…`: "favorites" is a valid source key.
    private fun favoritePath(
        sourceKey: String,
        titleRef: String,
    ) = "api/v1/favorites/${sourceKey.encodeURLPathPart()}/${titleRef.encodeURLPathPart()}"

    override suspend fun favorite(
        sourceKey: String,
        titleRef: String,
        updatedAt: String,
        titleName: String,
        coverUrl: String?,
    ): AppResult<LibraryEntryDto> =
        http
            .apiCall<ApiData<LibraryEntryDto>> {
                put(favoritePath(sourceKey, titleRef)) {
                    contentType(ContentType.Application.Json)
                    setBody(FavoriteBody(updatedAt, titleName, coverUrl))
                }
            }.map { it.data }

    override suspend fun unfavorite(
        sourceKey: String,
        titleRef: String,
        updatedAt: String,
    ): AppResult<LibraryEntryDto> =
        http
            .apiCall<ApiData<LibraryEntryDto>> {
                delete(favoritePath(sourceKey, titleRef)) { parameter("updatedAt", updatedAt) }
            }.map { it.data }
}
