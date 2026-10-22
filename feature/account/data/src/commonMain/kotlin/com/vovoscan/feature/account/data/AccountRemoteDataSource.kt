package com.vovoscan.feature.account.data

import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.isoUtc
import com.vovoscan.core.common.map
import com.vovoscan.core.network.ApiData
import com.vovoscan.core.network.apiCall
import com.vovoscan.core.network.apiCallUnit
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable

@Serializable
data class ProviderKeyDto(
    val id: String,
    val provider: String,
    val hint: String,
    val label: String? = null,
    val createdAt: String,
    val lastUsedAt: String? = null,
)

@Serializable
data class ProviderKeysDto(
    val enabled: Boolean,
    val keys: List<ProviderKeyDto> = emptyList(),
)

/**
 * `POST api/v1/provider-keys`. The only place the secret ever goes: serialized once into the request body. Not a data
 * class, and its `toString` hides the key, so it can't end up in a log or a crash report.
 */
@Serializable
internal class AddProviderKeyBody(
    val provider: String,
    val key: String,
    val label: String? = null,
) {
    override fun toString(): String = "AddProviderKeyBody(provider=$provider, key=<hidden>)"
}

@Serializable
data class ReadingSettingsDto(
    val ocrModel: String? = null,
    /** The content language chapters are translated into; null until chosen (any subset is sent). */
    val targetLanguage: String? = null,
)

@Serializable
data class UsageTotalDto(
    val provider: String,
    val costUsd: Double,
    val units: Map<String, Double> = emptyMap(),
)

@Serializable
data class UsageEntryDto(
    val id: String,
    val provider: String,
    val model: String? = null,
    val unitType: String,
    val units: Double,
    val costUsd: Double,
    val chapterId: String? = null,
    val renderId: String? = null,
    val createdAt: String,
)

@Serializable
data class UsageDto(
    val from: String? = null,
    val to: String? = null,
    val totals: List<UsageTotalDto> = emptyList(),
    val entries: List<UsageEntryDto> = emptyList(),
)

/** Remote source: `api/v1/provider-keys` (keys and costs) and `api/v1/settings/reading`. Always the caller's own. */
interface AccountRemoteDataSource {
    suspend fun keys(): AppResult<ProviderKeysDto>

    suspend fun addKey(
        provider: String,
        secret: String,
        label: String?,
    ): AppResult<ProviderKeyDto>

    suspend fun removeKey(id: String): AppResult<Unit>

    suspend fun readingSettings(): AppResult<ReadingSettingsDto>

    /** Any subset: a null field is left out of the request and keeps its value. */
    suspend fun setReadingSettings(
        ocrModel: String? = null,
        targetLanguage: String? = null,
    ): AppResult<ReadingSettingsDto>

    suspend fun usage(
        fromMillis: Long?,
        toMillis: Long?,
    ): AppResult<UsageDto>
}

class KtorAccountRemoteDataSource(
    private val http: HttpClient,
) : AccountRemoteDataSource {
    override suspend fun keys(): AppResult<ProviderKeysDto> =
        http.apiCall<ApiData<ProviderKeysDto>> { get("api/v1/provider-keys") }.map { it.data }

    override suspend fun addKey(
        provider: String,
        secret: String,
        label: String?,
    ): AppResult<ProviderKeyDto> =
        http
            .apiCall<ApiData<ProviderKeyDto>> {
                post("api/v1/provider-keys") {
                    contentType(ContentType.Application.Json)
                    setBody(AddProviderKeyBody(provider, secret, label))
                }
            }.map { it.data }

    override suspend fun removeKey(id: String): AppResult<Unit> = http.apiCallUnit { delete("api/v1/provider-keys/$id") }

    override suspend fun readingSettings(): AppResult<ReadingSettingsDto> =
        http.apiCall<ApiData<ReadingSettingsDto>> { get("api/v1/settings/reading") }.map { it.data }

    override suspend fun setReadingSettings(
        ocrModel: String?,
        targetLanguage: String?,
    ): AppResult<ReadingSettingsDto> =
        http
            .apiCall<ApiData<ReadingSettingsDto>> {
                put("api/v1/settings/reading") {
                    contentType(ContentType.Application.Json)
                    setBody(ReadingSettingsDto(ocrModel, targetLanguage))
                }
            }.map { it.data }

    override suspend fun usage(
        fromMillis: Long?,
        toMillis: Long?,
    ): AppResult<UsageDto> =
        http
            .apiCall<ApiData<UsageDto>> {
                get("api/v1/provider-keys/usage") {
                    fromMillis?.let { parameter("from", isoUtc(it)) }
                    toMillis?.let { parameter("to", isoUtc(it)) }
                }
            }.map { it.data }
}
