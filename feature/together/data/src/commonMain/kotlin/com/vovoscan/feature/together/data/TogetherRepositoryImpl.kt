package com.vovoscan.feature.together.data

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.map
import com.vovoscan.core.network.ApiData
import com.vovoscan.core.network.ApiJson
import com.vovoscan.core.network.SseParser
import com.vovoscan.core.network.apiCall
import com.vovoscan.core.network.apiCallUnit
import com.vovoscan.core.network.errorOf
import com.vovoscan.feature.together.domain.Device
import com.vovoscan.feature.together.domain.InvitePreview
import com.vovoscan.feature.together.domain.PagePoint
import com.vovoscan.feature.together.domain.Place
import com.vovoscan.feature.together.domain.Room
import com.vovoscan.feature.together.domain.RoomBrief
import com.vovoscan.feature.together.domain.Signal
import com.vovoscan.feature.together.domain.SignalKind
import com.vovoscan.feature.together.domain.Sticker
import com.vovoscan.feature.together.domain.TogetherRepository
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.utils.io.LineEnding
import io.ktor.utils.io.readLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.min

/**
 * `api/v1/reading-rooms`. Online only: nothing is queued (a circle is live or it is nothing). [live] keeps a room's
 * event stream open, with the contract's reconnection rules.
 */
class TogetherRepositoryImpl(
    private val http: HttpClient,
) : TogetherRepository {
    private fun path(id: String) = "$BASE/${id.encodeURLPathPart()}"

    override suspend fun rooms(): AppResult<List<RoomBrief>> =
        http.apiCall<ApiData<List<RoomBriefDto>>> { get(BASE) }.map { list -> list.data.map { it.toDomain() } }

    override suspend fun open(
        chapterId: String,
        place: Place?,
        language: String?,
        device: Device,
    ): AppResult<Room> =
        http
            .apiCall<ApiData<RoomDto>> { post(BASE) { json(OpenBody(chapterId, place?.let(PlaceDto::of), language, device.wire)) } }
            .map { it.data.toDomain() }

    override suspend fun preview(code: String): AppResult<InvitePreview> =
        http.apiCall<ApiData<PreviewDto>> { get("$BASE/invites/${code.encodeURLPathPart()}") }.map { it.data.toDomain() }

    override suspend fun join(
        code: String,
        language: String?,
        device: Device,
    ): AppResult<Room> =
        http
            .apiCall<ApiData<RoomDto>> { post("$BASE/join") { json(JoinBody(code, language, device.wire)) } }
            .map { it.data.toDomain() }

    override suspend fun room(id: String): AppResult<Room> = http.apiCall<ApiData<RoomDto>> { get(path(id)) }.map { it.data.toDomain() }

    override suspend fun live(
        id: String,
        onRoom: suspend (Room) -> Boolean,
    ): AppResult<Unit> {
        var failures = 0
        var backoff = FIRST_RETRY_MS
        var polls = 0
        while (true) {
            if (failures < STREAM_ATTEMPTS) {
                var seen = false
                var stop = false
                val result =
                    events(id) { room ->
                        seen = true
                        stop = !onRoom(room) || !room.open
                        !stop
                    }
                if (stop) return AppResult.Ok(Unit)
                when (result) {
                    // The server ended the stream (its 30-minute cap): reconnect at once.
                    is AppResult.Ok ->
                        if (seen) {
                            failures = 0
                            backoff = FIRST_RETRY_MS
                            continue
                        } else {
                            failures += 1
                        }
                    is AppResult.Err -> {
                        if (result.error.isFinal()) return result
                        failures = if (result.error == AppError.RateLimited) STREAM_ATTEMPTS else failures + 1
                    }
                }
                if (failures < STREAM_ATTEMPTS) {
                    delay(backoff)
                    backoff = min(backoff * 2, MAX_RETRY_MS)
                }
            } else {
                when (val result = room(id)) {
                    is AppResult.Ok -> if (!onRoom(result.value) || !result.value.open) return AppResult.Ok(Unit)
                    // Offline or a server hiccup: keep polling.
                    is AppResult.Err -> if (result.error.isFinal()) return result
                }
                delay(POLL_MS)
                polls += 1
                if (polls >= POLLS_BEFORE_STREAM) {
                    polls = 0
                    failures = 0
                    backoff = FIRST_RETRY_MS
                }
            }
        }
    }

    /** One connection to `…/events`: [onRoom] for every `event: snapshot` until it answers false or the stream ends. */
    private suspend fun events(
        id: String,
        onRoom: suspend (Room) -> Boolean,
    ): AppResult<Unit> =
        try {
            http
                .prepareGet("${path(id)}/events") {
                    header(HttpHeaders.Accept, "text/event-stream")
                    header(HttpHeaders.CacheControl, "no-cache")
                    // A comment comes every 15 s: 45 s of silence is a dead link.
                    timeout {
                        requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                        socketTimeoutMillis = STREAM_IDLE_MS
                    }
                }.execute { response ->
                    if (!response.status.isSuccess()) return@execute AppResult.Err(errorOf(response))
                    val channel = response.bodyAsChannel()
                    val parser = SseParser()
                    while (true) {
                        val line = channel.readLine(LineEnding.Lenient) ?: break
                        val event = parser.line(line) ?: continue
                        if (event.type != SNAPSHOT) continue
                        val room =
                            runCatching { ApiJson.decodeFromString(RoomDto.serializer(), event.data).toDomain() }.getOrNull() ?: continue
                        if (!onRoom(room)) return@execute AppResult.Ok(Unit)
                    }
                    AppResult.Ok(Unit)
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.Err(AppError.Network)
        }

    override suspend fun me(
        id: String,
        place: Place?,
        following: Boolean?,
        language: String?,
    ): AppResult<Unit> = http.apiCallUnit { put("${path(id)}/me") { json(MeBody(place?.let(PlaceDto::of), following, language)) } }

    override suspend fun leave(id: String): AppResult<Unit> = http.apiCallUnit { delete("${path(id)}/me") }

    override suspend fun signal(
        id: String,
        kind: SignalKind,
        sticker: Sticker?,
        text: String?,
        at: PagePoint?,
    ): AppResult<Signal> =
        http
            .apiCall<ApiData<SignalDto>> {
                post("${path(id)}/signals") {
                    json(SignalBody(kind.wire, sticker?.key, text, at?.let { PagePointDto(it.page, it.x, it.y) }))
                }
            }.let { result ->
                when (result) {
                    is AppResult.Err -> result
                    is AppResult.Ok ->
                        result.value.data
                            .toDomain()
                            ?.let { AppResult.Ok(it) } ?: AppResult.Err(AppError.Unknown("signal"))
                }
            }

    override suspend fun moveTo(
        id: String,
        chapterId: String,
        place: Place?,
    ): AppResult<Room> =
        http
            .apiCall<ApiData<RoomDto>> { put("${path(id)}/chapter") { json(MoveBody(chapterId, place?.let(PlaceDto::of))) } }
            .map { it.data.toDomain() }

    override suspend fun handOver(
        id: String,
        userId: String,
    ): AppResult<Room> = http.apiCall<ApiData<RoomDto>> { put("${path(id)}/host") { json(HostBody(userId)) } }.map { it.data.toDomain() }

    override suspend fun lock(
        id: String,
        locked: Boolean,
    ): AppResult<Room> = http.apiCall<ApiData<RoomDto>> { patch(path(id)) { json(LockBody(locked)) } }.map { it.data.toDomain() }

    override suspend fun renewCode(id: String): AppResult<Room> =
        http.apiCall<ApiData<RoomDto>> { post("${path(id)}/code") }.map { it.data.toDomain() }

    override suspend fun remove(
        id: String,
        userId: String,
    ): AppResult<Unit> = http.apiCallUnit { delete("${path(id)}/members/${userId.encodeURLPathPart()}") }

    override suspend fun close(id: String): AppResult<Unit> = http.apiCallUnit { delete(path(id)) }

    private companion object {
        const val BASE = "api/v1/reading-rooms"
        const val SNAPSHOT = "snapshot"
        const val STREAM_IDLE_MS = 45_000L
        const val STREAM_ATTEMPTS = 3
        const val FIRST_RETRY_MS = 1_000L
        const val MAX_RETRY_MS = 30_000L
        const val POLL_MS = 5_000L
        const val POLLS_BEFORE_STREAM = 12
    }
}

private inline fun <reified T> HttpRequestBuilder.json(body: T) {
    contentType(ContentType.Application.Json)
    setBody(body)
}

/** No retry helps: the room is gone or closed, the caller was removed, or circles are off for the account. */
private fun AppError.isFinal(): Boolean =
    this == AppError.Unauthorized ||
        this == AppError.Forbidden ||
        (this is AppError.Rejected && code in FINAL_CODES)

private val FINAL_CODES =
    setOf("ROOM_NOT_FOUND", "ROOM_CLOSED", "ROOM_REMOVED", "ROOM_CHAPTER_HIDDEN", "FEATURE_UNAVAILABLE", "FEATURE_DISABLED")
