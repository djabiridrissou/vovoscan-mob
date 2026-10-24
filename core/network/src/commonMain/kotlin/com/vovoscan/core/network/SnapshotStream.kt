package com.vovoscan.core.network

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.LineEnding
import io.ktor.utils.io.readLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.min

/**
 * Follows a server-sent `event: snapshot` stream (the API's `sse_response`: a snapshot at once and on every change, a
 * comment every 15 s, 30 minutes per stream) with the contracts' reconnection rules: at once when the server ends the
 * stream, backoff 1 → 30 s on failures, and after 3 failures (or a 429) [poll] every [pollMs], the stream tried again
 * after [pollsBeforeStream] polls. [onItem] answers false to stop; an error [isFinal] says no retry helps returns.
 */
suspend fun <T> HttpClient.followSnapshots(
    path: String,
    decode: (String) -> T?,
    poll: suspend () -> AppResult<T>,
    isFinal: (AppError) -> Boolean,
    pollMs: Long = 5_000L,
    pollsBeforeStream: Int = 12,
    onItem: suspend (T) -> Boolean,
): AppResult<Unit> {
    var failures = 0
    var backoff = FIRST_RETRY_MS
    var polls = 0
    while (true) {
        if (failures < STREAM_ATTEMPTS) {
            var seen = false
            var stop = false
            val result =
                snapshots(path, decode) { item ->
                    seen = true
                    stop = !onItem(item)
                    !stop
                }
            if (stop) return AppResult.Ok(Unit)
            when (result) {
                is AppResult.Ok ->
                    if (seen) {
                        failures = 0
                        backoff = FIRST_RETRY_MS
                        continue
                    } else {
                        failures += 1
                    }
                is AppResult.Err -> {
                    if (isFinal(result.error)) return result
                    failures = if (result.error == AppError.RateLimited) STREAM_ATTEMPTS else failures + 1
                }
            }
            if (failures < STREAM_ATTEMPTS) {
                delay(backoff)
                backoff = min(backoff * 2, MAX_RETRY_MS)
            }
        } else {
            when (val result = poll()) {
                is AppResult.Ok -> if (!onItem(result.value)) return AppResult.Ok(Unit)
                // Offline or a server hiccup: keep polling.
                is AppResult.Err -> if (isFinal(result.error)) return result
            }
            delay(pollMs)
            polls += 1
            if (polls >= pollsBeforeStream) {
                polls = 0
                failures = 0
                backoff = FIRST_RETRY_MS
            }
        }
    }
}

/** One connection: [onItem] for every readable `event: snapshot` until it answers false or the stream ends. */
private suspend fun <T> HttpClient.snapshots(
    path: String,
    decode: (String) -> T?,
    onItem: suspend (T) -> Boolean,
): AppResult<Unit> =
    try {
        prepareGet(path) {
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
                if (event.type != "snapshot") continue
                val item = runCatching { decode(event.data) }.getOrNull() ?: continue
                if (!onItem(item)) return@execute AppResult.Ok(Unit)
            }
            AppResult.Ok(Unit)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppResult.Err(AppError.Network)
    }

private const val STREAM_IDLE_MS = 45_000L
private const val STREAM_ATTEMPTS = 3
private const val FIRST_RETRY_MS = 1_000L
private const val MAX_RETRY_MS = 30_000L
