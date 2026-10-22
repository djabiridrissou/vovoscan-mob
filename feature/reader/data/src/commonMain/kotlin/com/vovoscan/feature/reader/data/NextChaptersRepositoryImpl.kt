package com.vovoscan.feature.reader.data

import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.map
import com.vovoscan.core.network.ApiData
import com.vovoscan.core.network.apiCall
import com.vovoscan.feature.reader.domain.Funding
import com.vovoscan.feature.reader.domain.NextChapters
import com.vovoscan.feature.reader.domain.NextChaptersRepository
import com.vovoscan.feature.reader.domain.TranslationStatus
import com.vovoscan.feature.reader.domain.Upcoming
import com.vovoscan.feature.reader.domain.UpcomingTranslation
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.Serializable

@Serializable
data class UpcomingTranslationDto(
    val status: String,
    val progress: Int? = null,
    val error: String? = null,
    val cancellable: Boolean = false,
)

@Serializable
data class UpcomingDto(
    val id: String,
    val number: Double? = null,
    val title: String? = null,
    val pageCount: Int = 0,
    val state: String = "readable",
    val translation: UpcomingTranslationDto? = null,
    val credits: Int = 0,
)

@Serializable
data class NextChaptersDto(
    val language: String? = null,
    val seriesId: String? = null,
    val funding: String = "none",
    val available: Int? = null,
    val auto: Boolean = false,
    val upcoming: List<UpcomingDto> = emptyList(),
    val nextUrl: String? = null,
)

@Serializable
internal data class PrefetchBody(
    val language: String,
    val count: Int,
    val maxCredits: Int,
    val auto: Boolean,
)

@Serializable
internal data class PrefetchAutoBody(
    val language: String,
    val auto: Boolean,
)

/** The next chapters of one of our chapters and their preparation ahead (next-chapter contract). Online only. */
class NextChaptersRepositoryImpl(
    private val http: HttpClient,
) : NextChaptersRepository {
    override suspend fun next(
        chapterId: String,
        lang: String?,
    ): AppResult<NextChapters> =
        http
            .apiCall<ApiData<NextChaptersDto>> {
                get(
                    path(chapterId) + "/next",
                ) { lang?.let { parameter("lang", it) } }
            }.map { it.data.toDomain() }

    override suspend fun prefetch(
        chapterId: String,
        lang: String,
        count: Int,
        maxCredits: Int,
        auto: Boolean,
    ): AppResult<NextChapters> =
        http
            .apiCall<ApiData<NextChaptersDto>> {
                post(path(chapterId) + "/prefetch") {
                    contentType(ContentType.Application.Json)
                    setBody(PrefetchBody(lang, count, maxCredits, auto))
                }
            }.map { it.data.toDomain() }

    override suspend fun cancel(
        chapterId: String,
        lang: String,
    ): AppResult<NextChapters> =
        http
            .apiCall<ApiData<NextChaptersDto>> { delete(path(chapterId) + "/prefetch") { parameter("lang", lang) } }
            .map { it.data.toDomain() }

    override suspend fun setAuto(
        chapterId: String,
        lang: String,
        auto: Boolean,
    ): AppResult<NextChapters> =
        http
            .apiCall<ApiData<NextChaptersDto>> {
                put(path(chapterId) + "/prefetch/auto") {
                    contentType(ContentType.Application.Json)
                    setBody(PrefetchAutoBody(lang, auto))
                }
            }.map { it.data.toDomain() }

    private fun path(chapterId: String) = "api/v1/translations/chapters/${chapterId.encodeURLPathPart()}"
}

internal fun NextChaptersDto.toDomain() =
    NextChapters(
        language = language,
        seriesId = seriesId,
        funding = Funding.fromWire(funding),
        available = available,
        auto = auto,
        upcoming =
            upcoming.map { u ->
                Upcoming(
                    id = u.id,
                    number = u.number ?: 0.0,
                    title = u.title,
                    pageCount = u.pageCount,
                    readable = u.state == "readable",
                    translation =
                        u.translation?.let {
                            UpcomingTranslation(TranslationStatus.from(it.status), it.progress ?: 0, it.error, it.cancellable)
                        },
                    credits = u.credits,
                )
            },
        nextUrl = nextUrl,
    )
