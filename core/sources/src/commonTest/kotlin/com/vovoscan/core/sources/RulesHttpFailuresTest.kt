package com.vovoscan.core.sources

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.NetworkMonitor
import com.vovoscan.core.common.NetworkState
import com.vovoscan.core.common.SOURCE_RULES
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** A source that fails is told apart from a device without network, and from rules that can't read the site. */
class RulesHttpFailuresTest {
    private val manifest = SourceManifest("inkwell", Json.parseToJsonElement(Fixtures.inkwell).jsonObject, emptyMap())
    private val online = NetworkMonitor.Fixed(NetworkState(online = true, unmetered = true))
    private val offline = NetworkMonitor.Fixed(NetworkState(online = false, unmetered = false))

    private fun source(
        network: NetworkMonitor,
        engine: MockEngine,
    ) = RulesSource(manifest, RulesHttp(createRulesHttpClient(engine), network))

    @Test
    fun anErrorStatusMeansTheSourceIsNotAnswering() =
        runTest {
            for (status in listOf(HttpStatusCode.ServiceUnavailable, HttpStatusCode.BadGateway, HttpStatusCode.NotFound)) {
                val result = source(online, MockEngine { respond("down", status) }).catalog(1)
                assertEquals(AppResult.Err(AppError.SourceUnavailable("Inkwell (test comic site)")), result)
            }
        }

    @Test
    fun aTimeoutWhileOnlineMeansTheSourceIsNotAnswering() =
        runTest {
            val result = source(online, MockEngine { throw IOException("timed out") }).catalog(1)
            assertEquals(AppResult.Err(AppError.SourceUnavailable("Inkwell (test comic site)")), result)
        }

    @Test
    fun noNetworkIsOffline() =
        runTest {
            val result = source(offline, MockEngine { throw IOException("no route") }).catalog(1)
            assertEquals(AppResult.Err(AppError.Network), result)
        }

    @Test
    fun aSourceNeverReachesTheLocalNetwork() =
        runTest {
            var calls = 0
            val engine =
                MockEngine {
                    calls++
                    respond("", HttpStatusCode.OK)
                }
            val json =
                Json.parseToJsonElement(
                    """{"key":"lan","name":"LAN","hosts":["127.0.0.1","comics.inkwell.example"],"settings":{"base":{"type":"url"}}}""",
                ).jsonObject
            val lan = SourceManifest("lan", json, mapOf("base" to "https://192.168.1.10"))
            assertEquals(setOf("comics.inkwell.example"), lan.allowedHosts)
            val http = RulesHttp(createRulesHttpClient(engine), online)
            for (url in listOf("http://127.0.0.1/admin", "https://192.168.1.10/", "https://comics.inkwell.example:x@127.0.0.1/")) {
                assertEquals("HOST_NOT_ALLOWED", ((http.get(lan, url) as AppResult.Err).error as AppError.Rejected).code, url)
            }
            assertEquals(0, calls)
        }

    @Test
    fun aRedirectCannotSmuggleTheDeviceOntoTheLocalNetwork() =
        runTest {
            val requested = mutableListOf<String>()
            val engine =
                MockEngine { request ->
                    requested += request.url.toString()
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://comics.inkwell.example:x@127.0.0.1/"))
                }
            val result = RulesHttp(createRulesHttpClient(engine), online).get(manifest, "https://comics.inkwell.example/browse")
            assertEquals("HOST_NOT_ALLOWED", ((result as AppResult.Err).error as AppError.Rejected).code)
            assertEquals(listOf("https://comics.inkwell.example/browse"), requested)
        }

    @Test
    fun aPageTheRulesCannotReadIsARuleError() =
        runTest {
            val html = """<h1></h1><p>Redesigned site</p>"""
            val result =
                source(online, MockEngine { respond(html, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html")) })
                    .title("ink-road")
            assertEquals(SOURCE_RULES, ((result as AppResult.Err).error as AppError.Rejected).code)
        }
}
