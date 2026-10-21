package com.vovoscan.feature.episodes.data

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.map
import com.vovoscan.core.network.ApiData
import com.vovoscan.core.network.ApiJson
import com.vovoscan.core.network.apiCall
import com.vovoscan.core.network.errorOf
import com.vovoscan.core.network.isDecodingFailure
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.LineEnding
import io.ktor.utils.io.readLine
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException

/** What a prepare or reread request answered. */
sealed interface RunDto {
    /** 202: a run was queued. */
    data class Queued(
        val episode: EpisodeDto,
    ) : RunDto

    /** 200: a usable render existed, nothing was queued. */
    data class Ready(
        val episode: EpisodeDto,
    ) : RunDto

    /** 409 `EPISODE_BUSY`, with the episode as it is. */
    data class Busy(
        val episode: EpisodeDto?,
    ) : RunDto
}

/** Remote source: `api/v1/episodes/chapters/{chapterId}` (episode, prepare, reread, live events, watch). */
interface EpisodesRemoteDataSource {
    /** `Ok(null)`: no episode yet (`404 EPISODE_NOT_FOUND`). */
    suspend fun episode(chapterId: String): AppResult<EpisodeDto?>

    suspend fun prepare(chapterId: String): AppResult<RunDto>

    suspend fun reread(chapterId: String): AppResult<RunDto>

    suspend fun watch(chapterId: String): AppResult<WatchDto>

    /**
     * One connection to `…/events` (server-sent events): calls [onSnapshot] for every `event: snapshot`, until it
     * returns false (the caller has what it needs), the server closes the stream (`Ok`), or the connection fails.
     */
    suspend fun events(
        chapterId: String,
        onSnapshot: suspend (EpisodeDto) -> Boolean,
    ): AppResult<Unit>
}

class KtorEpisodesRemoteDataSource(
    private val http: HttpClient,
) : EpisodesRemoteDataSource {
    private fun path(chapterId: String) = "api/v1/episodes/chapters/$chapterId"

    override suspend fun episode(chapterId: String): AppResult<EpisodeDto?> =
        when (val result = http.apiCall<ApiData<EpisodeDto>> { get(path(chapterId)) }) {
            is AppResult.Ok -> AppResult.Ok(result.value.data)
            is AppResult.Err -> if (result.error.isCode(EPISODE_NOT_FOUND)) AppResult.Ok(null) else result
        }

    override suspend fun prepare(chapterId: String): AppResult<RunDto> = run(path(chapterId))

    override suspend fun reread(chapterId: String): AppResult<RunDto> = run(path(chapterId) + "/reread")

    override suspend fun watch(chapterId: String): AppResult<WatchDto> =
        http.apiCall<ApiData<WatchDto>> { get(path(chapterId) + "/watch") }.map { it.data }

    private suspend fun run(path: String): AppResult<RunDto> =
        try {
            val response = http.post(path)
            when {
                response.status == HttpStatusCode.Conflict -> busy(response)
                response.status.isSuccess() -> {
                    val episode = response.body<ApiData<EpisodeDto>>().data
                    AppResult.Ok(if (response.status == HttpStatusCode.Accepted) RunDto.Queued(episode) else RunDto.Ready(episode))
                }
                else -> AppResult.Err(errorOf(response))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SerializationException) {
            AppResult.Err(AppError.Unknown("unreadable response: ${e.message}"))
        } catch (e: Exception) {
            if (e.isDecodingFailure()) {
                AppResult.Err(
                    AppError.Unknown("unreadable response: ${e.message}"),
                )
            } else {
                AppResult.Err(AppError.Network)
            }
        }

    /** 409: `EPISODE_BUSY` carries the episode as it is, so the screen can follow the run already in progress. */
    private suspend fun busy(response: HttpResponse): AppResult<RunDto> {
        val envelope = runCatching { ApiJson.decodeFromString(BusyEnvelope.serializer(), response.bodyAsText()) }.getOrNull()
        return if (envelope?.data?.error ==
            EPISODE_BUSY
        ) {
            AppResult.Ok(RunDto.Busy(envelope.data.episode))
        } else {
            AppResult.Err(errorOf(response))
        }
    }

    override suspend fun events(
        chapterId: String,
        onSnapshot: suspend (EpisodeDto) -> Boolean,
    ): AppResult<Unit> =
        try {
            http
                .prepareGet(path(chapterId) + "/events") {
                    header(HttpHeaders.Accept, "text/event-stream")
                    header(HttpHeaders.CacheControl, "no-cache")
                    // The stream lasts up to 30 minutes; a heartbeat comes every 15 s, so 45 s of silence is a dead link.
                    timeout {
                        requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                        socketTimeoutMillis = STREAM_IDLE_MS
                    }
                }.execute { response ->
                    if (!response.status.isSuccess()) return@execute AppResult.Err(errorOf(response))
                    val channel = response.bodyAsChannel()
                    val parser = SseParser()
                    while (true) {
                        // SSE lines end with CR, LF or CRLF.
                        val line = channel.readLine(LineEnding.Lenient) ?: break
                        val event = parser.line(line) ?: continue
                        if (event.type != SNAPSHOT) continue
                        val dto = runCatching { ApiJson.decodeFromString(EpisodeDto.serializer(), event.data) }.getOrNull() ?: continue
                        if (!onSnapshot(dto)) return@execute AppResult.Ok(Unit)
                    }
                    AppResult.Ok(Unit)
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.Err(AppError.Network)
        }

    private companion object {
        const val SNAPSHOT = "snapshot"
        const val STREAM_IDLE_MS = 45_000L
    }
}

internal const val EPISODE_NOT_FOUND = "EPISODE_NOT_FOUND"
internal const val EPISODE_BUSY = "EPISODE_BUSY"

internal fun AppError.isCode(code: String) = this is AppError.Rejected && this.code == code
