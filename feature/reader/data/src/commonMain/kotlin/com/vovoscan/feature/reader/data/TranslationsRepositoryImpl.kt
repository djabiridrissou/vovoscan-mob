package com.vovoscan.feature.reader.data

import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.map
import com.vovoscan.core.network.ApiData
import com.vovoscan.core.network.apiCall
import com.vovoscan.feature.reader.domain.ChapterPanels
import com.vovoscan.feature.reader.domain.ChapterTranslation
import com.vovoscan.feature.reader.domain.PanelBox
import com.vovoscan.feature.reader.domain.PixelBox
import com.vovoscan.feature.reader.domain.TranslatedPage
import com.vovoscan.feature.reader.domain.TranslatedText
import com.vovoscan.feature.reader.domain.TranslationStatus
import com.vovoscan.feature.reader.domain.TranslationsRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.Serializable

/** A language of a chapter (`GET /translations/chapters/{id}`). */
@Serializable
data class ChapterTranslationDto(
    val language: String,
    val status: String? = null,
    val progress: Int? = null,
)

@Serializable
data class ChapterTranslationsDto(
    val translations: List<ChapterTranslationDto> = emptyList(),
)

@Serializable
internal data class TranslateBody(
    val language: String,
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

/** One page of `GET …/read?lang=` (`width`/`height`/`texts`; `w`/`h`/`balloons` still read, as the first API named them). */
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
    /** The analysis' panels, `[x, y, width, height]` in page pixels, already in reading order (reader/SPEC.md §2). */
    val panels: List<List<Double>>? = null,
)

@Serializable
data class TranslatedChapterDto(
    val pages: List<TranslatedPageDto> = emptyList(),
)

/**
 * Remote source and repository at once: translations are read fresh, nothing is kept on the device. Also the reader's
 * panels (the original's `…/read`): our catalogue only; a source read on the device has none (gutters on the device).
 */
class TranslationsRepositoryImpl(
    private val http: HttpClient,
) : TranslationsRepository,
    ChapterPanels {
    override suspend fun panels(
        sourceKey: String,
        chapterId: String,
    ): Map<Int, List<PanelBox>> {
        if (sourceKey != INTERNAL_SOURCE) return emptyMap()
        val result = http.apiCall<ApiData<TranslatedChapterDto>> { get(path(chapterId) + "/read") }
        val pages = (result as? AppResult.Ok)?.value?.data?.pages ?: return emptyMap()
        return pages
            .mapIndexed { i, page -> (page.index ?: i) to page.panelBoxes(page.index ?: i) }
            .filter { it.second.isNotEmpty() }
            .toMap()
    }

    override suspend fun languages(chapterId: String): AppResult<List<ChapterTranslation>> =
        http
            .apiCall<ApiData<ChapterTranslationsDto>> { get(path(chapterId)) }
            .map { list -> list.data.translations.map { it.toDomain() } }

    /** 200 when the language exists or runs (shared, free), 202 when queued; either way the chapter's languages. */
    override suspend fun request(
        chapterId: String,
        lang: String,
    ): AppResult<ChapterTranslation> =
        http
            .apiCall<ApiData<ChapterTranslationsDto>> {
                post(path(chapterId)) {
                    contentType(ContentType.Application.Json)
                    setBody(TranslateBody(lang))
                }
            }.map { list ->
                list.data.translations
                    .firstOrNull { it.language == lang }
                    ?.toDomain() ?: ChapterTranslation(lang, TranslationStatus.Queued, null)
            }

    override suspend fun pages(
        chapterId: String,
        lang: String,
    ): AppResult<List<TranslatedPage>> =
        http
            .apiCall<ApiData<TranslatedChapterDto>> {
                get(path(chapterId) + "/read") { parameter("lang", lang) }
            }.map { chapter -> chapter.data.pages.mapIndexed { i, page -> page.toDomain(i) } }

    private fun path(chapterId: String) = "api/v1/translations/chapters/${chapterId.encodeURLPathPart()}"
}

internal fun ChapterTranslationDto.toDomain() = ChapterTranslation(language, TranslationStatus.from(status), progress)

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

/** The panels as fractions of the page; boxes outside it are clipped, empty ones dropped. */
internal fun TranslatedPageDto.panelBoxes(page: Int): List<PanelBox> {
    val w = (width ?: this.w ?: 0).toDouble()
    val h = (height ?: this.h ?: 0).toDouble()
    if (w <= 0 || h <= 0) return emptyList()
    return panels.orEmpty().mapNotNull { box ->
        val b = box.toBox() ?: return@mapNotNull null
        val x0 = (b.x / w).coerceIn(0.0, 1.0)
        val y0 = (b.y / h).coerceIn(0.0, 1.0)
        val x1 = ((b.x + b.w) / w).coerceIn(0.0, 1.0)
        val y1 = ((b.y + b.h) / h).coerceIn(0.0, 1.0)
        if (x1 <= x0 || y1 <= y0) null else PanelBox(page, x0.toFloat(), y0.toFloat(), (x1 - x0).toFloat(), (y1 - y0).toFloat())
    }
}

private const val INTERNAL_SOURCE = "vovoscan"

private fun List<Double>.toBox(): PixelBox? =
    if (size == 4 && this[2] > 0 && this[3] > 0) PixelBox(this[0], this[1], this[2], this[3]) else null

/** Only `#rrggbb` reaches a renderer. */
private fun String.colorOr(fallback: String) = if (Regex("^#[0-9a-fA-F]{6}$").matches(this)) lowercase() else fallback
