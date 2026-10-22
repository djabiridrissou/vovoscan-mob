package com.vovoscan.feature.reader.data

import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.map
import com.vovoscan.core.network.ApiData
import com.vovoscan.core.network.apiCall
import com.vovoscan.feature.reader.domain.ChapterTranslation
import com.vovoscan.feature.reader.domain.PixelBox
import com.vovoscan.feature.reader.domain.TranslatedPage
import com.vovoscan.feature.reader.domain.TranslatedText
import com.vovoscan.feature.reader.domain.TranslationStatus
import com.vovoscan.feature.reader.domain.TranslationsRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.Serializable

@Serializable
data class ChapterTranslationDto(
    val lang: String,
    val status: String? = null,
    val progress: Int? = null,
)

@Serializable
internal data class TranslateBody(
    val lang: String,
)

/** A translated text box; `bbox` and `area` are `[x, y, width, height]` in page pixels. */
@Serializable
data class TranslatedTextDto(
    val id: String = "",
    val bbox: List<Double>,
    val text: String,
    val fill: String = "#ffffff",
    val ink: String = "#000000",
    val round: Boolean = false,
    val area: List<Double>? = null,
)

/** One page; the unified API names the sizes `w`/`h` and the texts `balloons`, the web reader `width`/`height`/`texts`. */
@Serializable
data class TranslatedPageDto(
    val index: Int? = null,
    val url: String = "",
    val width: Int? = null,
    val height: Int? = null,
    val w: Int? = null,
    val h: Int? = null,
    val texts: List<TranslatedTextDto>? = null,
    val balloons: List<TranslatedTextDto>? = null,
)

@Serializable
data class TranslatedChapterDto(
    val pages: List<TranslatedPageDto> = emptyList(),
)

/** Remote source and repository at once: translations are read fresh, nothing is kept on the device. */
class TranslationsRepositoryImpl(
    private val http: HttpClient,
) : TranslationsRepository {
    override suspend fun languages(chapterId: String): AppResult<List<ChapterTranslation>> =
        http
            .apiCall<ApiData<List<ChapterTranslationDto>>> { get(path(chapterId)) }
            .map { list -> list.data.map { it.toDomain() } }

    override suspend fun request(
        chapterId: String,
        lang: String,
    ): AppResult<ChapterTranslation> =
        http
            .apiCall<ApiData<ChapterTranslationDto>> {
                post(path(chapterId)) {
                    contentType(ContentType.Application.Json)
                    setBody(TranslateBody(lang))
                }
            }.map { it.data.toDomain() }

    override suspend fun pages(
        chapterId: String,
        lang: String,
    ): AppResult<List<TranslatedPage>> =
        http
            .apiCall<ApiData<TranslatedChapterDto>> { get(path(chapterId) + "/" + lang.encodeURLPathPart()) }
            .map { chapter -> chapter.data.pages.mapIndexed { i, page -> page.toDomain(i) } }

    private fun path(chapterId: String) = "api/v1/translations/chapters/${chapterId.encodeURLPathPart()}"
}

internal fun ChapterTranslationDto.toDomain() = ChapterTranslation(lang, TranslationStatus.from(status), progress)

internal fun TranslatedPageDto.toDomain(position: Int) =
    TranslatedPage(
        index = index ?: position,
        width = width ?: w ?: 0,
        height = height ?: h ?: 0,
        texts =
            (texts ?: balloons ?: emptyList()).mapIndexedNotNull { i, t ->
                val box = t.bbox.toBox() ?: return@mapIndexedNotNull null
                TranslatedText(
                    id = t.id.ifEmpty { "t$i" },
                    box = box,
                    text = t.text,
                    fill = t.fill.colorOr("#ffffff"),
                    ink = t.ink.colorOr("#000000"),
                    round = t.round,
                    area = t.area?.toBox(),
                )
            },
    )

private fun List<Double>.toBox(): PixelBox? =
    if (size == 4 && this[2] > 0 && this[3] > 0) PixelBox(this[0], this[1], this[2], this[3]) else null

/** Only `#rrggbb` reaches a renderer. */
private fun String.colorOr(fallback: String) = if (Regex("^#[0-9a-fA-F]{6}$").matches(this)) lowercase() else fallback
