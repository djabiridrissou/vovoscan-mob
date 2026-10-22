package com.vovoscan.core.sources

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.HostPolicy
import com.vovoscan.core.common.NetworkMonitor
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.request
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.readByteArray
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
            // 30 s without a byte is a stalled source, whatever is being fetched; the total depends on what it is.
            socketTimeoutMillis = 30_000
            requestTimeoutMillis = Transfer.Page.totalMillis
        }
    }

/**
 * What a request fetches, and so how long it may take and how big it may be: a page of the source (JSON, HTML, XML) is
 * quick and small; an image or a book file takes as long as its bytes keep coming (a 25 MB EPUB on a modest link
 * needs more than 30 s), within a total and a size of their own.
 */
enum class Transfer(
    val totalMillis: Long,
    val maxBytes: Long,
) {
    Page(30_000, 5L * 1024 * 1024),
    Image(120_000, 30L * 1024 * 1024),
    File(600_000, 100L * 1024 * 1024),
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
        transfer: Transfer = Transfer.Page,
        // The source's page images only (`content.comic.referer`): sent to its allowed hosts, redirects included.
        referer: String? = null,
    ): AppResult<Fetched> {
        val hosts = manifest.allowedHosts
        var current = url
        var params = query
        repeat(4) {
            val host = hostOf(current)
            val scheme = current.startsWith("https://", ignoreCase = true) || current.startsWith("http://", ignoreCase = true)
            // Checked again on every hop: a redirect can't lead to a host the source didn't declare, nor off the internet.
            if (host == null || host !in hosts || !HostPolicy.isPublic(host) || !scheme) {
                return AppResult.Err(AppError.Rejected("HOST_NOT_ALLOWED", "This source can't reach $host."))
            }
            wait(manifest)
            // Streamed: the headers come first, so a file announced too large is refused before any of it is read.
            val step =
                try {
                    client
                        .prepareGet(current) {
                            timeout { requestTimeoutMillis = transfer.totalMillis }
                            header(HttpHeaders.UserAgent, RULES_USER_AGENT)
                            if (referer != null) header(HttpHeaders.Referrer, referer)
                            params.forEach { (k, v) -> parameter(k, v) }
                        }.execute { read(it, manifest, transfer) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return AppResult.Err(if (network.state.value.online) AppError.SourceUnavailable(manifest.name) else AppError.Network)
                }
            when (step) {
                is Step.Done -> return step.result
                is Step.Redirect -> {
                    current = step.url
                    params = emptyMap()
                }
            }
        }
        return AppResult.Err(AppError.Rejected("SOURCE_ERROR", "Too many redirects."))
    }

    private suspend fun read(
        response: HttpResponse,
        manifest: SourceManifest,
        transfer: Transfer,
    ): Step {
        val location = response.headers[HttpHeaders.Location]
        if (response.status.value in 300..399 && location != null) {
            return Step.Redirect(absoluteUrl(response.request.url.toString(), location))
        }
        // Down, overloaded, refusing or gone: the source isn't answering (the reader can retry later).
        if (!response.status.isSuccess()) return Step.Done(AppResult.Err(AppError.SourceUnavailable(manifest.name)))
        val announced = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (announced != null && announced > transfer.maxBytes) return Step.Done(tooLarge())
        // At most one byte past the limit: an unannounced oversized file never fills the memory.
        val body = response.bodyAsChannel().readRemaining(transfer.maxBytes + 1).readByteArray()
        if (body.size > transfer.maxBytes) return Step.Done(tooLarge())
        return Step.Done(AppResult.Ok(Fetched(body, response.request.url.toString())))
    }

    private fun tooLarge(): AppResult<Nothing> =
        AppResult.Err(AppError.Rejected("SOURCE_ERROR", "The source answered with a file too large."))

    private sealed interface Step {
        class Redirect(
            val url: String,
        ) : Step

        class Done(
            val result: AppResult<Fetched>,
        ) : Step
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
