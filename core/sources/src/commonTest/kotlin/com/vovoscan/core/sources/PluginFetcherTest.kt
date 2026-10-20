package com.vovoscan.core.sources

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginFetcherTest {
    private val requests = mutableListOf<Pair<String, String?>>()

    private fun fetcher(
        allowLocal: Boolean = false,
        routes: Map<String, Pair<HttpStatusCode, String>>,
    ) = PluginFetcher(
        createPluginHttpClient(
            MockEngine { request ->
                requests += request.url.toString() to request.headers[HttpHeaders.Cookie]
                val (status, body) = routes[request.url.toString()] ?: (HttpStatusCode.NotFound to "")
                if (status.value in 300..399) respond("", status, headersOf(HttpHeaders.Location, body)) else respond(body, status)
            },
        ),
        PluginLinkPolicy(allowLocal),
    )

    private fun code(result: AppResult<*>) = ((result as AppResult.Err).error as AppError.Rejected).code

    @Test
    fun followsUpToThreeHttpsRedirectsWithoutCookies() =
        runTest {
            val fetcher =
                fetcher(
                    routes =
                        mapOf(
                            "https://a.example/repo.json" to (HttpStatusCode.Found to "https://b.example/repo.json"),
                            "https://b.example/repo.json" to (HttpStatusCode.MovedPermanently to "/c/repo.json"),
                            "https://b.example/c/repo.json" to (HttpStatusCode.OK to "{}"),
                        ),
                )
            val fetched = assertIs<AppResult.Ok<Fetched>>(fetcher.fetch("https://a.example/repo.json", 100)).value
            assertEquals("{}", fetched.body.decodeToString())
            assertEquals("https://b.example/c/repo.json", fetched.finalUrl)
            assertTrue(requests.all { it.second == null })
        }

    @Test
    fun refusesInsecureRedirectsTooManyRedirectsAndLargeFiles() =
        runTest {
            val fetcher =
                fetcher(
                    routes =
                        mapOf(
                            "https://a.example/x" to (HttpStatusCode.Found to "http://a.example/x"),
                            "https://loop.example/1" to (HttpStatusCode.Found to "https://loop.example/2"),
                            "https://loop.example/2" to (HttpStatusCode.Found to "https://loop.example/3"),
                            "https://loop.example/3" to (HttpStatusCode.Found to "https://loop.example/4"),
                            "https://loop.example/4" to (HttpStatusCode.Found to "https://loop.example/5"),
                            "https://big.example/x" to (HttpStatusCode.OK to "x".repeat(101)),
                        ),
                )
            assertEquals("PLUGIN_REDIRECT", code(fetcher.fetch("https://a.example/x", 100)))
            assertEquals("PLUGIN_REDIRECT", code(fetcher.fetch("https://loop.example/1", 100)))
            assertEquals("PLUGIN_TOO_LARGE", code(fetcher.fetch("https://big.example/x", 100)))
            assertEquals("PLUGIN_FETCH", code(fetcher.fetch("https://missing.example/x", 100)))
            requests.clear()
            assertEquals("PLUGIN_LINK", code(fetcher.fetch("http://localhost/repo.json", 100)))
            assertNull(requests.firstOrNull())
        }

    @Test
    fun localServerOnlyInDebug() =
        runTest {
            val fetcher = fetcher(allowLocal = true, routes = mapOf("http://localhost:9000/repo.json" to (HttpStatusCode.OK to "{}")))
            assertIs<AppResult.Ok<Fetched>>(fetcher.fetch("http://localhost:9000/repo.json", 100))
        }
}
