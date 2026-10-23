package com.vovoscan.feature.reader.data

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.INTERNAL_SOURCE_KEY
import com.vovoscan.core.common.isoUtc
import com.vovoscan.core.common.parseIsoMillis
import com.vovoscan.core.network.ApiData
import com.vovoscan.core.network.apiCall
import com.vovoscan.feature.reader.domain.AccountPlace
import com.vovoscan.feature.reader.domain.HistorySync
import com.vovoscan.feature.reader.domain.HistoryTitles
import com.vovoscan.feature.reader.domain.LastRead
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.Serializable

@Serializable
data class PlaceDto(
    val sourceKey: String,
    val titleRef: String,
    val chapterRef: String,
    val page: Int = 0,
    val updatedAt: String,
    val deleted: Boolean = false,
)

@Serializable
internal data class SavePlaceBody(
    val chapterRef: String,
    val chapterId: String?,
    val page: Int,
    val updatedAt: String,
    val titleName: String?,
    val coverUrl: String?,
)

/** The account's reading history (`api/v1/reading-history`, reader/SPEC.md §9). */
class ApiHistorySync(
    private val http: HttpClient,
    private val titles: HistoryTitles,
) : HistorySync {
    override suspend fun push(read: LastRead): Boolean {
        val internal = read.sourceKey == INTERNAL_SOURCE_KEY
        val title = if (internal) null else titles.title(read.sourceKey, read.titleId) ?: return false
        val body =
            SavePlaceBody(
                chapterRef = read.chapterId,
                chapterId = read.chapterId.takeIf { internal && UUID.matches(it) },
                page = read.page,
                updatedAt = isoUtc(read.readAt),
                titleName = title?.name,
                coverUrl = title?.coverUrl?.takeIf { it.startsWith("https://") },
            )
        val result =
            http.apiCall<ApiData<PlaceDto>> {
                put("api/v1/reading-history/${read.sourceKey.encodeURLPathPart()}/${read.titleId.encodeURLPathPart()}") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
        // Refused for good (a source gone, a title hidden, a server without the history): not sent again.
        return when (result) {
            is AppResult.Ok -> true
            is AppResult.Err -> result.error is AppError.Rejected || result.error is AppError.Forbidden
        }
    }

    override suspend fun pull(sinceMillis: Long?): List<AccountPlace>? =
        (
            http.apiCall<ApiData<List<PlaceDto>>> {
                get("api/v1/reading-history") { sinceMillis?.let { parameter("updatedSince", isoUtc(it)) } }
            } as? AppResult.Ok
        )?.value?.data?.mapNotNull { it.toDomain() }

    private companion object {
        val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}

internal fun PlaceDto.toDomain(): AccountPlace? {
    val at = parseIsoMillis(updatedAt) ?: return null
    val read = if (deleted) null else LastRead(sourceKey, titleRef, chapterRef, page, at)
    return AccountPlace(sourceKey, titleRef, read, at)
}
