package com.vovoscan.feature.sources.data

import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.map
import com.vovoscan.core.network.ApiData
import com.vovoscan.core.network.apiCall
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class MySourceDto(
    val key: String,
    val name: String,
    val engine: String,
    val version: String = "1.0.0",
    val contentKinds: List<String> = emptyList(),
    val langs: List<String> = emptyList(),
    val enabled: Boolean,
    val position: Int,
    /** Sources lues sur l'appareil : manifeste (contrat v1) et réglages effectifs. */
    val manifest: JsonObject? = null,
    val settings: JsonObject? = null,
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

/** Source distante : `GET/PUT api/v1/catalog/sources`. */
interface SourcesRemoteDataSource {
    suspend fun mine(): AppResult<List<MySourceDto>>

    suspend fun save(choices: List<SourceChoiceDto>): AppResult<List<MySourceDto>>
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
}
