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

/** API error envelope: `{ "code": 409, "message": "…", "data": { "error": "INVITE_INVALID" } }`. */
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

/** Success envelope for a single item: `{ "data": … }`. */
@Serializable
data class ApiData<T>(
    val data: T,
)

/** Envelope for a paginated list: `{ "data": [...], "total", "page", "perPage", "pages" }`. */
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
 * Runs a request and maps it to an [AppResult]: never throws, except on cancellation.
 * No response (connection lost, timeout): [AppError.Network].
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

/** Request with no useful response body (204). */
suspend inline fun HttpClient.apiCallUnit(request: HttpClient.() -> HttpResponse): AppResult<Unit> =
    try {
        val response = request()
        if (response.status.isSuccess()) AppResult.Ok(Unit) else AppResult.Err(errorOf(response))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppResult.Err(AppError.Network)
    }

/** Ktor sometimes wraps the deserialization error (JsonConvertException). */
fun Throwable.isDecodingFailure(): Boolean =
    generateSequence(this) { it.cause }.any { it is SerializationException || it::class.simpleName == "JsonConvertException" }

suspend fun errorOf(response: HttpResponse): AppError {
    val body = runCatching { ApiJson.decodeFromString(ApiErrorEnvelope.serializer(), response.bodyAsText()) }.getOrNull()
    val code = body?.data?.error
    return when {
        // A specific 401 ("INVALID_CREDENTIALS" at sign-in) keeps its message; a generic 401 means the session has ended.
        response.status == HttpStatusCode.Unauthorized && (code == null || code == "UNAUTHORIZED") -> AppError.Unauthorized
        response.status == HttpStatusCode.Forbidden && (code == null || code == "PERMISSION_DENIED") -> AppError.Forbidden
        // The credits' daily cap is a 429 with its own meaning (the screen says when runs resume), not a rate limit.
        response.status == HttpStatusCode.TooManyRequests && code == DAILY_CAP -> AppError.Rejected(DAILY_CAP, body?.message.orEmpty())
        // Groups and games say why (a flood, the day's groups made, a full queue of requests): their own line, not "slow down".
        response.status == HttpStatusCode.TooManyRequests && code != null && LIMIT_CODES.any { code.startsWith(it) } ->
            AppError.Rejected(code, body.message)
        response.status == HttpStatusCode.TooManyRequests -> AppError.RateLimited
        code != null -> AppError.Rejected(code, body.message)
        else -> AppError.Unknown("HTTP ${response.status.value}")
    }
}

private val LIMIT_CODES = listOf("GROUP_", "GAME_")

/** `429` of the credits: the platform's daily limit is reached (runs on its keys resume tomorrow). */
const val DAILY_CAP = "DAILY_CAP"
