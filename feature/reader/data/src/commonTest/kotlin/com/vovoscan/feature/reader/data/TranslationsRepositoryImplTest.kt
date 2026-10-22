package com.vovoscan.feature.reader.data

import com.vovoscan.core.common.AppResult
import com.vovoscan.core.testing.respondJson
import com.vovoscan.core.testing.testHttpClient
import com.vovoscan.feature.reader.domain.ChapterTranslation
import com.vovoscan.feature.reader.domain.PixelBox
import com.vovoscan.feature.reader.domain.TranslatedPage
import com.vovoscan.feature.reader.domain.TranslationStatus
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The unified API's translation routes (`/api/v1/translations/chapters/{id}`). */
class TranslationsRepositoryImplTest {
    private val requests = mutableListOf<HttpRequestData>()
    private val languages =
        """{"data":{"translations":[{"language":"fr","status":"ready","progress":100,"sourceLanguage":"ja","error":null,""" +
            """"version":2,"episode":{"status":"ready","audio":"dub","error":null},"updatedAt":"2026-10-22T10:00:00Z"},""" +
            """{"language":"ee","status":"translating","progress":40,"sourceLanguage":"ja","error":null,"version":0,""" +
            """"episode":null,"updatedAt":"2026-10-22T10:00:00Z"}]}}"""

    private fun repo(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = TranslationsRepositoryImpl(
        testHttpClient { request ->
            requests += request
            respondJson(body, status)
        },
    )

    @Test
    fun theChapterLanguagesComeWithTheirStatus() =
        runTest {
            val list = assertIs<AppResult.Ok<List<ChapterTranslation>>>(repo(languages).languages("c1")).value
            assertEquals(
                listOf(ChapterTranslation("fr", TranslationStatus.Ready, 100), ChapterTranslation("ee", TranslationStatus.Running, 40)),
                list,
            )
            assertEquals("/api/v1/translations/chapters/c1", requests.single().url.encodedPath)
        }

    @Test
    fun askingForALanguageSendsItAndReadsItsState() =
        runTest {
            val asked = assertIs<AppResult.Ok<ChapterTranslation>>(repo(languages, HttpStatusCode.Accepted).request("c1", "ee")).value
            assertEquals(TranslationStatus.Running, asked.status)
            val request = requests.single()
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("""{"language":"ee"}""", (request.body as TextContent).text)
        }

    @Test
    fun theReaderPagesCarryTheLettering() =
        runTest {
            val body =
                """{"data":{"chapterId":"c1","language":"fr","sourceLanguage":"ja","version":2,"canCorrect":false,""" +
                    """"pages":[{"index":0,"width":800,"height":1200,"url":"https://api.example/p0","texts":[""" +
                    """{"id":"t1","bbox":[10,20,100,50],"text":"Bonjour","fill":"#fefefe","ink":"#101010","round":true,""" +
                    """"area":[5,15,110,60]},{"id":"t2","bbox":[0,0,0,0],"text":"x","fill":"red","ink":"#000000","round":false,""" +
                    """"area":null}]}],"expiresAt":"2026-10-23T10:00:00Z"}}"""
            val page = assertIs<AppResult.Ok<List<TranslatedPage>>>(repo(body).pages("c1", "fr")).value.single()
            assertEquals(800, page.width)
            val text = page.texts.single()
            assertEquals(PixelBox(10.0, 20.0, 100.0, 50.0), text.box)
            assertEquals(PixelBox(5.0, 15.0, 110.0, 60.0), text.area)
            assertEquals("#fefefe", text.fill)
            assertEquals("/api/v1/translations/chapters/c1/read", requests.single().url.encodedPath)
            assertEquals("fr", requests.single().url.parameters["lang"])
        }
}
