package com.vovoscan.feature.sources.data

import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.map
import com.vovoscan.core.network.ApiData
import com.vovoscan.core.network.apiCall
import com.vovoscan.core.network.apiCallUnit
import com.vovoscan.core.sources.Fetched
import com.vovoscan.core.sources.PluginFetcher
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonObject

/**
 * A source of the user's list (`GET api/v1/catalog/sources`). `origin` is "official" or "user" (third-party source,
 * then `originUrl` and `repositoryId` tell where it came from). Lenient: unknown fields are ignored, missing ones
 * take a default.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MySourceDto(
    val key: String,
    val name: String,
    val engine: String = "rules",
    val version: String = "1.0.0",
    @JsonNames("kinds")
    val contentKinds: List<String> = emptyList(),
    val langs: List<String> = emptyList(),
    val enabled: Boolean = true,
    val position: Int = 0,
    /** Sources read on the device: manifest (v1 contract) and effective settings. */
    val manifest: JsonObject? = null,
    val settings: JsonObject? = null,
    val origin: String = "official",
    val originUrl: String? = null,
    val repositoryId: String? = null,
    val description: String? = null,
)

@Serializable
data class SourceChoiceDto(
    val key: String,
    val enabled: Boolean,
)

@Serializable
internal data class SaveChoicesBody(
    val sources: List<SourceChoiceDto>,
)

@Serializable
data class PluginRepositoryDto(
    val id: String,
    val url: String = "",
    val name: String = "",
    val updatedAt: String? = null,
    val deleted: Boolean = false,
)

@Serializable
data class UserPluginDto(
    val key: String,
    val manifest: JsonObject? = null,
    val settings: JsonObject? = null,
    /** repository | link | file */
    val origin: String? = null,
    val originUrl: String? = null,
    val repositoryId: String? = null,
    val updatedAt: String? = null,
    val deleted: Boolean = false,
)

/** `GET api/v1/plugins`: the user's repositories and plugins. */
@Serializable
data class MyPluginsDto(
    val repositories: List<PluginRepositoryDto> = emptyList(),
    val plugins: List<UserPluginDto> = emptyList(),
)

/** Remote source: `api/v1/catalog/sources`, `api/v1/plugins`, `api/v1/plugin-repositories`. */
interface SourcesRemoteDataSource {
    suspend fun mine(): AppResult<List<MySourceDto>>

    suspend fun save(choices: List<SourceChoiceDto>): AppResult<List<MySourceDto>>

    suspend fun plugins(): AppResult<MyPluginsDto>

    /** [body]: `{manifest, settings, origin, originUrl, repositoryId, updatedAt}`. */
    suspend fun putPlugin(
        key: String,
        body: JsonObject,
    ): AppResult<Unit>

    suspend fun deletePlugin(
        key: String,
        updatedAt: String,
    ): AppResult<Unit>

    /** [body]: `{url, name, updatedAt}`. */
    suspend fun putRepository(
        id: String,
        body: JsonObject,
    ): AppResult<Unit>

    suspend fun deleteRepository(
        id: String,
        updatedAt: String,
    ): AppResult<Unit>
}

class KtorSourcesRemoteDataSource(
    private val http: HttpClient,
) : SourcesRemoteDataSource {
    override suspend fun mine(): AppResult<List<MySourceDto>> =
        http.apiCall<ApiData<List<MySourceDto>>> { get("api/v1/catalog/sources") }.map { it.data }

    override suspend fun save(choices: List<SourceChoiceDto>): AppResult<List<MySourceDto>> =
        http
            .apiCall<ApiData<List<MySourceDto>>> {
                put("api/v1/catalog/sources") {
                    contentType(ContentType.Application.Json)
                    setBody(SaveChoicesBody(choices))
                }
            }.map { it.data }

    override suspend fun plugins(): AppResult<MyPluginsDto> = http.apiCall<ApiData<MyPluginsDto>> { get("api/v1/plugins") }.map { it.data }

    override suspend fun putPlugin(
        key: String,
        body: JsonObject,
    ): AppResult<Unit> =
        http.apiCallUnit {
            put("api/v1/plugins/${key.encodeURLPathPart()}") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    override suspend fun deletePlugin(
        key: String,
        updatedAt: String,
    ): AppResult<Unit> = http.apiCallUnit { delete("api/v1/plugins/${key.encodeURLPathPart()}") { parameter("updatedAt", updatedAt) } }

    override suspend fun putRepository(
        id: String,
        body: JsonObject,
    ): AppResult<Unit> =
        http.apiCallUnit {
            put("api/v1/plugin-repositories/${id.encodeURLPathPart()}") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    override suspend fun deleteRepository(
        id: String,
        updatedAt: String,
    ): AppResult<Unit> =
        http.apiCallUnit {
            delete("api/v1/plugin-repositories/${id.encodeURLPathPart()}") { parameter("updatedAt", updatedAt) }
        }
}

/**
 * Remote source for plugin files hosted by third parties (repository indexes, manifests): fetched by the device
 * without the vovoscan session, never through our API.
 */
fun interface PluginFilesRemoteDataSource {
    suspend fun fetch(
        url: String,
        maxBytes: Int,
    ): AppResult<Fetched>
}

class KtorPluginFilesRemoteDataSource(
    private val fetcher: PluginFetcher,
) : PluginFilesRemoteDataSource {
    override suspend fun fetch(
        url: String,
        maxBytes: Int,
    ): AppResult<Fetched> = fetcher.fetch(url, maxBytes)
}
