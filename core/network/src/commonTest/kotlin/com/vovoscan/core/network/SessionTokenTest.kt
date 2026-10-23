package com.vovoscan.core.network

import com.vovoscan.core.common.i18n.I18n
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SessionTokenTest {
    @Test
    fun theSessionGoesOnlyToTheApiHost() =
        runTest {
            val seen = mutableMapOf<String, String?>()
            val client =
                createHttpClient(
                    MockEngine { request ->
                        seen[request.url.host] = request.headers[HttpHeaders.Authorization]
                        respond("")
                    },
                    ApiConfig("https://api.test/"),
                ) { "secret-token" }
            client.get("api/v1/me")
            client.get("https://files.elsewhere.example/page.jpg")
            assertEquals("Bearer secret-token", seen["api.test"])
            assertEquals(null, seen["files.elsewhere.example"])
        }

    @Test
    fun theUiLanguageGoesOnlyToTheApiHost() =
        runTest {
            val seen = mutableMapOf<String, String?>()
            val client =
                createHttpClient(
                    MockEngine { request ->
                        seen[request.url.host] = request.headers[HttpHeaders.AcceptLanguage]
                        respond("")
                    },
                    ApiConfig("https://api.test/"),
                ) { null }
            I18n.use("fr")
            try {
                client.get("api/v1/me")
                client.get("https://source.example/catalog")
            } finally {
                I18n.use("en")
            }
            assertEquals("fr", seen["api.test"])
            assertEquals(null, seen["source.example"])
        }

    @Test
    fun aSimulatedOfflineClientFailsLikeALostConnection() =
        runTest {
            val client = createHttpClient(MockEngine { respond("") }, ApiConfig("https://api.test/")) { null }.simulatingOffline()
            val failure = runCatching { client.get("api/v1/me") }.exceptionOrNull()
            assertIs<SimulatedOfflineException>(failure)
        }
}
