package com.vovoscan.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** `baseUrl` se termine par « / » ; les chemins sont relatifs (« api/v1/… »). */
data class ApiConfig(
    val baseUrl: String,
)

/** Jeton de session (lu dans le coffre sécurisé), null hors connexion. */
fun interface TokenProvider {
    suspend fun token(): String?
}

val ApiJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

/**
 * Client HTTP de l'application. `X-Client: mobile` : l'API rend le jeton de session dans le corps (pas de cookie)
 * et n'exige pas de jeton CSRF, réservé au navigateur. Le jeton part en `Authorization: Bearer`.
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
        install(
            createClientPlugin("SessionToken") {
                onRequest { request, _ ->
                    request.headers["X-Client"] = "mobile"
                    tokens.token()?.let { request.headers[HttpHeaders.Authorization] = "Bearer $it" }
                }
            },
        )
    }
