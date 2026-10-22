package com.vovoscan.core.sources

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.NetworkMonitor
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.request
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

const val RULES_USER_AGENT = "vovoscan/0.1 (+https://vovoscan.yieldgrowth.org)"

/**
 * HTTP client for rule-based sources, separate from the API client: it never sends the vovoscan session, does not
 * follow redirects on its own (each one is checked again) and only contacts the hosts the source declares.
 */
fun createRulesHttpClient(engine: HttpClientEngine): HttpClient =
    HttpClient(engine) {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 30_000
        }
    }

class Fetched(
    val body: ByteArray,
    val finalUrl: String,
)

/**
 * Failures are told apart: no network at all is [AppError.Network] (the offline banner); a source that doesn't
 * answer while the device is online (site down, error status, timeout) is [AppError.SourceUnavailable] with the
 * source's name.
 */
class RulesHttp(
    private val client: HttpClient,
    private val network: NetworkMonitor = NetworkMonitor.AssumeOnline,
) {
    private val throttle = Mutex()
    private val recent = mutableMapOf<String, MutableList<Long>>()

    suspend fun get(
        manifest: SourceManifest,
        url: String,
        query: Map<String, String> = emptyMap(),
        maxBytes: Int = 30 * 1024 * 1024,
    ): AppResult<Fetched> {
        val hosts = manifest.allowedHosts
        var current = url
        var params = query
        repeat(4) {
            val host = hostOf(current)
            if (host == null || host !in hosts || !(current.startsWith("https://") || current.startsWith("http://"))) {
                return AppResult.Err(AppError.Rejected("HOST_NOT_ALLOWED", "This source can't reach $host."))
            }
            wait(manifest)
            val response: HttpResponse =
                try {
                    client.get(current) {
                        header(HttpHeaders.UserAgent, RULES_USER_AGENT)
                        params.forEach { (k, v) -> parameter(k, v) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return AppResult.Err(if (network.state.value.online) AppError.SourceUnavailable(manifest.name) else AppError.Network)
                }
            val location = response.headers[HttpHeaders.Location]
            if (response.status.value in 300..399 && location != null) {
                current = absoluteUrl(response.request.url.toString(), location)
                params = emptyMap()
                return@repeat
            }
            // Down, overloaded, refusing or gone: the source isn't answering (the reader can retry later).
            if (!response.status.isSuccess()) return AppResult.Err(AppError.SourceUnavailable(manifest.name))
            val body =
                try {
                    response.bodyAsBytes()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return AppResult.Err(if (network.state.value.online) AppError.SourceUnavailable(manifest.name) else AppError.Network)
                }
            if (body.size > maxBytes) return AppResult.Err(AppError.Rejected("SOURCE_ERROR", "The source answered with a file too large."))
            return AppResult.Ok(Fetched(body, response.request.url.toString()))
        }
        return AppResult.Err(AppError.Rejected("SOURCE_ERROR", "Too many redirects."))
    }

    /** Per-source rate limit, taken from the manifest: wait rather than hammer the site. */
    @OptIn(ExperimentalTime::class)
    private suspend fun wait(manifest: SourceManifest) {
        val pause =
            throttle.withLock {
                val now = Clock.System.now().toEpochMilliseconds()
                val window = recent.getOrPut(manifest.key) { mutableListOf() }
                window.removeAll { now - it >= 60_000 }
                val delayMs = if (window.size >= manifest.requestsPerMinute) 60_000 - (now - window.first()) else 0
                window += now + delayMs
                delayMs
            }
        if (pause > 0) delay(pause)
    }
}
