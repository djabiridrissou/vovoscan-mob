package com.vovoscan.core.network

import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.ChapterImports
import com.vovoscan.core.common.ImportStatus
import com.vovoscan.core.common.ImportedChapter
import com.vovoscan.core.common.SourceUnit
import com.vovoscan.core.common.map
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/**
 * [ChapterImports] over the API's source reader. [language] is the `{{lang}}` the device reads its sources in, so the
 * server reads the same pages. Copies found are remembered for the app's run: a card opened again asks nothing.
 */
class ApiChapterImports(
    private val client: HttpClient,
    private val language: () -> String,
) : ChapterImports {
    private val lock = Mutex()
    private val known = mutableMapOf<SourceUnit, ImportedChapter>()

    override suspend fun find(unit: SourceUnit): ImportedChapter? {
        lock.withLock { known[unit] }?.let { return it }
        // The title as the server reads it names the units already copied (no copy is started).
        val title =
            client.apiCall<ApiData<MirroredTitleDto>> {
                get("api/v1/source-reader/${unit.sourceKey.encodeURLPathPart()}/title") {
                    parameter("ref", unit.titleRef)
                    parameter("lang", language())
                }
            }
        val found =
            (title as? AppResult.Ok)
                ?.value
                ?.data
                ?.units
                ?.firstOrNull { it.id == unit.unitRef && it.chapterId != null }
                ?.let { ImportedChapter("", it.chapterId!!, ImportStatus.fromWire(it.fetchStatus), 0) }
                ?: return null
        // The title id comes with the chapter's own state.
        return (status(found.chapterId) as? AppResult.Ok)?.value?.also { remember(unit, it) }
    }

    override suspend fun import(unit: SourceUnit): AppResult<ImportedChapter> =
        client
            .apiCall<ApiData<MirroredDto>> {
                post("api/v1/source-reader/${unit.sourceKey.encodeURLPathPart()}/import") {
                    parameter("lang", language())
                    contentType(ContentType.Application.Json)
                    setBody(ImportBody(unit.titleRef, unit.unitRef))
                }
            }.map { it.data.toDomain() }
            .also { if (it is AppResult.Ok) remember(unit, it.value) }

    override suspend fun status(chapterId: String): AppResult<ImportedChapter> =
        client
            .apiCall<ApiData<MirroredDto>> { get("api/v1/source-reader/chapters/${chapterId.encodeURLPathPart()}") }
            .map { it.data.toDomain() }
            .also { result ->
                if (result is AppResult.Ok) {
                    lock.withLock {
                        known.entries.filter { it.value.chapterId == chapterId }.forEach { known[it.key] = result.value }
                    }
                }
            }

    private suspend fun remember(
        unit: SourceUnit,
        chapter: ImportedChapter,
    ) = lock.withLock { known[unit] = chapter }
}

@Serializable
internal data class ImportBody(
    val titleRef: String,
    val unitRef: String,
)

@Serializable
internal data class MirroredDto(
    val titleId: String,
    val chapterId: String,
    val status: String? = null,
    val pageCount: Int = 0,
    val error: String? = null,
) {
    fun toDomain(): ImportedChapter = ImportedChapter(titleId, chapterId, ImportStatus.fromWire(status), pageCount, error)
}

@Serializable
internal data class MirroredTitleDto(
    val units: List<MirroredUnitDto> = emptyList(),
)

@Serializable
internal data class MirroredUnitDto(
    val id: String,
    val chapterId: String? = null,
    val fetchStatus: String? = null,
)
