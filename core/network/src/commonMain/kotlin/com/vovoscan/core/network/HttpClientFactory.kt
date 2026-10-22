package com.vovoscan.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.serialization.kotlinx.json.json
import kotlinx.io.IOException
import kotlinx.serialization.json.Json

/** `baseUrl` ends with "/"; paths are relative ("api/v1/…"). */
data class ApiConfig(
    val baseUrl: String,
)

/** Session token (read from the secure store), null when signed out. */
fun interface TokenProvider {
    suspend fun token(): String?
}

val ApiJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

/**
 * The app's HTTP client. `X-Client: mobile`: the API returns the session token in the body (no cookie)
 * and does not require a CSRF token, which is for browsers only. The token is sent as `Authorization: Bearer`, and
 * only to the API's own host: an absolute URL elsewhere (a file link) never gets the session.
 */
fun createHttpClient(
    engine: HttpClientEngine,
    config: ApiConfig,
    tokens: TokenProvider,
): HttpClient =
    HttpClient(engine) {
        expectSuccess = false
        install(ContentNegotiation) { json(ApiJson) }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 30_000
        }
        defaultRequest { url(config.baseUrl) }
        val apiHost = Url(config.baseUrl).host
        install(
            createClientPlugin("SessionToken") {
                onRequest { request, _ ->
                    if (request.url.host != apiHost) return@onRequest
                    request.headers["X-Client"] = "mobile"
                    tokens.token()?.let { request.headers[HttpHeaders.Authorization] = "Bearer $it" }
                }
            },
        )
    }

/** Thrown for every request of a client that simulates having no network (debug builds only). */
class SimulatedOfflineException : IOException("No network (simulated).")

/**
 * The same client, failing every request as a lost connection would (debug builds, to check offline reading in the
 * iOS simulator, which shares the Mac's network).
 */
fun HttpClient.simulatingOffline(): HttpClient =
    config {
        install(createClientPlugin("SimulatedOffline") { onRequest { _, _ -> throw SimulatedOfflineException() } })
    }
