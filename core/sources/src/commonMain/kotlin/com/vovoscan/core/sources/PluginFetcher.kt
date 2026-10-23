package com.vovoscan.core.sources

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.i18n.t
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray

/**
 * HTTP client for plugin files (repository indexes, manifests): no vovoscan session, no cookies, redirects
 * followed by hand, 15 s at most.
 */
fun createPluginHttpClient(engine: HttpClientEngine): HttpClient =
    HttpClient(engine) {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 15_000
            socketTimeoutMillis = 15_000
        }
    }

/** Whether debug builds may also fetch plugins from a local development server (`http://localhost`, …). */
data class PluginLinkPolicy(
    val allowLocal: Boolean,
)

/**
 * Downloads plugin files from third-party hosts: https only (or local in debug builds), at most 3 redirects, each
 * one checked again, and a byte limit enforced while reading.
 */
class PluginFetcher(
    private val client: HttpClient,
    private val policy: PluginLinkPolicy = PluginLinkPolicy(allowLocal = false),
) {
    suspend fun fetch(
        url: String,
        maxBytes: Int,
    ): AppResult<Fetched> {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            if (!PluginLinks.isAllowed(current, policy.allowLocal)) {
                return if (current == url) PluginErrors.linkNotAllowed else unsafeRedirect
            }
            val step =
                try {
                    client.prepareGet(current) { header(HttpHeaders.UserAgent, RULES_USER_AGENT) }.execute { read(it, current, maxBytes) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return AppResult.Err(AppError.Network)
                }
            when (step) {
                is Step.Done -> return step.result
                is Step.Redirect -> current = step.url
            }
        }
        return PluginErrors.rejected("PLUGIN_REDIRECT", t("plugins.tooManyRedirects"))
    }

    private suspend fun read(
        response: HttpResponse,
        url: String,
        maxBytes: Int,
    ): Step {
        val location = response.headers[HttpHeaders.Location]
        val declared = response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0
        return when {
            response.status.value in 300..399 && location != null -> Step.Redirect(absoluteUrl(url, location.trim()))
            !response.status.isSuccess() ->
                Step.Done(
                    PluginErrors.rejected(
                        "PLUGIN_FETCH",
                        t(
                            "plugins.linkStatus",
                            "status" to response.status.value.toString(),
                        ),
                    ),
                )
            declared > maxBytes -> Step.Done(PluginErrors.tooLarge)
            else -> {
                // Read one byte more than allowed: a longer body is refused without being kept.
                val body = response.bodyAsChannel().readRemaining(maxBytes + 1L).readByteArray()
                Step.Done(if (body.size > maxBytes) PluginErrors.tooLarge else AppResult.Ok(Fetched(body, url)))
            }
        }
    }

    private val unsafeRedirect get() = PluginErrors.rejected("PLUGIN_REDIRECT", t("plugins.unsafeRedirect"))

    private sealed interface Step {
        class Redirect(
            val url: String,
        ) : Step

        class Done(
            val result: AppResult<Fetched>,
        ) : Step
    }

    private companion object {
        const val MAX_REDIRECTS = 3
    }
}
