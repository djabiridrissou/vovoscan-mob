package com.vovoscan.feature.reader.data

import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.map
import com.vovoscan.core.network.ApiData
import com.vovoscan.core.network.apiCall
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import kotlinx.serialization.Serializable

@Serializable
data class ReaderPageDto(
    val index: Int,
    val url: String,
    val width: Int,
    val height: Int,
)

@Serializable
data class ReaderChapterDto(
    val id: String,
    val titleId: String,
    val titleName: String,
    val number: Double,
    val name: String? = null,
    val format: String,
    val previousChapterId: String? = null,
    val nextChapterId: String? = null,
    val pages: List<ReaderPageDto> = emptyList(),
)

/** Source distante : `GET api/v1/catalog/chapters/{id}`, le chapitre prêt à lire quelle que soit sa source. */
interface ReaderRemoteDataSource {
    suspend fun chapter(chapterId: String): AppResult<ReaderChapterDto>
}

class KtorReaderRemoteDataSource(
    private val http: HttpClient,
) : ReaderRemoteDataSource {
    override suspend fun chapter(chapterId: String): AppResult<ReaderChapterDto> =
        http.apiCall<ApiData<ReaderChapterDto>> { get("api/v1/catalog/chapters/$chapterId") }.map { it.data }
}
