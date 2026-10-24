package com.vovoscan.feature.notifications.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.Clock
import com.vovoscan.core.common.map
import com.vovoscan.core.common.parseIsoMillis
import com.vovoscan.core.network.ApiData
import com.vovoscan.core.network.ApiJson
import com.vovoscan.core.network.apiCall
import com.vovoscan.core.network.apiCallUnit
import com.vovoscan.core.network.followSnapshots
import com.vovoscan.feature.notifications.data.db.NotificationsDatabase
import com.vovoscan.feature.notifications.domain.DeviceKey
import com.vovoscan.feature.notifications.domain.Inbox
import com.vovoscan.feature.notifications.domain.Notification
import com.vovoscan.feature.notifications.domain.NotificationKind
import com.vovoscan.feature.notifications.domain.NotificationsRepository
import com.vovoscan.feature.notifications.domain.Preferences
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class ItemDto(
    val id: String,
    val kind: String,
    val data: JsonObject = JsonObject(emptyMap()),
    val createdAt: String,
    val readAt: String? = null,
) {
    fun toDomain() =
        Notification(
            id = id,
            kind = NotificationKind.of(kind),
            wireKind = kind,
            data = flatten(data),
            createdAtMillis = parseIsoMillis(createdAt) ?: 0,
            readAtMillis = readAt?.let(::parseIsoMillis),
        )
}

/** `data` as plain texts: numbers and booleans as written, lists joined (`winners`). */
fun flatten(data: JsonObject): Map<String, String> = data.mapNotNull { (k, v) -> text(v)?.let { k to it } }.toMap()

private fun text(v: JsonElement): String? =
    when (v) {
        is JsonNull -> null
        is JsonPrimitive -> v.content
        is JsonArray -> v.mapNotNull(::text).joinToString(", ")
        is JsonObject -> null
    }

@Serializable
data class InboxDto(
    val items: List<ItemDto> = emptyList(),
    val unread: Int = 0,
    val pushAvailable: Boolean = false,
) {
    fun toDomain() = Inbox(items.map { it.toDomain() }, unread, pushAvailable)
}

@Serializable
data class PrefsDto(
    val push: Boolean = true,
    val muted: List<String> = emptyList(),
    val kinds: List<String> = emptyList(),
) {
    fun toDomain() = Preferences(push, muted.toSet(), kinds)
}

@Serializable
data class PrefsBody(
    val push: Boolean,
    val muted: List<String>,
)

@Serializable
data class ReadBody(
    val ids: List<String>? = null,
)

@Serializable
data class DeviceBody(
    val platform: String,
    val token: String,
    val publicKey: String,
    val keyId: String,
    val locale: String? = null,
)

/** `api/v1/notifications`: always the caller's own. The last inbox is kept for display offline. */
class NotificationsRepositoryImpl(
    private val http: HttpClient,
    private val database: NotificationsDatabase,
    private val io: CoroutineDispatcher,
    private val clock: Clock,
) : NotificationsRepository {
    private val queries get() = database.notificationsQueries

    override fun observeInbox(): Flow<Inbox?> =
        queries
            .cached()
            .asFlow()
            .mapToOneOrNull(io)
            .map { body -> body?.let { runCatching { ApiJson.decodeFromString(InboxDto.serializer(), it).toDomain() }.getOrNull() } }

    override suspend fun inbox(before: String?): AppResult<Inbox> =
        when (val result = http.apiCall<ApiData<InboxDto>> { get(BASE) { before?.let { parameter("before", it) } } }) {
            is AppResult.Err -> result
            is AppResult.Ok -> {
                if (before == null) save(result.value.data)
                AppResult.Ok(result.value.data.toDomain())
            }
        }

    override suspend fun live(onInbox: suspend (Inbox) -> Boolean): AppResult<Unit> =
        http.followSnapshots(
            path = "$BASE/events",
            decode = { ApiJson.decodeFromString(InboxDto.serializer(), it) },
            poll = { http.apiCall<ApiData<InboxDto>> { get(BASE) }.map { it.data } },
            isFinal = { it == AppError.Unauthorized || it == AppError.Forbidden },
            pollMs = POLL_MS,
            pollsBeforeStream = 4,
        ) { dto ->
            save(dto)
            onInbox(dto.toDomain())
        }

    override suspend fun read(ids: List<String>?): AppResult<Unit> = http.apiCallUnit { post("$BASE/read") { json(ReadBody(ids)) } }

    override suspend fun preferences(): AppResult<Preferences> =
        http.apiCall<ApiData<PrefsDto>> { get("$BASE/preferences") }.map { it.data.toDomain() }

    override suspend fun savePreferences(
        push: Boolean,
        muted: Set<String>,
    ): AppResult<Preferences> =
        http.apiCall<ApiData<PrefsDto>> { put("$BASE/preferences") { json(PrefsBody(push, muted.sorted())) } }.map { it.data.toDomain() }

    override suspend fun register(
        key: DeviceKey,
        platform: String,
        token: String,
        locale: String?,
    ): AppResult<Unit> =
        http.apiCallUnit {
            put("$BASE/devices/${key.deviceId.encodeURLPathPart()}") { json(DeviceBody(platform, token, key.publicKey, key.keyId, locale)) }
        }

    override suspend fun unregister(deviceId: String): AppResult<Unit> =
        http.apiCallUnit {
            delete("$BASE/devices/${deviceId.encodeURLPathPart()}")
        }

    override suspend fun clearLocal() {
        withContext(io) { queries.clear() }
    }

    private suspend fun save(dto: InboxDto) =
        withContext(io) { queries.put(ApiJson.encodeToString(InboxDto.serializer(), dto), clock.nowMillis()) }

    private companion object {
        const val BASE = "api/v1/notifications"
        const val POLL_MS = 15_000L
    }
}

private inline fun <reified T> HttpRequestBuilder.json(body: T) {
    contentType(ContentType.Application.Json)
    setBody(body)
}
