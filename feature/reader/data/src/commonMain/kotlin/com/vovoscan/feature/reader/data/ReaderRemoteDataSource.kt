package com.vovoscan.feature.reader.data

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.map
import com.vovoscan.core.network.ApiData
import com.vovoscan.core.network.apiCall
import com.vovoscan.core.sources.ManifestProvider
import com.vovoscan.core.sources.RulesHttp
import com.vovoscan.core.sources.RulesSource
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

@Serializable
data class ReaderPageDto(
    val index: Int,
    val url: String,
    val width: Int = 0,
    val height: Int = 0,
)

@Serializable
data class DocumentDto(
    val url: String,
    val mediaType: String = "application/epub+zip",
)

@Serializable
data class ReaderChapterDto(
    val id: String,
    val titleId: String,
    val titleName: String,
    val kind: String = "comic",
    val number: Double,
    val name: String? = null,
    val format: String,
    val previousChapterId: String? = null,
    val nextChapterId: String? = null,
    val pages: List<ReaderPageDto> = emptyList(),
    val document: DocumentDto? = null,
)

/** Remote source for a unit: the vovoscan API (internal source) or the source itself (read on the device). */
interface ReaderRemoteDataSource {
    suspend fun chapter(
        sourceKey: String,
        titleId: String,
        chapterId: String,
    ): AppResult<ReaderChapterDto>

    /** A book's file. */
    suspend fun download(
        sourceKey: String,
        url: String,
    ): AppResult<ByteArray>
}

/** `GET api/v1/catalog/chapters/{id}`; a book's file is a signed vovoscan URL. */
class ApiReaderRemoteDataSource(
    private val http: HttpClient,
) : ReaderRemoteDataSource {
    override suspend fun chapter(
        sourceKey: String,
        titleId: String,
        chapterId: String,
    ): AppResult<ReaderChapterDto> = http.apiCall<ApiData<ReaderChapterDto>> { get("api/v1/catalog/chapters/$chapterId") }.map { it.data }

    override suspend fun download(
        sourceKey: String,
        url: String,
    ): AppResult<ByteArray> =
        try {
            val response = http.get(url)
            if (response.status.isSuccess()) {
                AppResult.Ok(
                    response.bodyAsBytes(),
                )
            } else {
                AppResult.Err(AppError.Unknown("HTTP ${response.status.value}"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.Err(AppError.Network)
        }
}

/** Sources read on the device: title, pages or book are requested from the source itself, never from vovoscan. */
class OnDeviceReaderRemoteDataSource(
    private val manifests: ManifestProvider,
    private val http: RulesHttp,
) : ReaderRemoteDataSource {
    override suspend fun chapter(
        sourceKey: String,
        titleId: String,
        chapterId: String,
    ): AppResult<ReaderChapterDto> {
        val source = manifests.manifest(sourceKey)?.let { RulesSource(it, http) } ?: return unknown()
        val title =
            when (val loaded = source.title(titleId)) {
                is AppResult.Err -> return loaded
                is AppResult.Ok -> loaded.value
            }
        val position = title.units.indexOfFirst { it.id == chapterId }
        if (position < 0) return AppResult.Err(AppError.Rejected("CHAPTER_NOT_FOUND", "This chapter isn't available anymore."))
        val unit = title.units[position]
        val base =
            ReaderChapterDto(
                id = unit.id,
                titleId = titleId,
                titleName = title.summary.name,
                kind = title.summary.kind,
                number = unit.number,
                name = unit.name,
                format = title.summary.format,
                previousChapterId = title.units.getOrNull(position - 1)?.id,
                nextChapterId = title.units.getOrNull(position + 1)?.id,
            )
        return if (title.summary.kind == "book") {
            source.bookUrl(titleId, unit.id).map { base.copy(document = DocumentDto(it)) }
        } else {
            source.comicPages(titleId, unit.id).map { pages ->
                base.copy(pages = pages.mapIndexed { i, p -> ReaderPageDto(i, p.url, p.width, p.height) })
            }
        }
    }

    override suspend fun download(
        sourceKey: String,
        url: String,
    ): AppResult<ByteArray> = manifests.manifest(sourceKey)?.let { RulesSource(it, http).download(url) } ?: unknown()

    private fun <T> unknown(): AppResult<T> =
        AppResult.Err(AppError.Rejected("SOURCE_NOT_FOUND", "This source isn't available on this device yet."))
}

/** Routing: a source with a manifest is read on the device; otherwise it is the internal source, through the API. */
class RoutingReaderRemoteDataSource(
    private val manifests: ManifestProvider,
    private val api: ReaderRemoteDataSource,
    private val onDevice: ReaderRemoteDataSource,
) : ReaderRemoteDataSource {
    private suspend fun pick(key: String) = if (manifests.manifest(key) != null) onDevice else api

    override suspend fun chapter(
        sourceKey: String,
        titleId: String,
        chapterId: String,
    ) = pick(sourceKey).chapter(sourceKey, titleId, chapterId)

    override suspend fun download(
        sourceKey: String,
        url: String,
    ) = pick(sourceKey).download(sourceKey, url)
}
