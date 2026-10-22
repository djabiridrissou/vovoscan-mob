package com.vovoscan.core.network

import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.ImportStatus
import com.vovoscan.core.common.ImportedChapter
import com.vovoscan.core.common.SourceUnit
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ApiChapterImportsTest {
    private val unit = SourceUnit("inkwell", "t 9", "u3")
    private val requests = mutableListOf<HttpRequestData>()

    private fun imports(answer: (HttpRequestData) -> Pair<HttpStatusCode, String>) =
        ApiChapterImports(
            createHttpClient(
                MockEngine { request ->
                    requests += request
                    val (status, body) = answer(request)
                    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
                },
                ApiConfig("https://api.test/"),
            ) { "token" },
        ) { "fr" }

    private val mirrored = """{"data":{"titleId":"t-1","chapterId":"c-1","status":"fetching","pageCount":0,"error":null}}"""

    @Test
    fun importSendsTheRefsAndTheDeviceLanguage() =
        runTest {
            val result = imports { HttpStatusCode.OK to mirrored }.import(unit)
            assertEquals(AppResult.Ok(ImportedChapter("t-1", "c-1", ImportStatus.Fetching, 0)), result)
            val request = requests.single()
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/api/v1/source-reader/inkwell/import", request.url.encodedPath)
            assertEquals("fr", request.url.parameters["lang"])
            assertEquals("""{"titleRef":"t 9","unitRef":"u3"}""", (request.body as TextContent).text)
        }

    @Test
    fun findReadsTheTitleThenTheCopyAndRemembersIt() =
        runTest {
            val imports =
                imports { request ->
                    if (request.url.encodedPath.endsWith("/title")) {
                        HttpStatusCode.OK to
                            """{"data":{"id":"t 9","units":[{"id":"u2"},{"id":"u3","chapterId":"c-1","fetchStatus":"ready"}]}}"""
                    } else {
                        HttpStatusCode.OK to """{"data":{"titleId":"t-1","chapterId":"c-1","status":"ready","pageCount":12}}"""
                    }
                }
            val expected = ImportedChapter("t-1", "c-1", ImportStatus.Ready, 12)
            assertEquals(expected, imports.find(unit))
            assertEquals("t 9", requests.first().url.parameters["ref"])
            assertEquals("/api/v1/source-reader/chapters/c-1", requests[1].url.encodedPath)
            assertEquals(expected, imports.find(unit))
            assertEquals(2, requests.size)
        }

    @Test
    fun aUnitNeverCopiedOrAnUnreachableServerIsNotFound() =
        runTest {
            assertNull(imports { HttpStatusCode.OK to """{"data":{"units":[{"id":"u3"}]}}""" }.find(unit))
            assertNull(imports { HttpStatusCode.BadGateway to """{"code":502,"data":{"error":"SOURCE_UNAVAILABLE"}}""" }.find(unit))
        }

    @Test
    fun aFailedFetchKeepsItsCode() =
        runTest {
            val failed = """{"data":{"titleId":"t-1","chapterId":"c-1","status":"failed","pageCount":0,"error":"NO_PAGES"}}"""
            assertEquals(
                AppResult.Ok(ImportedChapter("t-1", "c-1", ImportStatus.Failed, 0, "NO_PAGES")),
                imports { HttpStatusCode.OK to failed }.status("c-1"),
            )
        }
}
