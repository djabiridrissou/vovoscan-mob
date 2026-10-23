package com.vovoscan.feature.catalog.data

import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.map
import com.vovoscan.core.network.ApiData
import com.vovoscan.core.network.ApiPage
import com.vovoscan.core.network.apiCall
import com.vovoscan.core.sources.ManifestProvider
import com.vovoscan.core.sources.RulesHttp
import com.vovoscan.core.sources.RulesSource
import com.vovoscan.core.sources.RulesTitleSummary
import com.vovoscan.core.sources.formatHint
import com.vovoscan.core.sources.sourceLanguage
import com.vovoscan.feature.catalog.domain.INTERNAL_SOURCE
import com.vovoscan.feature.catalog.domain.ReadingFormat
import com.vovoscan.feature.catalog.domain.SourceCategory
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class TitleSummaryDto(
    val id: String,
    val name: String,
    val coverUrl: String? = null,
    val kind: String = "comic",
    val format: String,
    val status: String,
    val contentRating: String? = null,
    /** false: the source names no format, [format] is the default (it never replaces one the device knows). */
    @Transient val formatKnown: Boolean = true,
    /** Chapters with an episode the caller can watch (the server's word). */
    val episodeCount: Int = 0,
    /** false: listed by the device itself, which can't know [episodeCount] (the saved count stays). */
    @Transient val episodesKnown: Boolean = true,
    /** Everyone's numbers (our catalog's listings only; each null below 3 accounts). */
    val ratingAvg: Double? = null,
    val ratingCount: Int? = null,
    val favoriteCount: Int? = null,
)

@Serializable
data class ChapterDto(
    val id: String,
    val number: Double,
    val name: String? = null,
    val pageCount: Int = 0,
    val publishedAt: String? = null,
    /** Languages its episode can be watched in, sorted; `""` = the original. */
    val episodeLanguages: List<String> = emptyList(),
)

@Serializable
data class TitleDetailDto(
    val id: String,
    val name: String,
    val coverUrl: String? = null,
    val kind: String = "comic",
    val format: String,
    val status: String,
    val contentRating: String? = null,
    val description: String = "",
    val authors: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val chapters: List<ChapterDto> = emptyList(),
    /** As in [TitleSummaryDto]: false when neither the source nor the title's genres say how it reads. */
    @Transient val formatKnown: Boolean = true,
    val episodeCount: Int = 0,
    /** false: read on the device; the chapters' episode languages saved before stay. */
    @Transient val episodesKnown: Boolean = true,
)

@Serializable
data class CategoryDto(
    val key: String,
    val name: String,
    val position: Int = 0,
    /** `manga`, `webtoon` or `text` for our default categories (the format they stand for); null for a curated one. */
    val format: String? = null,
)

/** `GET source-reader/{key}/titles`: a page of a source as the server reads it (here: its titles with an episode). */
@Serializable
data class SourceTitlesDto(
    val items: List<TitleSummaryDto> = emptyList(),
    val page: Int = 1,
    val hasNext: Boolean = false,
)

/** `GET source-reader/{key}/title`: only what the episode badges need. */
@Serializable
data class SourceTitleEpisodesDto(
    val episodeCount: Int = 0,
    val units: List<SourceUnitEpisodesDto> = emptyList(),
)

@Serializable
data class SourceUnitEpisodesDto(
    val id: String,
    val episodeLanguages: List<String> = emptyList(),
)

/** What the server says of a title's episodes: the count, and each chapter's languages (by chapter id). */
data class TitleEpisodes(
    val count: Int,
    val languages: Map<String, List<String>>,
)

/** A catalog page, whatever the source. */
data class TitlesPage(
    val page: Int,
    val items: List<TitleSummaryDto>,
    val hasNext: Boolean,
)

/** Remote source of a catalog: the vovoscan API for the internal source, the source itself for the others. */
interface CatalogRemoteDataSource {
    /**
     * `categoryId`: one of the source's categories, null for everything. [episodes]: only titles with an episode, which
     * the server alone knows (for a source read on the device: the titles imported from it, any category).
     */
    suspend fun titles(
        sourceKey: String,
        page: Int,
        categoryId: String? = null,
        episodes: Boolean = false,
    ): AppResult<TitlesPage>

    suspend fun search(
        sourceKey: String,
        query: String,
        categoryId: String? = null,
        episodes: Boolean = false,
    ): AppResult<TitlesPage>

    suspend fun categories(sourceKey: String): AppResult<List<SourceCategory>>

    suspend fun title(
        sourceKey: String,
        id: String,
    ): AppResult<TitleDetailDto>

    /**
     * Title ref → format (`manga`, `webtoon`) the server learned from imported pages, for a source read on the device
     * whose catalog names no format; empty otherwise, offline, or for refs it has not seen.
     */
    suspend fun learnedFormats(
        sourceKey: String,
        refs: List<String>,
    ): Map<String, String> = emptyMap()

    /**
     * The episodes the server knows for a title of a source read on the device (null: none asked, or no answer). Asked
     * only for a title the server already said has one, so opening a title page tells it nothing new.
     */
    suspend fun titleEpisodes(
        sourceKey: String,
        ref: String,
    ): TitleEpisodes? = null
}

/** `api/v1/catalog/…`: the internal source (our published titles). */
class ApiCatalogRemoteDataSource(
    private val http: HttpClient,
) : CatalogRemoteDataSource {
    override suspend fun titles(
        sourceKey: String,
        page: Int,
        categoryId: String?,
        episodes: Boolean,
    ): AppResult<TitlesPage> =
        if (episodes && sourceKey != INTERNAL_SOURCE) {
            sourceEpisodes(sourceKey, page, "")
        } else {
            http
                .apiCall<ApiPage<TitleSummaryDto>> {
                    get("api/v1/catalog/sources/$sourceKey/titles") {
                        parameter("page", page)
                        parameter("perPage", PAGE_SIZE)
                        categoryId?.let { parameter("category", it) }
                        if (episodes) parameter("has", HAS_EPISODE)
                    }
                }.map { TitlesPage(it.page, it.data, it.hasNext) }
        }

    override suspend fun search(
        sourceKey: String,
        query: String,
        categoryId: String?,
        episodes: Boolean,
    ): AppResult<TitlesPage> =
        if (episodes && sourceKey != INTERNAL_SOURCE) {
            sourceEpisodes(sourceKey, 1, query)
        } else {
            http
                .apiCall<ApiPage<TitleSummaryDto>> {
                    get("api/v1/catalog/sources/$sourceKey/titles") {
                        parameter("search", query)
                        parameter("perPage", PAGE_SIZE)
                        categoryId?.let { parameter("category", it) }
                        if (episodes) parameter("has", HAS_EPISODE)
                    }
                }.map { TitlesPage(it.page, it.data, it.hasNext) }
        }

    /** A source read on the device: its titles imported to the server that have an episode (the server's database). */
    private suspend fun sourceEpisodes(
        sourceKey: String,
        page: Int,
        query: String,
    ): AppResult<TitlesPage> =
        http
            .apiCall<ApiData<SourceTitlesDto>> {
                get("api/v1/source-reader/${sourceKey.encodeURLPathPart()}/titles") {
                    parameter("has", HAS_EPISODE)
                    parameter("page", page)
                    // The `{{lang}}` this device reads its sources in, so the refs match its own.
                    parameter("lang", sourceLanguage())
                    if (query.isNotBlank()) parameter("search", query)
                }
            }.map { TitlesPage(it.data.page, it.data.items, it.data.hasNext) }

    override suspend fun titleEpisodes(
        sourceKey: String,
        ref: String,
    ): TitleEpisodes? =
        (
            http.apiCall<ApiData<SourceTitleEpisodesDto>> {
                get("api/v1/source-reader/${sourceKey.encodeURLPathPart()}/title") {
                    parameter("ref", ref)
                    parameter("lang", sourceLanguage())
                }
            } as? AppResult.Ok
        )?.value?.data?.let { dto -> TitleEpisodes(dto.episodeCount, dto.units.associate { it.id to it.episodeLanguages }) }

    /** The internal catalog's categories (their `key` is the id sent back as `category`). */
    override suspend fun categories(sourceKey: String): AppResult<List<SourceCategory>> =
        http
            .apiCall<ApiData<List<CategoryDto>>> { get("api/v1/catalog/sources/$sourceKey/categories") }
            .map { list ->
                list.data.sortedBy { it.position }.map { SourceCategory(it.key, it.name, it.format?.let(ReadingFormat::fromApi)) }
            }

    override suspend fun title(
        sourceKey: String,
        id: String,
    ): AppResult<TitleDetailDto> = http.apiCall<ApiData<TitleDetailDto>> { get("api/v1/catalog/titles/$id") }.map { it.data }

    override suspend fun learnedFormats(
        sourceKey: String,
        refs: List<String>,
    ): Map<String, String> =
        refs.distinct().chunked(MAX_FORMAT_REFS).fold(emptyMap()) { found, chunk ->
            val result =
                http.apiCall<ApiData<Map<String, String>>> {
                    get("api/v1/source-reader/${sourceKey.encodeURLPathPart()}/formats") { chunk.forEach { parameter("ref", it) } }
                }
            found + ((result as? AppResult.Ok)?.value?.data ?: return found)
        }

    private companion object {
        const val PAGE_SIZE = 24
        const val HAS_EPISODE = "episode"

        /** Refs per formats request (the server's limit). */
        const val MAX_FORMAT_REFS = 100
    }
}

/** Sources read on the device: the manifest is interpreted here and requests are made from here, never through vovoscan. */
class OnDeviceCatalogRemoteDataSource(
    private val manifests: ManifestProvider,
    private val http: RulesHttp,
) : CatalogRemoteDataSource {
    private suspend fun source(key: String): RulesSource? = manifests.manifest(key)?.let { RulesSource(it, http) }

    override suspend fun titles(
        sourceKey: String,
        page: Int,
        categoryId: String?,
        episodes: Boolean,
    ): AppResult<TitlesPage> =
        source(sourceKey)?.let { s ->
            s.catalog(page, category = categoryId.orEmpty()).map { TitlesPage(page, it.items.map { i -> dto(s, i) }, it.hasNext) }
        }
            ?: unknown()

    override suspend fun search(
        sourceKey: String,
        query: String,
        categoryId: String?,
        episodes: Boolean,
    ): AppResult<TitlesPage> =
        source(sourceKey)?.let { s ->
            s.catalog(1, query, categoryId.orEmpty()).map { TitlesPage(1, it.items.map { i -> dto(s, i) }, it.hasNext) }
        }
            ?: unknown()

    override suspend fun categories(sourceKey: String): AppResult<List<SourceCategory>> =
        source(sourceKey)?.categories()?.map { list -> list.map { SourceCategory(it.id, it.name) } } ?: unknown()

    override suspend fun title(
        sourceKey: String,
        id: String,
    ): AppResult<TitleDetailDto> =
        source(sourceKey)?.let { s ->
            s.title(id).map { title ->
                TitleDetailDto(
                    id = title.summary.id,
                    name = title.summary.name,
                    coverUrl = title.summary.coverUrl,
                    kind = title.summary.kind,
                    format = title.summary.format,
                    status = title.summary.status,
                    contentRating = title.summary.contentRating,
                    description = title.description,
                    authors = title.authors,
                    genres = title.genres,
                    // Newest to oldest, like the API.
                    chapters = title.units.reversed().map { ChapterDto(it.id, it.number, it.name) },
                    // A declared format rule that read nothing is the default too.
                    formatKnown =
                        title.summary.formatRead ||
                            title.summary.kind == "book" ||
                            title.summary.format != "manga" ||
                            formatHint(title.genres) != null,
                    episodesKnown = false,
                )
            }
        } ?: unknown()

    private fun dto(
        source: RulesSource,
        item: RulesTitleSummary,
    ) = TitleSummaryDto(
        item.id,
        item.name,
        item.coverUrl,
        item.kind,
        item.format,
        item.status,
        item.contentRating,
        formatKnown = item.formatRead || item.kind == "book",
        episodesKnown = false,
    )

    private fun <T> unknown(): AppResult<T> =
        AppResult.Err(
            com.vovoscan.core.common.AppError
                .Rejected("SOURCE_NOT_FOUND", "This source isn't available on this device yet."),
        )
}

/** Routing: a source with a manifest is read on the device; otherwise it is the internal source, through the API. */
class RoutingCatalogRemoteDataSource(
    private val manifests: ManifestProvider,
    private val api: CatalogRemoteDataSource,
    private val onDevice: CatalogRemoteDataSource,
) : CatalogRemoteDataSource {
    private suspend fun pick(key: String) = if (manifests.manifest(key) != null) onDevice else api

    /** With [episodes], always the server: only it knows which titles have one. */
    override suspend fun titles(
        sourceKey: String,
        page: Int,
        categoryId: String?,
        episodes: Boolean,
    ) = (if (episodes) api else pick(sourceKey)).titles(sourceKey, page, categoryId, episodes)

    override suspend fun search(
        sourceKey: String,
        query: String,
        categoryId: String?,
        episodes: Boolean,
    ) = (if (episodes) api else pick(sourceKey)).search(sourceKey, query, categoryId, episodes)

    override suspend fun titleEpisodes(
        sourceKey: String,
        ref: String,
    ): TitleEpisodes? = if (manifests.manifest(sourceKey) != null) api.titleEpisodes(sourceKey, ref) else null

    override suspend fun categories(sourceKey: String) = pick(sourceKey).categories(sourceKey)

    override suspend fun title(
        sourceKey: String,
        id: String,
    ) = pick(sourceKey).title(sourceKey, id)

    /**
     * Asked of the API for a comic source read on the device, whether or not its catalog names a format: a declared
     * rule that reads nothing for a title leaves it a manga by default, and the server's word beats that default.
     * Nothing for a source without comics.
     */
    override suspend fun learnedFormats(
        sourceKey: String,
        refs: List<String>,
    ): Map<String, String> {
        val manifest = manifests.manifest(sourceKey) ?: return emptyMap()
        return if ("comic" !in manifest.kinds || refs.isEmpty()) emptyMap() else api.learnedFormats(sourceKey, refs)
    }
}
