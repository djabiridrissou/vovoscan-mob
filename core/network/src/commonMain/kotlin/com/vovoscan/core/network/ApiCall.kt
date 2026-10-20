package com.vovoscan.core.network

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/** Enveloppe d'erreur de l'API : `{ "code": 409, "message": "…", "data": { "error": "INVITE_INVALID" } }`. */
@Serializable
data class ApiErrorEnvelope(
    val code: Int,
    val message: String = "",
    val data: ApiErrorData? = null,
)

@Serializable
data class ApiErrorData(
    val error: String,
)

/** Enveloppe de succès d'un élément : `{ "data": … }`. */
@Serializable
data class ApiData<T>(
    val data: T,
)

/** Enveloppe d'une liste paginée : `{ "data": [...], "total", "page", "perPage", "pages" }`. */
@Serializable
data class ApiPage<T>(
    val data: List<T> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val perPage: Int = 0,
    val pages: Int = 0,
) {
    val hasNext: Boolean get() = page < pages
}

/**
 * Exécute une requête et la traduit en [AppResult] : jamais d'exception hors annulation.
 * Sans réponse (coupure, délai) : [AppError.Network].
 */
suspend inline fun <reified T> HttpClient.apiCall(request: HttpClient.() -> HttpResponse): AppResult<T> =
    try {
        val response = request()
        if (response.status.isSuccess()) AppResult.Ok(response.body<T>()) else AppResult.Err(errorOf(response))
    } catch (e: CancellationException) {
        throw e
    } catch (e: SerializationException) {
        AppResult.Err(AppError.Unknown("unreadable response: ${e.message}"))
    } catch (e: Exception) {
        if (e.isDecodingFailure()) AppResult.Err(AppError.Unknown("unreadable response: ${e.message}")) else AppResult.Err(AppError.Network)
    }

/** Requête sans corps de réponse utile (204). */
suspend inline fun HttpClient.apiCallUnit(request: HttpClient.() -> HttpResponse): AppResult<Unit> =
    try {
        val response = request()
        if (response.status.isSuccess()) AppResult.Ok(Unit) else AppResult.Err(errorOf(response))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppResult.Err(AppError.Network)
    }

/** Ktor enveloppe parfois l'erreur de désérialisation (JsonConvertException). */
fun Throwable.isDecodingFailure(): Boolean =
    generateSequence(this) { it.cause }.any { it is SerializationException || it::class.simpleName == "JsonConvertException" }

suspend fun errorOf(response: HttpResponse): AppError {
    val body = runCatching { ApiJson.decodeFromString(ApiErrorEnvelope.serializer(), response.bodyAsText()) }.getOrNull()
    val code = body?.data?.error
    return when {
        // Un 401 précis (« INVALID_CREDENTIALS » à la connexion) garde son message ; le 401 générique = session finie.
        response.status == HttpStatusCode.Unauthorized && (code == null || code == "UNAUTHORIZED") -> AppError.Unauthorized
        response.status == HttpStatusCode.Forbidden && (code == null || code == "PERMISSION_DENIED") -> AppError.Forbidden
        response.status == HttpStatusCode.TooManyRequests -> AppError.RateLimited
        code != null -> AppError.Rejected(code, body.message)
        else -> AppError.Unknown("HTTP ${response.status.value}")
    }
}
