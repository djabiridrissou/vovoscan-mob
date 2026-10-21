package com.vovoscan.feature.episodes.data

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.Clock
import com.vovoscan.feature.episodes.domain.Episode
import com.vovoscan.feature.episodes.domain.EpisodesRepository
import com.vovoscan.feature.episodes.domain.PrepareResult
import com.vovoscan.feature.episodes.domain.Watch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlin.math.min

/**
 * Episodes, offline first: every answer (GET, prepare, reread, live snapshot) is saved on the device and screens read
 * the saved copy. [follow] keeps an SSE connection while the run goes on: reconnects with backoff (1 s, 2 s, 4 s…
 * up to 30 s) and, after [STREAM_ATTEMPTS] failed connections in a row (or `429 TOO_MANY_STREAMS`), polls `GET`
 * every 10 s, trying the stream again every minute.
 */
class EpisodesRepositoryImpl(
    private val remote: EpisodesRemoteDataSource,
    private val local: EpisodesLocalDataSource,
    private val clock: Clock,
) : EpisodesRepository {
    override fun observe(chapterId: String): Flow<Episode?> = local.observe(chapterId)

    override suspend fun refresh(chapterId: String): AppResult<Episode?> =
        when (val result = remote.episode(chapterId)) {
            is AppResult.Err -> result
            is AppResult.Ok -> AppResult.Ok(keep(chapterId, result.value))
        }

    override suspend fun prepare(chapterId: String): AppResult<PrepareResult> = run(chapterId, remote.prepare(chapterId))

    override suspend fun reread(chapterId: String): AppResult<PrepareResult> = run(chapterId, remote.reread(chapterId))

    private suspend fun run(
        chapterId: String,
        result: AppResult<RunDto>,
    ): AppResult<PrepareResult> {
        if (result !is AppResult.Ok) return result as AppResult.Err
        return when (val run = result.value) {
            is RunDto.Queued -> keep(chapterId, run.episode)?.let { AppResult.Ok(PrepareResult.Queued(it)) }
            is RunDto.Ready -> keep(chapterId, run.episode)?.let { AppResult.Ok(PrepareResult.AlreadyReady(it)) }
            is RunDto.Busy -> AppResult.Ok(PrepareResult.Busy(run.episode?.let { keep(chapterId, it) }))
        } ?: AppResult.Err(AppError.Unknown("unknown episode status"))
    }

    /** Saves the server's answer (none: forgets the chapter's episode) and returns it as the domain sees it. */
    private suspend fun keep(
        chapterId: String,
        dto: EpisodeDto?,
    ): Episode? {
        if (dto == null) {
            local.delete(chapterId)
            return null
        }
        local.save(dto)
        return dto.toDomain()
    }

    override suspend fun follow(chapterId: String): AppResult<Episode?> {
        var failures = 0
        var backoff = FIRST_RETRY_MS
        var polls = 0
        while (true) {
            if (failures < STREAM_ATTEMPTS) {
                var last: EpisodeDto? = null
                val result =
                    remote.events(chapterId) { dto ->
                        local.save(dto)
                        last = dto
                        !dto.isFinal
                    }
                val seen = last
                if (seen != null && seen.isFinal) return AppResult.Ok(seen.toDomain())
                when (result) {
                    // The server ended the stream (its 30-minute cap): reconnect at once.
                    is AppResult.Ok ->
                        if (seen != null) {
                            failures = 0
                            backoff = FIRST_RETRY_MS
                            continue
                        } else {
                            failures += 1
                        }
                    is AppResult.Err -> {
                        if (result.error.isCode(EPISODE_NOT_FOUND)) return AppResult.Ok(keep(chapterId, null))
                        if (result.error.isPermanent()) return result
                        failures = if (result.error == AppError.RateLimited) STREAM_ATTEMPTS else failures + 1
                    }
                }
                if (failures < STREAM_ATTEMPTS) {
                    delay(backoff)
                    backoff = min(backoff * 2, MAX_RETRY_MS)
                }
            } else {
                when (val result = remote.episode(chapterId)) {
                    is AppResult.Ok -> {
                        val dto = result.value ?: return AppResult.Ok(keep(chapterId, null))
                        local.save(dto)
                        if (dto.isFinal) return AppResult.Ok(dto.toDomain())
                    }
                    // Offline or a server hiccup: keep polling; the saved copy stays on screen.
                    is AppResult.Err -> if (result.error.isPermanent()) return result
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

    override suspend fun watch(
        chapterId: String,
        forceRefresh: Boolean,
    ): AppResult<Watch> {
        val cached = local.watch(chapterId)
        if (!forceRefresh && cached != null && cached.expiresAtMillis - clock.nowMillis() > WATCH_MARGIN_MS) return AppResult.Ok(cached)
        return when (val result = remote.watch(chapterId)) {
            is AppResult.Ok -> {
                local.saveWatch(result.value)
                AppResult.Ok(result.value.toDomain())
            }
            // Offline: the last timeline still plays what the image cache holds.
            is AppResult.Err -> if (result.error == AppError.Network && cached != null) AppResult.Ok(cached) else result
        }
    }

    override suspend fun clearLocal() = local.clear()

    companion object {
        const val STREAM_ATTEMPTS = 3
        const val FIRST_RETRY_MS = 1_000L
        const val MAX_RETRY_MS = 30_000L
        const val POLL_MS = 10_000L
        const val POLLS_BEFORE_STREAM = 6

        /** A watch whose URLs expire within this is fetched again. */
        const val WATCH_MARGIN_MS = 10 * 60_000L
    }
}

/** Errors that another try will not fix: the session ended, the role, the chapter gone or not a comic. */
private fun AppError.isPermanent(): Boolean =
    this == AppError.Unauthorized ||
        this == AppError.Forbidden ||
        (this is AppError.Rejected && code in setOf("CHAPTER_NOT_FOUND", "EPISODE_NOT_SUPPORTED", EPISODE_NOT_FOUND))
